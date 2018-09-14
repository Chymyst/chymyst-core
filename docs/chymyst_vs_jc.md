<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

## Comparison: chemical machine vs. academic Join Calculus

In talking about `Chymyst`, I follow the chemical machine metaphor and terminology, which differs from the terminology usually found in academic papers on JC.
Here is a dictionary:

| Chemical machine  | Academic Join Calculus | `Chymyst` code |
|---|---|---|
| input molecule | message on a channel | `case a(123) => ...` _// pattern-matching_ |
| molecule emitter | channel name | `val a :  M[Int]` |
| blocking emitter | synchronous channel | `val q :  B[Unit, Int]` |
| reaction | process | `val r1 = go { case a(x) + ... => ... }` |
| emitting a molecule | sending a message | `a(123)` _// side effect_ |
| emitting a blocking molecule | sending a synchronous message | `q()` _// returns_ `Int` |
| reaction site | join definition | `site(r1, r2, ...)` |

As another comparison, here is some code in academic Join Calculus notation, taken from [this tutorial](http://research.microsoft.com/en-us/um/people/fournet/papers/join-tutorial.pdf):

<img alt="def newVar(v0) def put(w) etc." src="docs/academic_join_calculus_2.png" width="400" />

This code creates a shared value container `val` with synchronized single access.

The equivalent `Chymyst` code looks like this:

```scala
def newVar[T](v0: T): (B[T, Unit], B[Unit, T]) = {
  val put = b[T, Unit] 
  val get = b[Unit, T]
  val vl = m[T] // Will use the name `vl` since `val` is a Scala keyword.
  
  site(
    go { case put(w, ret) + vl(v) => vl(w); ret() },
    go { case get(_, ret) + vl(v) => vl(v); ret(v) }
  )
  vl(v0)
  
  (put, get)
}

```

### Extensions to Join Calculus

`Chymyst` implements significantly fewer restrictions than usually present in academic versions of Join Calculus:

- reactions may have arbitrary guard conditions on molecule values
- reactions may consume several molecules of the same sort ("nonlinear input patterns")
- reactions may consume an arbitrary number of blocking input molecules, and each blocking input molecule can receive its own reply ("nonlinear reply")
- reactions are values — user's code can construct and define chemical laws incrementally at run time 

`Chymyst` also implements some additional features that are important for practical applications but not supported by academic versions of Join Calculus:

- timeouts on blocking calls
- being able to terminate a reaction site, in order to make the program stop
- explicit thread pools for controlling latency and throughput of concurrent computations

## Comparison: chemical machine vs. Actor model

Chemical machine programming is similar in some aspects to the well-known Actor model (e.g. as implemented by the [Akka library](https://github.com/akka/akka)).

### Similarities

| Chemical machine | Actor model |
|---|---|
| molecules carry values | messages carry values |
| reactions wait to consume certain molecules | actors wait to receive certain messages | 
| synchronization is implicit in molecule emission | synchronization is implicit in message-passing | 
| reaction starts when input molecules are available | actor starts running when a message is received |
| reactions can define new reactions and emit new input molecules for them | actors can create new actors and send messages to them |

### Differences

| Chemical machine | Actor model |
|---|---|
| several concurrent reactions start automatically whenever several input molecules are available | a desired number of concurrent actors must be created and managed manually |
| the user's code only manipulates molecules | the user's code must manipulate explicit references to actors as well as messages |
| reactions may wait for (and consume) several input molecules at once | actors wait for (and consume) only one input message at a time |
| reactions are immutable and stateless; all data is stored on molecules | actors can mutate (“become another actor”); actors may hold mutable state |
| molecules are held in an unordered bag and may be processed in random order | messages are held in an ordered queue (mailbox) and are processed in the order received |
| molecule data is statically typed | message data is untyped (but not if using [Akka Typed](https://doc.akka.io/docs/akka/2.5/typed/index.html)) |

## Comparison: chemical machine vs. CSP

CSP (Communicating Sequential Processes) is another approach to declarative concurrency, used today in the [Go programming language](https://golang.org/).

Similarities:

The channels of CSP are similar to blocking molecules: sending a message will block until a process can be started that consumes the message and replies with a value.

Differences:

The chemical machine admits only one reply to a blocking channel; CSP can open a channel and send many messages to it.

The chemical machine will start processes automatically and concurrently whenever input molecules are available.
In CSP, the user needs to create and manage new threads manually.

JC has non-blocking channels as a primitive construct.
In CSP, non-blocking channels need to be simulated by [additional user code](https://gobyexample.com/non-blocking-channel-operations).
