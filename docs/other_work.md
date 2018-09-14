<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Other work on Join Calculus

Here are other implementations of Join Calculus that I was able to find online.

- The `Funnel` programming language: [M. Odersky et al., 2000](http://lampwww.epfl.ch/funnel/). This project was discontinued, and Odersky went on to create the [Scala language](https://www.scala-lang.org/), which neither includes any concepts from Funnel or JC, nor implements any ideas from JC in a standard library.
- _Join Java_: [von Itzstein et al., 2001-2005](http://www.vonitzstein.com/Project_JoinJava.html). This was a modified Java language compiler, with support for certain Join Calculus constructions. The project is not maintained.
- The `JoCaml` language: [Official site](http://jocaml.inria.fr) and a publication about JoCaml: [Fournet et al. 2003](http://research.microsoft.com/en-us/um/people/fournet/papers/jocaml-afp4-summer-school-02.pdf). This project embeds JC into OCaml and is implemented as a patch to the mainstream OCaml compiler. The project is still maintained, and a full JoCaml distribution is available with the [OCaml OPAM](https://opam.ocaml.org/) platform.
- “Join in Scala” compiler patch: [V. Cremet 2003](http://lampwww.epfl.ch/~cremet/misc/join_in_scala/index.html). The project is discontinued.
- `Joins` library for .NET: [P. Crusso 2006](http://research.microsoft.com/en-us/um/people/crusso/joins/). The project is available as a .NET binary download from Microsoft Research, and is not maintained.
- `ScalaJoins`, a prototype implementation in Scala: [P. Haller 2008](http://lampwww.epfl.ch/~phaller/joins/index.html). The project is not maintained.
- `Join`, a prototype implementation in C++/Boost [Liu Yigong 2009](http://channel.sourceforge.net/). The project is not maintained.
- `ScalaJoin`, an improvement over `ScalaJoins`: [He Jiansen 2011](https://github.com/Jiansen/ScalaJoin). The project is not maintained.
- “Joinads”, a Join-Calculus implementation as a compiler patch for F# and Haskell: [Petricek and Syme 2011](https://www.microsoft.com/en-us/research/publication/joinads-a-retargetable-control-flow-construct-for-reactive-parallel-and-concurrent-programming/). The project is not maintained.
- Implementations of Join Calculus for iOS: [CocoaJoin](https://github.com/winitzki/AndroJoin) and for Android: [AndroJoin](https://github.com/winitzki/AndroJoin). These projects are not maintained.
- [Join-Language](https://github.com/syallop/Join-Language): implementation of Join Calculus as an embedded Haskell DSL (2014). The project is in development.
- [JEScala](https://www.stg.tu-darmstadt.de/research/programming_languages/jescala_menu/index.en.jsp): an implementation of Join Calculus in Scala (2014) that accompanied [this academic paper](http://www.guidosalvaneschi.com/attachments/papers/2014_JEScala-Modular-Coordination-with-Declarative-Events-and-Joins_pdf.pdf), and its development appears to be abandoned.

The code of `Chymyst` is a clean-room implementation of join calculus, not based on the code of any of the previous work.
The chosen syntax in `Chymyst` aims to improve upon the design of He Jiansen's `ScalaJoin` as well as building on the experience of implementing CocoaJoin / AndroJoin.

## Improvements with respect to He Jiansen's `ScalaJoin`

Compared to [`ScalaJoin` (Jiansen He's 2011 implementation of JC)](https://github.com/Jiansen/ScalaJoin), `Chymyst Core` offers the following improvements:

- Lighter syntax for reaction sites (“join definitions”). Compare:

`Chymyst Core`:

```scala
val a = m[Int]
val c = b[Int, Int]
site(
  go { case a(x) + c(y, reply) ⇒
    a(x + y)
    reply(x)
  }
)
a(1)

```

`ScalaJoin`:

```scala
object join1 extends Join {
  object a extends AsyName[Int]
  object c extends SynName[Int, Int]

  join {
    case a(x) and c(y) ⇒
      a(x + y)
      c.reply(x)
  }
}
a(1)

```

- Molecule emitters (“channel names”) are not singleton objects as in `ScalaJoin` but locally scoped values in `Chymyst`. This is also the way JoCaml implements the semantics of JC. In this way, we get more flexibility in defining molecules.
- Reactions are not merely `case` clauses but locally scoped values of type `Reaction`. `Chymyst` performs static analysis of reactions and detects some errors as well as optimizations.
- Reaction sites are not static objects — they are local values of type `ReactionSite` and are invisible to the user, as they should be according to the semantics of JC.

## Improvements with respect to JoCaml

As a baseline reference, the most concise syntax for JC is available in [JoCaml](http://jocaml.inria.fr), which uses a modified OCaml compiler:

```ocaml
def a(x) & c(y) =  
   a(x+y) & reply x to c
spawn a(1)

```

In the JoCaml syntax, molecule emitters `a` and `c` are declared implicitly, together with the reaction.
Implicit declaration of molecule emitters (“channels”) is not possible in `Chymyst` because Scala macros do not allow us to insert a new top-level name declaration into the code.
So, molecule declarations need to be explicit and show the types of values (`b[Int, Int]` and so on).
Other than that, `Chymyst`'s syntax is closely modeled on that of `ScalaJoin` and JoCaml.

Another departure from JoCaml is that
in `Chymyst`, reactions can declare any number of repeated input molecules, as well as repeated blocking molecules.
The novel reply syntax disambiguates the destination of the reply value:

```scala
go { case a(x1, reply1) + a(x2, reply2) ⇒ reply2(x1); reply1(x2) }

```

This reaction is impossible to express in JoCaml directly.

## Other tutorials on Join Calculus

This book can be seen as a pragmatic, non-theoretical introduction to practical use of Join Calculus, written for programmers.

Previously I wrote a [tutorial for JoCaml](https://sites.google.com/site/winitzki/tutorial-on-join-calculus-and-its-implementation-in-ocaml-jocaml). (However, be warned that the old JoCaml tutorial is unfinished and contains mistakes in some of the more advanced code examples.)

See also [my recent presentation at _Scalæ by the Bay 2016_](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala).
([Talk slides are available](https://github.com/winitzki/talks/tree/master/join_calculus)).
That presentation covered an early version of `Chymyst`.

There are a few academic papers on Join Calculus and a few expository descriptions, such as the [Wikipedia page on Join Calculus](https://en.wikipedia.org/wiki/Join-calculus) or the JoCaml documentation.
Unfortunately, I cannot recommend reading these sources: they are unsuitable for learning about the chemical machine / Join Calculus paradigm.

I first found out about the “Reflexive Chemical Abstract Machine” from the introduction in one of the [early papers on Join Calculus](http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.32.3078&rep=rep1&type=pdf).

Do not start by reading academic papers if you never studied Join Calculus - you will be unnecessarily confused.
All Join Calculus papers I've seen are written in an obscure jargon and are intended for advanced computer scientists.

As another comparison, here is some code in academic join calculus notation, taken from [this tutorial](http://research.microsoft.com/en-us/um/people/fournet/papers/join-tutorial.pdf):

<img alt="def newVar(v0) def put(w) etc." src="academic_join_calculus_2.png" width="400" />

This code creates a shared value container `val` with synchronized single access.

The equivalent `Chymyst` code looks like this:

```scala
def newVar[T](v0: T): (B[T, Unit], B[Unit, T]) = {
  val put = b[T, Unit] 
  val get = b[Unit, T]
  val vl = m[T] // have to use `vl` since `val` is a Scala keyword
  
  site(
    go { case put(w, ret) + vl(v) ⇒ vl(w); ret() },
    go { case get(_, ret) + vl(v) ⇒ vl(v); ret(v) }
  )
  vl(v0)
  
  (put, get)
}

```

I also do not recommend reading the [Wikipedia page on Join Calculus](https://en.wikipedia.org/wiki/Join-calculus).
As of August 2018, the Wikipedia page says this about Join Calculus:

> The join-calculus ... can be considered, at its core, an asynchronous π-calculus with several strong restrictions:
>
> - Scope restriction, reception, and replicated reception are syntactically merged into a single construct, the _definition_;
> - Communication occurs only on defined names;
> - For every defined name there is exactly one replicated reception.
>
> However, as a language for programming, the join-calculus offers at least one convenience over the π-calculus — namely the use of multi-way join patterns, the ability to match against messages from multiple channels simultaneously.

This explanation is impossible to understand unless you are already well-versed in the research literature.
(For instance, I'm not sure what it means to have “communication on _defined_ names”; for instance, how could we communicate on _undefined_ names...?)

Academic literature on Join Calculus typically uses terms such as “channel” and “message”, which are not helpful for understanding how JC works and how to write concurrent programs in JC.
Indeed, a “channel” in JC holds an _unordered_ collection of messages, rather than an ordered queue or mailbox, as the word “channel” may suggest.
Another metaphor for “channel” is a persistent pathway for sending and receiving messages, but this is also far from what a JC “channel” actually does.

The word “message” suggests that a mailbox or a queue receives messages and processes them one by one.
This is very different from what happens in JC, where a reaction may wait for several “messages” at once, and different reactions may contend on several “messages” they wait for.
“Messages” in JC are better understood as specially labeled and managed data that has been made available for concurrent computations at a dedicated storage location (“reaction site”). 

The JoCaml documentation is especially confusing as regards “channels”, “messages”, and “processes”.
It confuses a “process” as a fragment of code running on some CPU thread, and a “process” as the side-effect of emitting a molecule that adds data to a reaction site. 

For these reasons, the JoCaml documentation is ill-suited as a pedagogical introduction to using join calculus or JoCaml.
Given the lack of tutorial-level documentation, it is not surprising that the JC paradigm remains unknown to the programming community.

Instead of using academic terminology, I always follow the chemical machine metaphor and terminology when talking about `Chymyst` programming.
Here is a dictionary:

| Chemical machine  | Academic Join Calculus | `Chymyst` code |
|---|---|---|
| input molecule | message on a channel | `case a(123) ⇒ ...` _// pattern-matching_ |
| molecule emitter | channel name | `val a:  M[Int]` |
| blocking emitter | synchronous channel | `val q :  B[Unit, Int]` |
| reaction | process | `val r1 = go { case a(x) + ... ⇒ ... }` |
| emitting a molecule | sending a message | `a(123)` _// side effect_ |
| emitting a blocking molecule | sending a synchronous message | `q()` _// returns_ `Int` |
| reaction site | join definition | `site(r1, r2, ...)` |

## Why the industry ignores Join Calculus

I conjecture three principal reasons for this:

1. Lack of readable tutorial documentation about the new paradigm: Almost all available documentation on Join Calculus is 100% incomprehensible to ordinary software practitioners.
2. Lack of industry-strength implementations in mainstream languages: The only well-maintained implementation (JoCaml) is in a language that is mostly used for university teaching. Apart from `Chymyst`, all other existing implementations are unmaintained academic projects. There is no industry-strength implementation of JC that provides facilities for unit testing, message-level debugging or logging, performance tuning, error recovery, or low-level thread control. (As an example, a facility to _terminate the concurrent computations_ is not present in any implementations of JC apart from `Chymyst`.)
3. Lack of a developed set of design patterns for concurrent programming in JC: Most articles and books on JC are limited to academic or toy examples, and there is no documented systematic development of design patterns for implementing concurrency tasks such as barriers, rendez-vous, critical sections, map/reduce, fork/join, asynchronous pipelines with backpressure, throttling, cancellation, timeouts, and so on.

In addition, there is no well-understood Join Calculus model for distributed computations, persistence, or consensus across a network of machines, that would compare, say, with Akka's persistence and cluster features.

## Referee criticism on the `Chymyst` paper from 2017

I submitted a [paper on `Chymyst`](https://github.com/winitzki/talks/blob/master/join-calculus-paper/join-calculus-paper.pdf) to the Scala'2017 ACM conference.
The draft was rejected with the following comments.

### Referee A

It is a good idea that someone tends to a more practically 
useful JC implementation. In order to become more popular, 
JC needs compelling real-world use cases beyond academic 
examples. 

You may want to compare against another recent JC implementation
in Scala [1]. Moreover, it would be interesting if you could 
explain what kind of implementation strategy is used and how it 
scales. For that matter, the algorithm from [2] may be relevant.
For “Modern Concurrency Abstractions for C#” there is a TOPLAS
paper that presumably supersedes the ECOOP paper.

Detailed comments:

Sec 1:

- A joinad is a type class that generalizes
pattern matching over notions of computations,
just as monads generalize sequential computation.
JC is just an instances fitting into this framework. 
Hence, joinads are not about JC per se.

Sec 2:

- My concern is that the pedagogical issues you raise
are not solved by using your own terminology, e.g.,

- The choice of names for the primitives is not mnemonic, i.e., 
  m, b, site and go. 
- Switching from ampersand to plus in join patterns is
  not a drastic improvement in readability. Moreover, it is
  misleading, because the logical interpretation of a join
  pattern is a conjunction/product, which is quite different
  from a sum. Intuitively, a site is a disjunction of conjunctions.

- 2.2.3: It is confusing that you write in the abstract
that JC is extended with non-linear patterns, whereas 
this "enhancement" is actually in comparison to JoCaml. 
JC supported multiple occurrences of the same channel in a
join pattern since its inception. Linear patterns refer to
something else, i.e., that all the bound variables (in JC lingo:
received variables) are distinct. However, this is a standard
assumption in practically any programming language that has
pattern matching, including Scala. I suggest naming 
"non-linear" join patterns differently.

- 2.2.5: I am not sure that excluding nondeterminism is a good idea
all of the time. Can programmers disable this feature?
Nondeterminism occurs when there is an interaction with several external
sources that the application does not control, e.g., mouse, keyboard, network etc.
A natural programming pattern is a state machine:
  mouse<x> & state<s> |> {  state<f(x,s)>; ... }
  key<y> & state<s> |> { state<f(y,s)>; ... }

[1] http://dl.acm.org/citation.cfm?id=2577082

[2] https://dl.acm.org/citation.cfm?id=2076021.2048111

### Referee B

Overall Chymyst looks like a well-designed project, and I like that it offers a way to explore programming with the join calculus in Scala that is easily accessible. Before publication, however, some aspects of the paper should be revised and improved:

1) The paper provides almost no details on some core aspects of the implementation. On p.6, for instance, the process search is only hinted at, while it seems to be one of the most important aspects of the system. We also learn about the existence of macros to implement static analysis without any further explanations. These aspects are sure to be of high interest to readers of the paper and attendees. For instance, we don't know if the support for non-linear patterns comes for an implementation technique.

2) One of the main claims is that the library is 'industry-strength'. While the bullet point list on p.6 is an excellent reference, a) not all points are in fact available in Chymyst, and, more importantly, b) the paper only presents very simple examples, and makes no reference to the existence of larger programs or benchmarks.

3) Generally, I found the comments on pedagogical aspects to be lacking in supportive facts. It is perfectly possible that the "visually suggestive terminology" of molecular reactions is better suited for teaching about the join calculus, but this should only be presented as a fact if it can be backed. Some opinions are stated unsubstantiated:

- p.1 "the original authors' introduction [... and] lecture notes [...] are largely incomprehensible to software engineers"
- p.2 "deficiencies of the academic terminology of JC [...] make it unhelpful for explaining the concepts of JC"
- p.3 "It appears that maintaining and supporting a completely new research language is hardly possible, even for a corporation such as Microsoft".
- p.4 "increased code clarity due to the explicit labeling"
- p.4 "[`a(x) + b(y) =>`] is somewhat easier to read than [... `a(x) & b(x)`]".

Misc. points:

- As presented, the main difference with previous implementations as described in Section 1.2 is that Chymyst is currently maintained while the other libraries are not. If there more distinguising features, they should be described, and if there are reasons to believe that the fate of Chymyst will be different, they should be laid out in the paper.

- Section 2.2.5 is about static analysis but the paragraph on unavoidable non-determinism indicates that it is detected at runtime (it refers to the runtime engine and to throwing an exception).

Summary:

*Pros*:

- the library offers an alternative for concurrent programming in Scala
- it was designed with consideration for some important aspects of software development (in particular unit testing, error recovery and compatibility with other concurrency constructs)

*Cons*:

- the "industry-strength" qualifier is not backed by examples or benchmarks
- the paper is light on implementation details
- the value of the "chemical" analogy is unclear, and the pedagogical considerations are not backed by facts

### Referee C

This is a good paper which describes a library that seems quite useful. I'm particularly happy about the discussion of pedagogy -- how we teach complex concepts is sadly under-discussed.

The major limitation of this paper is the lack of evaluation at any level. Have any applications been built with this library, that could be described? What about experience with the teaching approach? Or performance numbers? Or an evaluation of what features of JoCaml can be expressed by Chymyst? Any or all of those would make the claims of the paper easier to credit, and demonstrate the contributions more fully.

A few smaller issues:

* The work by Turon on Reagents in Scala (PLDI 2011), and also by Turon and Russo on join implementation in C# are quite related here, but not discussed. In particular, many of the syntactic conveniences here are provided by reagents, and the implementation of joins is not discussed much.

* How much of the syntactic design is novel? The paper compares mostly to JoCaml, not to embeddings in languages more similar to Scala, or to Scala join embeddings.

* It's unclear how much the "chemical" metaphor helps, versus just the analogy to actors, which as the paper says are very popular for Scala programmers.
