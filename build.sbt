/*
Main project: joinrun (not executable)
Depends on: lib, macros
Aggregates: lib, macros, benchmarks

Benchmarks: executable
 */

/* To compile with unchecked and deprecation warnings:
$ sbt
> set scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation", "-Xprint:namer", "-Xprint:typer")
> compile
> exit
*/

val commonSettings = Defaults.coreDefaultSettings ++ Seq(
  organization := "code.winitzki",
  version := "0.1.0",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq("2.11.0", "2.11.1", "2.11.2", "2.11.3", "2.11.4", "2.11.5", "2.11.6", "2.11.7", "2.11.8", "2.12.0", "2.12.1"),
  resolvers += Resolver.sonatypeRepo("snapshots"),
  resolvers += Resolver.sonatypeRepo("releases"),
  resolvers += "Typesafe releases" at "http://repo.typesafe.com/typesafe/releases",
  scalacOptions ++= Seq()
)

lazy val joinrun = (project in file("joinrun"))
  .settings(commonSettings: _*)
  .settings(
  	name := "joinrun",
  	libraryDependencies ++= Seq(
    	"org.scala-lang" % "scala-reflect" % scalaVersion.value % "test",
    	"org.scalatest" %% "scalatest" % "3.0.0" % "test"
    ),
    parallelExecution in Test := false,
    concurrentRestrictions in Global += Tags.limit(Tags.Test, 1)
  )
  .aggregate(lib, macros, benchmark).dependsOn(lib, macros)

// Macros for the JoinRun library.
lazy val macros = (project in file("macros"))
  .settings(commonSettings: _*)
  .settings(
    name := "macros",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "org.scalatest" %% "scalatest" % "3.0.0" % "test",

      // the "scala-compiler" is a necessary dependency only if we want to debug macros;
      // the project does not actually depend on scala-compiler.
      "org.scala-lang" % "scala-compiler" % scalaVersion.value % "test"
    )
  ).dependsOn(lib)

// The core JoinRun library without macros.
lazy val lib = (project in file("lib"))
  .settings(commonSettings: _*)
  .settings(
    name := "lib",
    parallelExecution in Test := false,
    concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
    libraryDependencies ++= Seq(
      //        "com.typesafe.akka" %% "akka-actor" % "2.4.12",
      "org.scalatest" %% "scalatest" % "3.0.0" % "test"
    )
  )

// Benchmarks - users do not need to depend on this.
lazy val benchmark = (project in file("benchmark"))
  .settings(commonSettings: _*)
  .settings(
    name := "benchmark",
    concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.0" % "test"
    )
  ).dependsOn(lib, macros)

