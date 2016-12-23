/*
Root project: buildAll (not executable)
Depends on: lib, macros, joinrun, benchmark
Aggregates: lib, macros, joinrun

Benchmark: executable (sbt benchmark/run)

Main JAR of the library: sbt joinrun/assembly
 */

/* To compile with printed names and types:
$ sbt
> set scalacOptions in ThisBuild ++= Seq("-Xprint:namer", "-Xprint:typer")
> compile
> exit
*/

val commonSettings = Defaults.coreDefaultSettings ++ Seq(
  organization := "code.chymyst",
  version := "0.1.3",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq("2.11.0", "2.11.1", "2.11.2", "2.11.3", "2.11.4", "2.11.5", "2.11.6", "2.11.7", "2.11.8", "2.12.0", "2.12.1"),
  resolvers += Resolver.sonatypeRepo("snapshots"),
  resolvers += Resolver.sonatypeRepo("releases"),
  resolvers += "Typesafe releases" at "http://repo.typesafe.com/typesafe/releases",

  scalacOptions ++= Seq( // https://tpolecat.github.io/2014/04/11/scalac-flags.html
    "-deprecation",
    "-unchecked",
    "-encoding", "UTF-8", // yes, this is 2 args
    "-feature",
//    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    // "-Xfatal-warnings",
    "-Xlint",
    "-Yno-adapted-args", // Makes calling a() fail to substitute a Unit argument into a.apply(x: Unit)
    "-Ywarn-dead-code", // N.B. doesn't work well with the ??? hole
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
     "-Xfuture",
    "-Ywarn-unused-import" // 2.11 only
  )
)

tutSettings

lazy val errorsForWartRemover = Seq(Wart.EitherProjectionPartial, Wart.Enumeration, Wart.Equals, Wart.ExplicitImplicitTypes, Wart.FinalCaseClass, Wart.FinalVal, Wart.LeakingSealed, Wart.NoNeedForMonad, Wart.Return, Wart.StringPlusAny, Wart.TraversableOps, Wart.TryPartial)

lazy val warningsForWartRemover = Seq() //Seq(Wart.Any, Wart.AsInstanceOf, Wart.ImplicitConversion, Wart.IsInstanceOf, Wart.JavaConversions, Wart.Option2Iterable, Wart.OptionPartial, Wart.Nothing, Wart.Product, Wart.Serializable, Wart.ToString, Wart.While)

val rootProject = Some(buildAll)

lazy val buildAll = (project in file("."))
  .settings(commonSettings: _*)
  .settings(
    name := "buildAll"
  )
  .aggregate(lib, macros, joinrun, benchmark)

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
  .dependsOn(lib, macros)

// Macros for the JoinRun library.
lazy val macros = (project in file("macros"))
  .settings(commonSettings: _*)
  .settings(
    name := "macros",
    wartremoverWarnings in (Compile, compile) ++= warningsForWartRemover,
    wartremoverErrors in (Compile, compile) ++= errorsForWartRemover,
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
    wartremoverWarnings in (Compile, compile) ++= warningsForWartRemover,
    wartremoverErrors in (Compile, compile) ++= errorsForWartRemover,
    libraryDependencies ++= Seq(
      //        "com.typesafe.akka" %% "akka-actor" % "2.4.12",
      "org.scalacheck" %% "scalacheck" % "1.13.4" % "test",
      "org.scalatest" %% "scalatest" % "3.0.0" % "test"
    )
  )

// Benchmarks - users do not need to depend on this.
lazy val benchmark = (project in file("benchmark"))
  .settings(commonSettings: _*)
  .settings(
    name := "benchmark",
    aggregate in assembly := false,
    concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.0" % "test"
    )
  ).dependsOn(joinrun)

