/*
Root project: buildAll (not executable)
Aggregates: core, benchmark

Benchmark: executable (sbt benchmark/run)

Make the main JAR of the Chymyst Core library: sbt core/package
 */

/* To compile with printed names and types:
$ sbt
> set scalacOptions in ThisBuild ++= Seq("-Xprint:namer", "-Xprint:typer")
> compile
> exit
*/

val commonSettings = Defaults.coreDefaultSettings ++ Seq(
  organization := "io.chymyst",
  version := "0.2.1-SNAPSHOT",
  scalaVersion := "2.12.4",
  crossScalaVersions := Seq("2.11.11", "2.12.4"),
  resolvers ++= Seq(
    Resolver.sonatypeRepo("snapshots"),
    Resolver.sonatypeRepo("releases"),
    "Typesafe releases" at "http://repo.typesafe.com/typesafe/releases"
  ),
  licenses := Seq("Apache License, Version 2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  homepage := Some(url("https://chymyst.github.io/chymyst-core/")),
  description := "Declarative concurrency framework for Scala - the core library implementing the abstract chemical machine / join calculus",
  //  scmInfo := Some(ScmInfo(url("git@github.com:Chymyst/chymyst-core.git"), "scm:git:git@github.com:Chymyst/chymyst-core.git", None)),
  //  developers := List(Developer(id = "winitzki", name = "Sergei Winitzki", email = "swinitzk@hotmail.com", url("https://sites.google.com/site/winitzki"))),

  scalacOptions ++= Seq(// https://tpolecat.github.io/2014/04/11/scalac-flags.html
    "-deprecation",
    "-unchecked",
    "-encoding", "UTF-8",
    "-feature",
    //    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
//    "-opt:l:project", // this is deprecated
    "-opt:l:inline",
    "-Yvirtpatmat",
    "-Ydelambdafy:inline",
    // "-Xfatal-warnings",
    "-Xlint",
    "-Yno-adapted-args", // Makes calling a() fail to substitute a Unit argument into a.apply(x: Unit)
    "-Ywarn-dead-code", // N.B. doesn't work well with the ??? hole
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-Xfuture",
    "-Ywarn-unused"
  ) ++ (//target:jvm-1.8 supported from 2.11.5, warn-unused-import deprecated in 2.12; -Xexperimental is needed for SAM closures
    if (scalaBinaryVersion.value == "2.11") {
      val revision = scalaVersion.value.split('.').last.toInt
      Seq("-Ywarn-unused-import", "-Xexperimental") ++ (
        if (revision >= 5) {
          Seq("-target:jvm-1.8")
        }
        else {
          Nil
        })
    }
    else Nil
    )
    ++ (
    if (scalaBinaryVersion.value == "2.12") Seq("-target:jvm-1.8", "-Ypartial-unification") // (SI-2712 pertains to partial-unification)
    else Nil
    )
)

lazy val errorsForWartRemover = Seq(Wart.EitherProjectionPartial, Wart.Enumeration, Wart.Equals, Wart.ExplicitImplicitTypes, Wart.FinalCaseClass, Wart.FinalVal, Wart.LeakingSealed, Wart.Return, Wart.StringPlusAny, Wart.TraversableOps, Wart.TryPartial)

lazy val warningsForWartRemover = Seq(Wart.JavaConversions, Wart.IsInstanceOf, Wart.OptionPartial) //Seq(Wart.Any, Wart.AsInstanceOf, Wart.ImplicitConversion, Wart.Option2Iterable, Wart.NoNeedForMonad, Wart.Nothing, Wart.Product, Wart.Serializable, Wart.ToString, Wart.While)

val flightRecorderJVMFlags = Seq(
  "-Xmx1G",
  "-XX:+UnlockCommercialFeatures",
  "-XX:+FlightRecorder",
  "-XX:+UnlockDiagnosticVMOptions",
  "-XX:+DebugNonSafepoints",
  "-XX:StartFlightRecording=delay=20s,duration=600s,name=Recording,filename=benchmark.jfr"
)

// Uncomment this and the tut dependency in plugin.sbt in order to build the tutorial.
enablePlugins(TutPlugin)

lazy val disableWarningsForTut = Set("-Ywarn-unused", "-Xlint")

lazy val buildAll = (project in file("."))
  .settings(commonSettings: _*)
  .settings(
    //    aggregate in assembly := false, // This would disable assembly in aggregated tasks - not what we want.

    tutSourceDirectory := (sourceDirectory in core in Compile).value / "tut",
    tutTargetDirectory := baseDirectory.value / "docs", //(crossTarget in core).value / "tut",
    scalacOptions in Tut := scalacOptions.value.filterNot(disableWarningsForTut.contains),
    name := "chymyst-core-root"
  )
  .dependsOn(core % "compile->compile;test->test")
  .aggregate(core, benchmark)
  .disablePlugins(sbtassembly.AssemblyPlugin) // do not create an assembly JAR for `buildAll`, but do create it for aggregate subprojects

lazy val core = (project in file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "chymyst-core",
    wartremoverWarnings in(Compile, compile) ++= warningsForWartRemover,
    wartremoverErrors in(Compile, compile) ++= errorsForWartRemover,
    libraryDependencies ++= Seq(
      // We need guava only because we use its concurrent hash map.
      "com.google.guava" % "guava" % "26.0-jre"
      //      "com.google.code.findbugs" % "jsr305" % "3.0.1", // Include this if there are weird compiler bugs due to guava. See http://stackoverflow.com/questions/10007994/why-do-i-need-jsr305-to-use-guava-in-scala
      , "org.apache.zookeeper" % "zookeeper" % "3.4.13"
      , "org.apache.curator" % "curator-framework" % "4.0.1"
      , "org.apache.curator" % "curator-recipes" % "4.0.1"
      //      "org.javolution" % "javolution" % "6.0.0", // source code not published on Maven Central!
      // We need scala-reflect because we use macros.
      , "org.scala-lang" % "scala-reflect" % scalaVersion.value
      , "org.scalatest" %% "scalatest" % "3.0.1" % Test
      , "org.scalacheck" %% "scalacheck" % "1.13.4" % Test
      , "com.lihaoyi" %% "utest" % "0.4.5" % Test
      , "com.twitter" %% "chill" % "0.9.3"
      // We need the "scala-compiler" only in order to debug macros;
      // the project or its tests do not actually depend on scala-compiler.
      , "org.scala-lang" % "scala-compiler" % scalaVersion.value % Provided
//      , "com.nativelibs4java" %% "scalaxy-streams" % "0.4-SNAPSHOT" % Provided // 2.12 is not published on Maven Central!
      , "org.sameersingh.scalaplot" % "scalaplot" % "0.0.4" % Test
    )
    , testFrameworks += new TestFramework("utest.runner.Framework")
  )
  .disablePlugins(sbtassembly.AssemblyPlugin) // do not create an assembly JAR for `core`

// Benchmarks - users do not need to depend on this.
lazy val benchmark = (project in file("benchmark"))
  .settings(commonSettings: _*)
  .settings(
    name := "benchmark",
    //    fork in run := true,
    test in assembly := {},
    //    unmanagedJars in Compile += file("lib/JiansenJoin-0.3.6-JoinRun-0.1.0.jar"),// they say it's no longer needed
    // Benchmarks shouldl not run concurrently.
    concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
    parallelExecution in Test := false,
    // Benchmarks can run under FlightRecorder for profiling.
    runFR := {
      // see http://jkinkead.blogspot.com/2015/04/running-with-alternate-jvm-args-in-sbt.html
      // Parse the arguments typed on the sbt console.
      val args = sbt.complete.Parsers.spaceDelimited("[main args]").parsed
      // Build up the classpath for the subprocess. Yes, this must be done manually.
      // This also ensures that your code will be compiled before you run.
      val classpath = (fullClasspath in Compile).value
      val classpathString = Path.makeString(classpath.map(_.data))
      Fork.java(
        // Full options include whatever you want to add, plus the classpath.
        ForkOptions(runJVMOptions = javaOptions.value ++ flightRecorderJVMFlags ++ Seq("-classpath", classpathString)),
        // You could also add other default arguments here.
        "io.chymyst.benchmark.MainApp" +: args
      )
    },
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.1" % Test
      , "com.lihaoyi" %% "ammonite-ops" % "1.0.0-RC7" % Test
    )
  ).dependsOn(core % "compile->compile;test->test")

// Running benchmarks with Flight Recorder.
lazy val runFR = InputKey[Unit]("runFR", "run the project with activated FlightRecorder")

/////////////////////////////////////////////////////////////////////////////////////////////////////
// Publishing to Sonatype Maven repository
publishMavenStyle := true
//
// pomIncludeRepository := { _ => false } // not sure we need this.
// http://www.scala-sbt.org/release/docs/Using-Sonatype.html says we might need it
// because "sometimes we have optional dependencies for special features".
//
publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
//
publishArtifact in Test := false
//
/////////////////////////////////////////////////////////////////////////////////////////////////////
