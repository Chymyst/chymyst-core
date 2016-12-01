import sbt._
import Keys._

/* To compile with unchecked and deprecation warnings:
$ sbt
> set scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation", "-Xprint:namer", "-Xprint:typer")
> compile
> exit
*/

object BuildSettings {

  val buildSettings = Defaults.coreDefaultSettings ++ Seq(
    organization := "code.winitzki",
    version := "0.0.8",
    scalaVersion := "2.11.8",
    crossScalaVersions := Seq("2.11.0", "2.11.1", "2.11.2", "2.11.3", "2.11.4", "2.11.5", "2.11.6", "2.11.7", "2.11.8", "2.12.0"),
    resolvers += Resolver.sonatypeRepo("snapshots"),
    resolvers += Resolver.sonatypeRepo("releases"),
    scalacOptions ++= Seq()
  )
}

object MyBuild extends Build {
  import BuildSettings._

  lazy val joinrun: Project = Project(
    "joinrun",
    file("."),
    settings = buildSettings ++ Seq(
      libraryDependencies ++= Seq(
        "org.scalatest" %% "scalatest" % "3.0.0" % "test"
      ),
      run <<= run in Compile in lib)
  ) aggregate(macros, lib, benchmark)

  // Macros for the JoinRun library - the users will need this too.
  lazy val macros: Project = Project(
    "macros",
    file("macros"),
    settings = buildSettings ++ Seq(
      libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-reflect" % _),
      libraryDependencies ++= Seq(
        "org.scalatest" %% "scalatest" % "3.0.0" % "test",

  // this is necessary only if we want to debug macros;
  // the project does not actually depend on scala-compiler.
        "org.scala-lang" % "scala-compiler" % scalaVersion.value % "test"
      )
    )
  ) dependsOn lib

  // The JoinRun library itself - this is what users depend on.
  lazy val lib: Project = Project(
    "lib",
    file("lib"),
    settings = buildSettings ++ Seq(
      parallelExecution in Test := false,
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-actor" % "2.4.12",
        "org.scalatest" %% "scalatest" % "3.0.0" % "test"
      )
    )
  )

  // Benchmarks - users do not need to depend on this.
  lazy val benchmark: Project = Project(
    "benchmark",
    file("benchmark"),
    settings = buildSettings ++ Seq(
      parallelExecution in Test := false,
      libraryDependencies ++= Seq(
        "org.scalatest" %% "scalatest" % "3.0.0" % "test"
      )
    )
  ) dependsOn (lib, macros)

}
