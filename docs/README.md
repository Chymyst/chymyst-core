<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# `Chymyst`: declarative concurrency in Scala

`Chymyst` is a framework for declarative concurrency in functional programming
implementing the **Chemical Machine** paradigm, also known in the academic world as [Join Calculus](https://en.wikipedia.org/wiki/Join-calculus).
This concurrency paradigm has the same expressive power as CSP ([Communicating Sequential Processes](https://en.wikipedia.org/wiki/Communicating_sequential_processes)),
the [Pi calculus](https://en.wikipedia.org/wiki/%CE%A0-calculus), and [the Actor model](https://en.wikipedia.org/wiki/Actor_model),
but is easier to use and reason about, more high-level, and more declarative.

[`Chymyst Core`](https://github.com/Chymyst/chymyst-core) is a library that implements the high-level concurrency primitives as a domain-specific language in Scala.
[`Chymyst`](https://github.com/Chymyst/Chymyst) is a framework-in-planning that will build upon `Chymyst Core` and bring declarative concurrency to practical applications.

The code of `Chymyst Core` is a clean-room implementation and is not based on previous Join Calculus implementations, such as [`ScalaJoin` by He Jiansen](https://github.com/Jiansen/ScalaJoin) (2011) and [`ScalaJoins` by Philipp Haller](http://lampwww.epfl.ch/~phaller/joins/index.html) (2008).
The algorithm is similar to that used in my earlier Join Calculus prototypes, [Objective-C/iOS](https://github.com/winitzki/CocoaJoin) and [Java/Android](https://github.com/winitzki/AndroJoin).

# [The _Concurrency in Reactions_ tutorial book: table of contents](chymyst00.md) 

## Overview of `Chymyst` and the Chemical Machine paradigm

### [Concurrency in Reactions: Get started with this extensive tutorial book](https://winitzki.gitbooks.io/concurrency-in-reactions-declarative-multicore-in/content/)

#### [From actors to reactions: a guide for those familiar with the Actor model](https://chymyst.github.io/chymyst-core/chymyst-actor.html)

#### [A "Hello, world" project](https://github.com/Chymyst/helloworld)

#### Presentations on `Chymyst` and on the Chemical Machine programming paradigm

See [this YouTube channel](https://www.youtube.com/playlist?list=PLcoadSpY7rHWh23clCzL0SuJ4fAtzVzTb) for tutorials and presentations.

Oct. 16, 2017: Talk given at the [Scala Bay meetup](https://www.meetup.com/Scala-Bay/events/243931229):

- [Talk slides with audio](https://youtu.be/Iu2KBYNF-6M)
- See also the [talk slides (PDF)](https://github.com/winitzki/talks/blob/master/join_calculus/join_calculus_2017_Scala_Bay.pdf) and the [code examples for the talk](https://github.com/Chymyst/jc-talk-2017-examples).

July 2017: [Draft of an academic paper](https://github.com/winitzki/talks/blob/master/join-calculus-paper/join-calculus-paper.pdf) describing Chymyst and its approach to join calculus

Nov. 11, 2016: Talk given at [Scalæ by the Bay 2016](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala):

- [Video presentation of early version of `Chymyst Core`, then called `JoinRun`](https://www.youtube.com/watch?v=jawyHGjUfBU)
- See also the [talk slides revised for the current syntax](https://github.com/winitzki/talks/raw/master/join_calculus/join_calculus_2016_revised.pdf).

### [Main features of the chemical machine](chymyst_features.md)

#### [Comparison of the chemical machine vs. academic Join Calculus](chymyst_vs_jc.md#comparison-chemical-machine-vs-academic-join-calculus)

#### [Comparison of the chemical machine vs. the Actor model](chymyst_vs_jc.md#comparison-chemical-machine-vs-actor-model)

#### [Comparison of the chemical machine vs. the coroutines / channels approach (CSP)](chymyst_vs_jc.md#comparison-chemical-machine-vs-csp)

#### [Technical documentation for `Chymyst Core`](chymyst-core.md)

#### [Source code repository for `Chymyst Core`](https://github.com/Chymyst/chymyst-core)


### [Version history and roadmap](roadmap.md)


## Status of the project

The `Chymyst Core` library is in alpha pre-release, with very few API changes envisioned for the future.

The semantics of the chemical machine (restricted to single-host, multicore computations) is fully implemented and tested on many nontrivial examples.

The library JAR is [published to Maven Central](http://search.maven.org/#search%7Cga%7C1%7Cchymyst-core).

Extensive tutorial and usage documentation is available.

Unit tests (more than 500 at the moment) exercise all aspects of the DSL provided by `Chymyst`.
Test coverage is [100% according to codecov.io](https://codecov.io/gh/Chymyst/chymyst-core?branch=master).

Test suites also complement the tutorial book and include examples such as barriers, asynchronous and synchronous rendezvous, local critical sections, parallel “or”, parallel map/reduce, parallel merge-sort, “dining philosophers”, as well as many other concurrency algorithms.

Performance benchmarks indicate that `Chymyst Core` can schedule about 100,000 reactions per second per CPU core, and the performance bottleneck is in submitting jobs to threads (a distant second bottleneck is pattern-matching in the internals of the library).
