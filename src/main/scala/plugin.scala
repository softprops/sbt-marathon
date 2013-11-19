package marathon

import dispatch._
import dispatch.Defaults._
import trackjacket.{ as => tas, _ }
import org.json4s._
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration
import java.io.File

object Plugin extends sbt.Plugin {
  import sbt.{ Future => _, _ }
  import sbt.Keys._
  import sbtassembly.Plugin.AssemblyKeys._
  import complete.DefaultParsers._

  object MarathonKeys {
    val marathon = taskKey[Unit]("lists services in fleet")
    val distribute = taskKey[Future[String]](
      "distributes assembled jar returning a resolvable uri for the distributed content. By default the locally assembled jar is used")
    val deploy = taskKey[Unit]("deploys service to fleet")
    val scale = inputKey[Unit]("scales a service to a given number of instances")
    val undeploy = taskKey[Unit]("undeploys a service from the fleet")
    val marathonHost = settingKey[String]("host of marathon server")
    val marathonPort = settingKey[Int]("port of marthon server")
    val marathonInstances = settingKey[Int]("number of instances of the service to deploy")
    val marathonMem = settingKey[Double]("amount of memory to allocate for each service instance")
    val marathonCpus = settingKey[Double]("amount of cpus to allocate for each service instance")
    val serviceIdentifier = settingKey[String]("formated service identifier used to to register with marathon. Defaults to {name}@{version}")
  }
  import MarathonKeys.{ marathon => marathonSetting, _ }

  def marathonSettings: Seq[Def.Setting[_]] = Seq(
    marathonHost := trackjacket.Client.Default.host,
    marathonPort := trackjacket.Client.Default.port,
    marathonInstances := trackjacket.Client.Default.instances,
    marathonMem := trackjacket.Client.Default.mem,
    marathonCpus := trackjacket.Client.Default.cpus,
    marathonSetting := {
      val log = streams.value.log
      log.info("fetching apps...")
      val cli = trackjacket.Client(marathonHost.value, marathonPort.value)
      (for { js <- cli.apps(as.json4s.Json) } yield {
        for {
          JArray(apps)                   <- js
          JObject(app)                   <- apps
          ("id", JString(id))            <- app
          ("instances", JInt(instances)) <- app
        } yield {
          log.info(s"service: $id instances requested: $instances")
          for (ejs <- cli.endpoint(id)(as.json4s.Json)) yield {
            for {
              JObject(endpoint) <- ejs
              ("instances", JArray(instances))  <- endpoint
              JObject(instance)                 <- instances
              ("host", JString(host))           <- instance
              ("ports", JArray(JInt(port):: _)) <- instance
            } yield {
              log.info(s"✈ $host:$port")
            }
          }
        }.apply() // TODO(*) there's probably a better way to do this
      }).apply()
      cli.close()
    },
    serviceIdentifier in marathonSetting := s"${name.value}@${version.value}",
    // remote distributions will potentially want to upload the assembly output path to a remote server
    distribute in marathonSetting <<= (outputPath in assembly).map(p => Future(p.getAbsolutePath)).dependsOn(assembly),
    deploy in marathonSetting <<= (
      distribute in marathonSetting, streams, jarName in assembly, name, version,
      marathonHost, marathonPort, marathonInstances, marathonMem, marathonCpus, serviceIdentifier in marathonSetting).map {
      (dist, out, jar, name, version, host, port, instances, mem, cpus, serviceId) =>
        val log = out.log
        val diste = dist.either
        for (uri <- diste.right) yield {
          log.info(s"marathon: deploying $serviceId to fleet...")
          val cli = trackjacket.Client(host, port)
          val start = (cli.start(serviceId)
                       .cmd(s"java -jar $jar") // TODO(*): expose java ops customization
                       .instances(instances)
                       .uris(uri)
                       .mem(mem)
                       .cpus(2.0)(as.String)).either
          for (_ <- start.right) yield log.info(
            "deployed. active endpoints may be resolved by typing convoy")
          for (e <- start.left) yield sys.error(
            s"failed to deploy $serviceId: ${e.getMessage}")
          start()
          cli.close()
        }
        for (e <- diste.left) yield sys.error(
          s"failed to distribute $serviceId: ${e.getMessage}")
        diste()
    },
    undeploy in marathonSetting := {
      val log = streams.value.log
      val serviceId = (serviceIdentifier in marathonSetting).value
      val cli = trackjacket.Client(marathonHost.value, marathonPort.value)
      log.info(s"convoy: undeploying $serviceId...")
      val stop = cli.stop(serviceId)(as.String).either
      for (_ <- stop.right) yield log.info(s"undeployed $serviceId")
      for (e <- stop.left) yield log.info(s"failed to undeploy $serviceId: ${e.getMessage}")
      stop.apply()
      cli.close()
    },
    scale in marathonSetting := {
      val to = (Space ~> token(NatBasic).examples(
        (Seq(-2, 0, 2).map(marathonInstances.value + _).map(_.toString) ++ Seq("<n>")):_*
      )).parsed
      val log = streams.value.log
      val serviceId = (serviceIdentifier in marathonSetting).value
      log.info(s"scaling $serviceId...")
      val cli = trackjacket.Client(marathonHost.value, marathonPort.value)
      cli.scale(serviceId, to)(as.String)()
      log.info(s"scaled $serviceId to $to instances")
      cli.close()
    }
  )
}
