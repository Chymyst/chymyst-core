[![Join the chat at https://gitter.im/joinrun-scala/Lobby](https://badges.gitter.im/joinrun-scala/Lobby.svg)](https://gitter.im/joinrun-scala/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build Status](https://travis-ci.org/Chymyst/joinrun-scala.svg?branch=master)](https://travis-ci.org/Chymyst/joinrun-scala)
[![Coverage Status](https://codecov.io/gh/Chymyst/joinrun-scala/coverage.svg?branch=master)](https://codecov.io/gh/Chymyst/joinrun-scala?branch=master)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Github Tag](https://img.shields.io/github/tag/Chymyst/joinrun-scala.svg?label=release&colorB=blue)](https://github.com/Chymyst/joinrun-scala/tags)
[![Maven Central](https://img.shields.io/maven-central/v/io.chymyst/core_2.11.svg)](http://search.maven.org/#search%7Cga%7C1%7Cio.chymyst)

# `Chymyst` -- declarative concurrency in Scala

This repository hosts `Chymyst Core` -- a library that provides a Scala domain-specific language for declarative concurrency.
[`Chymyst`](https://github.com/Chymyst/Chymyst) is a framework-in-planning that will build upon `Chymyst Core` to enable creating concurrent applications declaratively.

`Chymyst` is based on the **chemical machine** paradigm, known in the academic world as [Join Calculus (JC)](https://en.wikipedia.org/wiki/Join-calculus).
JC has the same expressive power as CSP ([Communicating Sequential Processes](https://en.wikipedia.org/wiki/Communicating_sequential_processes)) and [the Actor model](https://en.wikipedia.org/wiki/Actor_model), but is easier to use.
(See also [Conceptual overview of concurrency](https://chymyst.github.io/joinrun-scala/concurrency.html).)

The initial code of `Chymyst Core` was based on [previous work by Jiansen He](https://github.com/Jiansen/ScalaJoin) (2011) and [Philipp Haller](http://lampwww.epfl.ch/~phaller/joins/index.html) (2008), as well as on Join Calculus prototypes in [Objective-C/iOS](https://github.com/winitzki/CocoaJoin) and [Java/Android](https://github.com/winitzki/AndroJoin) (2012).

The current implementation is tested under Oracle JDK 8 with Scala `2.11.8` and `2.12.1`.

[Version history and roadmap](https://chymyst.github.io/joinrun-scala/roadmap.html)

# Overview of `Chymyst` and the chemical machine paradigm

## [Get started with this extensive tutorial](https://chymyst.github.io/joinrun-scala/chymyst00.html)

### [A complete minimal "Hello, world" project](https://github.com/Chymyst/helloworld)

### [Video presentation of early version of `Chymyst Core`, then called `JoinRun`](https://www.youtube.com/watch?v=jawyHGjUfBU)

This talk was given at [Scalæ by the Bay 2016](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala).
See also these [talk slides revised for the current syntax](https://github.com/winitzki/talks/raw/master/join_calculus/join_calculus_2016_revised.pdf).

## [Main features of the chemical machine](docs/chymyst_features.md)

### [Comparison of the chemical machine vs. academic Join Calculus](docs/chymyst_vs_jc.md#comparison-chemical-machine-vs-academic-join-calculus)

### [Comparison of the chemical machine vs. the Actor model](docs/chymyst_vs_jc.md#comparison-chemical-machine-vs-actor-model)

### [Comparison of the chemical machine vs. the coroutines / channels approach (CSP)](docs/chymyst_vs_jc.md#comparison-chemical-machine-vs-csp)

### [Technical documentation for `Chymyst Core`](docs/chymyst-core.md).

# Example: "dining philosophers"

This is a complete runnable example.
The logic of "dining philosophers" is implemented in a completely declarative and straightforward code.

```scala
import io.chymyst.jc._

object Main extends App {
   /** Print message and wait for a random time interval. */
  def wait(message: String): Unit = {
    println(message)
    Thread.sleep(scala.util.Random.nextInt(20))
  }
  
  val hungry1 = m[Int]
  val hungry2 = m[Int]
  val hungry3 = m[Int]
  val hungry4 = m[Int]
  val hungry5 = m[Int]
  val thinking1 = m[Int]
  val thinking2 = m[Int]
  val thinking3 = m[Int]
  val thinking4 = m[Int]
  val thinking5 = m[Int]
  val fork12 = m[Unit]
  val fork23 = m[Unit]
  val fork34 = m[Unit]
  val fork45 = m[Unit]
  val fork51 = m[Unit]
  
  site (
    go { case thinking1(_) => wait("Socrates is thinking");  hungry1() },
    go { case thinking2(_) => wait("Confucius is thinking"); hungry2() },
    go { case thinking3(_) => wait("Plato is thinking");     hungry3() },
    go { case thinking4(_) => wait("Descartes is thinking"); hungry4() },
    go { case thinking5(_) => wait("Voltaire is thinking");  hungry5() },
  
    go { case hungry1(_) + fork12(_) + fork51(_) => wait("Socrates is eating");  thinking1() + fork12() + fork51() },
    go { case hungry2(_) + fork23(_) + fork12(_) => wait("Confucius is eating"); thinking2() + fork23() + fork12() },
    go { case hungry3(_) + fork34(_) + fork23(_) => wait("Plato is eating");     thinking3() + fork34() + fork23() },
    go { case hungry4(_) + fork45(_) + fork34(_) => wait("Descartes is eating"); thinking4() + fork45() + fork34() },
    go { case hungry5(_) + fork51(_) + fork45(_) => wait("Voltaire is eating");  thinking5() + fork51() + fork45() }
  )
  // Emit molecules representing the initial state:
  thinking1() + thinking2() + thinking3() + thinking4() + thinking5()
  fork12() + fork23() + fork34() + fork45() + fork51()
  // Now reactions will start and print messages to the console.
}

```

# Status

The `Chymyst Core` library is in alpha pre-release, with very few API changes envisioned for the future.

The semantics of the chemical machine (restricted to single-host, multicore computations) is fully implemented and tested on many nontrivial examples.

The library JAR is published to Maven Central.

Extensive tutorial and usage documentation is available.

Unit tests include examples such as concurrent counters, parallel “or”, concurrent merge-sort, and “dining philosophers”.
Test coverage is [100% according to codecov.io](https://codecov.io/gh/Chymyst/joinrun-scala?branch=master).

Performance benchmarks indicate that `Chymyst Core` can schedule about 10,000 reactions per second per CPU core, and the performance bottleneck is in submitting jobs to threads (a distant second bottleneck is pattern-matching in the internals of the library).


Known limitations:

- `Chymyst Core` is about 2x slower than Jiansen He's `ScalaJoin` on the blocking molecule benchmark, and about 1.2x slower on some non-blocking molecule benchmarks.
- `Chymyst Core` has no fairness with respect to the choice of molecules: If a reaction could proceed with many alternative sets of input molecules, the input molecules are not chosen at random.
- `Chymyst Core` has no distributed execution (Jiansen He's `Disjoin.scala` is not ported to `Chymyst`, and probably will not be).
Distributed computation should be implemented in a better way than posting channel names on an HTTP server.
(However, `Chymyst Core` will use all cores on a single machine.)

# Run unit tests

`sbt test`

The tests will print some error messages and exception stack traces - this is normal, as long as all tests pass.

Some tests are timed and will fail on a slow machine.

# Build the benchmark application

`sbt benchmark/run` will run the benchmarks.

To build the benchmark application as a self-contained JAR, run

`sbt benchmark/assembly`

Then run it as

`java -jar benchmark/target/scala-2.11/benchmark-assembly-*.jar`

# Build the library JARs

To build the library JARs:

`sbt core/package core/package-doc`

This will prepare JAR assemblies as well as their Scaladoc documentation packages.

The main library is in the `core` JAR assembly (`core/target/scala-2.11/core-*.jar`).
User code should depend on that JAR only.

# Use `Chymyst Core` in your programs

`Chymyst Core` is published to Maven Central.
Add this to your `build.sbt` at the appropriate place:

```scala
libraryDependencies ++= Seq(
  "io.chymyst" %% "core" % "latest.integration"
)

```

To use the chemical machine DSL, add `import io.chymyst.jc._` in your Scala sources.

See the ["hello, world" project](https://github.com/Chymyst/helloworld) for a complete minimal example.

# Publish to Sonatype

```bash
$ sbt
> project core
> +publishSigned
> sonatypeRelease

```

# Trivia

[![Robert Boyle's self-flowing flask](docs/Boyle_Self-Flowing_Flask.png)](https://en.wikipedia.org/wiki/Robert_Boyle#/media/File:Boyle%27sSelfFlowingFlask.png)

This drawing is by [Robert Boyle](https://en.wikipedia.org/wiki/Robert_Boyle), who was one of the founders of the science of chemistry.
In 1661 he published a treatise titled [_“The Sceptical Chymyst”_](https://upload.wikimedia.org/wikipedia/commons/thumb/d/db/Sceptical_chymist_1661_Boyle_Title_page_AQ18_%283%29.jpg/220px-Sceptical_chymist_1661_Boyle_Title_page_AQ18_%283%29.jpg), from which the `Chymyst` framework borrows its name.
