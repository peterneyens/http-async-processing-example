val http4sVersion = "0.20.0-M2"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.12.7",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "http-async-processing-example",
    libraryDependencies ++= Seq(
      "io.chrisdavenport" %% "fuuid" % "0.2.0-M2",
      "io.chrisdavenport" %% "fuuid-http4s" % "0.2.0-M2",
      "io.chrisdavenport" %% "log4cats-slf4j" % "0.2.0",
      "io.chrisdavenport" %% "mules" % "0.2.0-M1",
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-blaze-server" % http4sVersion,
      "org.http4s" %% "http4s-blaze-client" % http4sVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      compilerPlugin("org.spire-math" % "kind-projector" % "0.9.8" cross CrossVersion.binary),
      compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.2.4")
    ),
    scalacOptions ++= Seq("-Ypartial-unification")
  )
