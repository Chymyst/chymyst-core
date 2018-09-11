[![Join the chat at https://gitter.im/chymyst-core/Lobby](https://badges.gitter.im/joinrun-scala/Lobby.svg)](https://gitter.im/joinrun-scala/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build Status](https://travis-ci.org/Chymyst/chymyst-core.svg?branch=master)](https://travis-ci.org/Chymyst/chymyst-core)
[![Coverage Status](https://codecov.io/gh/Chymyst/chymyst-core/coverage.svg?branch=master)](https://codecov.io/gh/Chymyst/chymyst-core?branch=master)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Github Tag](https://img.shields.io/github/tag/Chymyst/chymyst-core.svg?label=release&colorB=blue)](https://github.com/Chymyst/chymyst-core/tags)
[![Maven Central](https://img.shields.io/maven-central/v/io.chymyst/chymyst-core_2.11.svg)](http://search.maven.org/#search%7Cga%7C1%7Cio.chymyst)

# `Chymyst` — declarative concurrency in Scala

This repository hosts `Chymyst Core` — a library that provides a Scala domain-specific language for purely functional, declarative concurrency.
[`Chymyst`](https://github.com/Chymyst/Chymyst) is a framework-in-planning that will build upon `Chymyst Core` to enable creating concurrent applications declaratively.

`Chymyst` (pronounced “chemist”) implements the **chemical machine** paradigm, known in the academic world as [Join Calculus (JC)](https://en.wikipedia.org/wiki/Join-calculus).
JC has the same expressive power as CSP ([Communicating Sequential Processes](https://en.wikipedia.org/wiki/Communicating_sequential_processes)) and [the Actor model](https://en.wikipedia.org/wiki/Actor_model), but is easier to use.
(See also [Conceptual overview of concurrency](https://chymyst.github.io/chymyst-core/concurrency.html).)

The initial code of `Chymyst Core` was based on [previous work by He Jiansen](https://github.com/Jiansen/ScalaJoin) (2011) and [Philipp Haller](http://lampwww.epfl.ch/~phaller/joins/index.html) (2008), as well as on Join Calculus prototypes in [Objective-C/iOS](https://github.com/winitzki/CocoaJoin) and [Java/Android](https://github.com/winitzki/AndroJoin) (2012).

The current implementation is tested under Oracle JDK 8 with Scala `2.11.8`, `2.11.11`, and `2.12.2`-`2.12.4`.

### [Version history and roadmap](https://chymyst.github.io/chymyst-core/roadmap.html)

## Overview of `Chymyst` and the chemical machine programming paradigm

#### [Concurrency in Reactions: Get started with this extensive tutorial book](http://chemist.io/chymyst00.html)

- [Download the tutorial book in PDF](https://www.gitbook.com/download/pdf/book/winitzki/concurrency-in-reactions-declarative-multicore-in)

- [Download the tutorial book in EPUB format](https://www.gitbook.com/download/epub/book/winitzki/concurrency-in-reactions-declarative-multicore-in)

- [Manage book details (requires login)](https://www.gitbook.com/book/winitzki/concurrency-in-reactions-declarative-multicore-in/details)

#### [Project documentation at Github Pages](https://chymyst.github.io/chymyst-core/chymyst00.html)

#### [From actors to reactions: a guide for those familiar with the Actor model](https://chymyst.github.io/chymyst-core/chymyst-actor.html)

#### [Show me the code: An ultra-short guide to Chymyst](https://chymyst.github.io/chymyst-core/chymyst-quick.html)

#### [A "Hello, world" project](https://github.com/Chymyst/helloworld)

#### Presentations on `Chymyst` and on the chemical machine programming paradigm

Nov. 18, 2017: _Declarative concurrent programming with Chymyst_. Presented at the [Scale by the Bay conference](https://scalebythebay2017.sched.com/event/BLwM/declarative-concurrent-programming-with-chymyst?iframe=no&w=100%&sidebar=yes&bg=no)

- [Talk slides with audio - Long version](https://youtu.be/ubtj5g6SNaw)
- [Talk slides in PDF](https://github.com/winitzki/talks/blob/master/join_calculus/join_calculus_2017_SBTB.pdf)
- [Code examples used in the talk](https://github.com/Chymyst/jc-talk-2017-examples).

Oct. 16, 2017: _Declarative concurrent programming with Join Calculus_. Presented at the [Scala Bay meetup](https://www.meetup.com/Scala-Bay/events/243931229):

- [Talk slides with audio](https://youtu.be/Iu2KBYNF-6M)
- [Talk slides (PDF)](https://github.com/winitzki/talks/blob/master/join_calculus/join_calculus_2017_Scala_Bay.pdf)
- [Code examples used in the talk](https://github.com/Chymyst/jc-talk-2017-examples).

July 2017: [Industry-Strength Join Calculus: Declarative concurrent programming with `Chymyst`](https://github.com/winitzki/talks/blob/master/join-calculus-paper/join-calculus-paper.pdf): Draft of a paper describing Chymyst and its approach to join calculus

Nov. 11, 2016: _Concurrent Join Calculus in Scala_. Presented at [Scalæ by the Bay 2016](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala):

- [Video presentation of early version of `Chymyst`, then called `JoinRun`](https://www.youtube.com/watch?v=jawyHGjUfBU)
- See also the [talk slides revised for the current syntax](https://github.com/winitzki/talks/raw/master/join_calculus/join_calculus_2016_revised.pdf).

### [Main features of the chemical machine](docs/chymyst_features.md)

#### [Comparison of the chemical machine vs. academic Join Calculus](docs/chymyst_vs_jc.md#comparison-chemical-machine-vs-academic-join-calculus)

#### [Comparison of the chemical machine vs. the Actor model](docs/chymyst_vs_jc.md#comparison-chemical-machine-vs-actor-model)

#### [Comparison of the chemical machine vs. the coroutines / channels approach (CSP)](docs/chymyst_vs_jc.md#comparison-chemical-machine-vs-csp)

#### [Developer documentation for `Chymyst Core`](docs/chymyst-core.md)

## Example: the "dining philosophers" problem

The following code snippet is a complete runnable example
that implements the logic of "dining philosophers" in a fully declarative and straightforward way.

```scala
import io.chymyst.jc._

object Main extends App {
   /** Print message and wait for a random time interval. */
  def wait(message: String): Unit = {
    println(message)
    Thread.sleep(scala.util.Random.nextInt(20))
  }
  
  val hungry1 = m[Unit]
  val hungry2 = m[Unit]
  val hungry3 = m[Unit]
  val hungry4 = m[Unit]
  val hungry5 = m[Unit]
  val thinking1 = m[Unit]
  val thinking2 = m[Unit]
  val thinking3 = m[Unit]
  val thinking4 = m[Unit]
  val thinking5 = m[Unit]
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

## Status

The `Chymyst Core` library is in alpha pre-release, with very few API changes envisioned for the future.

The semantics of the chemical machine (restricted to single-host, multicore computations) is fully implemented and tested on many nontrivial examples.

The library JAR is published to Maven Central.

Extensive tutorial and usage documentation is available.

Unit tests (more than 500 at the moment) exercise all aspects of the DSL provided by `Chymyst`.
Test coverage is [100% according to codecov.io](https://codecov.io/gh/Chymyst/chymyst-core?branch=master).

Test suites also complement the tutorial book and include examples such as barriers, asynchronous and synchronous rendezvous, local critical sections, parallel “or”, map/reduce, parallel merge-sort, “dining philosophers”, as well as many other concurrency algorithms.

Performance benchmarks indicate that `Chymyst Core` can schedule about 100,000 reactions per second per CPU core, and the performance bottleneck is in submitting jobs to threads (a distant second bottleneck is pattern-matching in the internals of the library).

## Run unit tests

`sbt test`

The tests will print some error messages and exception stack traces - this is normal, as long as all tests pass.

Some tests are timed and will fail on a slow machine.

## Run the benchmark application

`sbt benchmark/run` will run the benchmarks.

To build the benchmark application as a self-contained JAR, run

`sbt clean benchmark/assembly`

Then run it as

`java -jar benchmark/target/scala-2.11/benchmark-assembly-*.jar`

To run with FlightRecorder:

`sbt benchmark/runFR`

This will create the file `benchmark.jfr` in the current directory.
Open that file with `jmc` (Oracle's "Java Mission Control" tool) to inspect Code and then the "Hot Methods" (places where most time is spent).

## Use `Chymyst Core` in your programs

`Chymyst Core` is published to Maven Central.
To pull the dependency, add this to your `build.sbt` at the appropriate place:

```scala
libraryDependencies += "io.chymyst" %% "chymyst-core" % "latest.integration"

```

To use the chemical machine DSL, add `import io.chymyst.jc._` in your Scala sources.

See the ["hello, world" project](https://github.com/Chymyst/helloworld) for an example.

## Build the library JARs

To build the library JARs:

`sbt core/package core/package-doc`

This will prepare JAR assemblies as well as their Scaladoc documentation packages.

The main library is in the `core` JAR assembly (`core/target/scala-2.11/core-*.jar`).
User code should depend on that JAR only.

## Prepare new release

- Edit the version string at the top of `build.sbt`
- Make sure there is a description of changes for this release at the top of `docs/roadmap.md`
- Commit everything to master and add tag with release version
- Push everything (with tag) to master; build must pass on CI

## Publish to Sonatype

```bash
$ sbt
> project core
> +publishSigned
> sonatypeRelease

```

If `sonatypeRelease` fails due to any problems with POM files while `+publishSigned` succeeded,
it is possible to release manually on the [Sonatype web site](https://oss.sonatype.org/#nexus-search;quick~chymyst-core) (requires login). Go to "Staging Repositories" and execute the actions to "promote" the release.

## Trivia

[![Robert Boyle's self-flowing flask](docs/Boyle_Self-Flowing_Flask.png)](https://en.wikipedia.org/wiki/Robert_Boyle#/media/File:Boyle%27sSelfFlowingFlask.png)

This drawing is by [Robert Boyle](https://en.wikipedia.org/wiki/Robert_Boyle), who was one of the founders of the science of chemistry.
In 1661 he published a treatise titled [_“The Sceptical Chymist”_](https://upload.wikimedia.org/wikipedia/commons/thumb/d/db/Sceptical_chymist_1661_Boyle_Title_page_AQ18_%283%29.jpg/220px-Sceptical_chymist_1661_Boyle_Title_page_AQ18_%283%29.jpg), from which the `Chymyst` framework borrows its name.
