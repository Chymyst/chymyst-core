<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# `Chymyst`: declarative concurrency in Scala

`Chymyst` is a framework for concurrency in functional programming
based on the **chemical machine** paradigm, also known as [Join Calculus](https://en.wikipedia.org/wiki/Join-calculus).
The chemical machine concurrency paradigm has the same expressive power as CSP ([Communicating Sequential Processes](https://en.wikipedia.org/wiki/Communicating_sequential_processes)) or [the Actor model](https://en.wikipedia.org/wiki/Actor_model).

[`Chymyst Core`](https://github.com/Chymyst/joinrun-scala) is a library that implements the high-level concurrency primitives as a domain-specific language in Scala.
[`Chymyst`](https://github.com/Chymyst/Chymyst) is a framework-in-planning that will build upon `Chymyst Core` and bring declarative concurrency to practical applications.

The code of `Chymyst Core` is based on previous Join Calculus implementations by Jiansen He (https://github.com/Jiansen/ScalaJoin, 2011) and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008), as well as on my earlier prototypes in [Objective-C/iOS](https://github.com/winitzki/CocoaJoin) and [Java/Android](https://github.com/winitzki/AndroJoin).

# Getting started

Begin with this [tutorial introduction](chymyst00.md).

I gave a presentation on an early version of `Chymyst Core`, at that time called `JoinRun`, at [Scala by the Bay 2016](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala). See the [talk video](https://www.youtube.com/watch?v=jawyHGjUfBU) and these [talk slides revised for the current syntax](https://github.com/winitzki/talks/raw/master/join_calculus/join_calculus_2016_revised.pdf).

Here is a minimal "Hello, world" project that uses `Chymyst`:
[https://github.com/Chymyst/helloworld](https://github.com/Chymyst/helloworld)
