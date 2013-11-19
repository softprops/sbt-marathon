sbtPlugin := true

organization := "me.lessis"

name := "sbt-marathon"

version := "0.1.0-SNAPSHOT"

scalacOptions += Opts.compile.deprecation

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.10.0")

libraryDependencies ++= Seq("me.lessis" %% "track-jacket" % "0.1.0-SNAPSHOT", "org.slf4j" % "slf4j-jdk14" % "1.6.2")

