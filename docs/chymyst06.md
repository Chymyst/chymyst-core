<link href="{{ site.github.url }}/tables.css" rel="stylesheet">

# Previous work

Here are previous implementations of Join Calculus that I was able to find.

- The `Funnel` programming language: [M. Odersky et al., 2000](http://lampwww.epfl.ch/funnel/). This project was discontinued.
- _Join Java_: [von Itzstein et al., 2001-2005](http://www.vonitzstein.com/Project_JoinJava.html). This was a modified Java language compiler, with support for certain Join Calculus constructions. The project is not maintained.
- The `JoCaml` language: [Official site](http://jocaml.inria.fr) and a publication about JoCaml: [Fournet et al. 2003](http://research.microsoft.com/en-us/um/people/fournet/papers/jocaml-afp4-summer-school-02.pdf). This is a dialect of OCaml implemented as a patch to the OCaml compiler. The project is still supported.
- “Join in Scala” compiler patch: [V. Cremet 2003](http://lampwww.epfl.ch/~cremet/misc/join_in_scala/index.html). The project is discontinued.
- Joins library for .NET: [P. Crusso 2006](http://research.microsoft.com/en-us/um/people/crusso/joins/). The project is available as a binary .NET download from Microsoft Research.
- `ScalaJoins`, a prototype implementation in Scala: [P. Haller 2008](http://lampwww.epfl.ch/~phaller/joins/index.html). The project is not maintained.
- `ScalaJoin`: an improvement over `ScalaJoins`, [J. He 2011](https://github.com/Jiansen/ScalaJoin). The project is not maintained.
- Joinads, a not-quite-Join-Calculus implementation in F# and Haskell: [Petricek and Syme 2011](https://www.microsoft.com/en-us/research/publication/joinads-a-retargetable-control-flow-construct-for-reactive-parallel-and-concurrent-programming/). The project is not maintained.
- Proof-of-concept implementations of Join Calculus for iOS: [CocoaJoin](https://github.com/winitzki/AndroJoin) and Android: [AndroJoin](https://github.com/winitzki/AndroJoin). These projects are not maintained.

The implementation in `JoinRun` is based on ideas from Jiansen He's `ScalaJoin` as well as on CocoaJoin / AndroJoin.

# Other tutorials on Join Calculus

The present “chemical machine” tutorial is a non-theoretical introduction to Join Calculus for beginners.

This tutorial is based on my [earlier tutorial for JoCaml](https://sites.google.com/site/winitzki/tutorial-on-join-calculus-and-its-implementation-in-ocaml-jocaml). (However, be warned that the JoCaml tutorial is unfinished and probably contains some mistakes in some of the more advanced code examples.)

See also [my recent presentation at _Scala by the Bay 2016_](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala).
([Talk slides are available](https://github.com/winitzki/talks/tree/master/join_calculus)).
That presentation covered an early version of `JoinRun`.

There are a few academic papers on Join Calculus and a few expository descriptions, such as the Wikipedia article or the JoCaml documentation.
Unfortunately, I cannot recommend reading them - they are unsuitable for learning about the chemical paradigm.

I learned about the “Reflexive Chemical Abstract Machine” from the introduction in one of the [early papers on Join Calculus](http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.32.3078&rep=rep1&type=pdf).

Do not start by reading these papers if you are a beginner in Join Calculus - you will only be unnecessarily confused, because those texts are intended for advanced computer scientists and not pedagogically appropriate for beginners.

Also, I do not recommend reading the [Wikipedia page on Join Calculus](https://en.wikipedia.org/wiki/Join-calculus).
As of December 2016, this page says this about Join Calculus:

> The join-calculus ... can be considered, at its core, an asynchronous π-calculus with several strong restrictions:
>
> - Scope restriction, reception, and replicated reception are syntactically merged into a single construct, the _definition_;
> - Communication occurs only on defined names;
> - For every defined name there is exactly one replicated reception.
>
> However, as a language for programming, the join-calculus offers at least one convenience over the π-calculus — namely the use of multi-way join patterns, the ability to match against messages from multiple channels simultaneously.

This text is impossible to understand unless you are already well-versed in the research literature.
(What does it mean to have "communication on _defined_ names" as opposed to _undefined_ names?)

Research literature on Join Calculus typically uses terms such as "channel" or "message", which are not very helpful for understanding how to write concurrent program.

Instead of using academic terminology, I always follow the chemical machine metaphor and terminology when talking about `Chymyst` programming.
Here is a dictionary:

| Chemical machine  | Academic Join Calculus | `Chymyst` code |
|---|---|---|
| input molecule | message on channel | `case a(123) => ...` _// pattern-matching_ |
| molecule emitter | channel (port) name | `val a :  M[Int]` |
| blocking emitter | synchronous channel | `val q :  B[Unit, Int]` |
| reaction | process | `val r1 = go { case a(x) + ... => ... }` |
| emitting an output molecule | sending a message | `a(123)` _// side effect_ |
| emitting a blocking molecule | sending a synchronous message | `q()` _// returns Int_ |
| reaction site | join definition | `site(r1, r2, ...)` |

