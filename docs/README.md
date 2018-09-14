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

## Status of the project

The `Chymyst Core` library is in alpha pre-release, with very few API changes envisioned for the future.

The semantics of the chemical machine (restricted to single-host, multicore computations) is fully implemented and tested on many nontrivial examples.

The library JAR is [published to Maven Central](http://search.maven.org/#search%7Cga%7C1%7Cchymyst-core).

Extensive tutorial and usage documentation is available.

Unit tests (more than 500 at the moment) exercise all aspects of the DSL provided by `Chymyst`.
Test coverage is [100% according to codecov.io](https://codecov.io/gh/Chymyst/chymyst-core?branch=master).

Test suites also complement the tutorial book and include examples such as barriers, asynchronous and synchronous rendezvous, local critical sections, parallel “or”, parallel map/reduce, parallel merge-sort, “dining philosophers”, as well as many other concurrency algorithms.

Performance benchmarks indicate that `Chymyst Core` can schedule about 100,000 reactions per second per CPU core, and the performance bottleneck is in submitting jobs to threads (a distant second bottleneck is pattern-matching in the internals of the library).
