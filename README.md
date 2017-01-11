[![Build Status](https://travis-ci.org/Chymyst/joinrun-scala.svg?branch=master)](https://travis-ci.org/Chymyst/joinrun-scala)
[![Coverage Status](https://codecov.io/gh/Chymyst/joinrun-scala/coverage.svg?branch=master)](https://codecov.io/gh/Chymyst/joinrun-scala?branch=master)
[![Version](http://img.shields.io/badge/version-0.1.4-blue.svg?style=flat)](https://github.com/Chymyst/joinrun-scala/releases)
[![License](https://img.shields.io/github/license/mashape/apistatus.svg)](https://opensource.org/licenses/MIT)

[![Join the chat at https://gitter.im/joinrun-scala/Lobby](https://badges.gitter.im/joinrun-scala/Lobby.svg)](https://gitter.im/joinrun-scala/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

# `JoinRun` and `Chymyst` - declarative concurrency in Scala

`JoinRun` is a core library that provides a Scala domain-specific language for declarative concurrency.
`Chymyst` is a framework-in-planning that will build upon `JoinRun` to enable creating concurrent applications declaratively.

`JoinRun`/`Chymyst` are based on the **chemical machine** paradigm, known in the academic world as Join Calculus (JC).
JC has the same expressive power as CSP ([Communicating Sequential Processes](https://en.wikipedia.org/wiki/Communicating_sequential_processes)) and [the Actor model](https://en.wikipedia.org/wiki/Actor_model), but is easier to use.
(See also [Conceptual overview of concurrency](https://chymyst.github.io/joinrun-scala/concurrency.html).)

The initial code of `JoinRun` was based on previous work by Jiansen He (https://github.com/Jiansen/ScalaJoin, 2011) and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008), as well as on my earlier prototypes in [Objective-C/iOS](https://github.com/winitzki/CocoaJoin) and [Java/Android](https://github.com/winitzki/AndroJoin).

The current implementation is tested under Oracle JDK 8 with Scala 2.11 and 2.12.

[Version history and roadmap](https://chymyst.github.io/joinrun-scala/roadmap.html)

# Overview of `JoinRun`/`Chymyst`

To get started, begin with this [tutorial introduction](https://chymyst.github.io/joinrun-scala/chymyst00.html).

I gave a presentation on an early version of `JoinRun` at [Scalæ by the Bay 2016](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala). See the [talk video](https://www.youtube.com/watch?v=jawyHGjUfBU) and these [talk slides revised for the current version of `JoinRun`](https://github.com/winitzki/talks/raw/master/join_calculus/join_calculus_2016_revised.pdf).

There is some [technical documentation for `JoinRun` library](docs/joinrun.md).

# Example: "dining philosophers"

This is a complete runnable example.

```scala
import code.chymyst.jc._

object Main extends App {
   /**
   * Print message and wait for a random time interval.
   */
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
  // Now reactions will start and print to the console.
}

```

# Example: Basic usage of `JoinRun`

Here is an example of “single-access non-blocking counter”.
There is an integer counter value, to which we have non-blocking access via `incr` and `decr` molecules.
We can also fetch the current counter value via the `get` molecule, which is blocking.
The counter is initialized to the number we specify.
```scala
import code.chymyst.jc._

// Define the logic of the “non-blocking counter”.
def makeCounter(initCount: Int)
              : (M[Unit], M[Unit], B[Unit, Int]) = {
  val counter = m[Int] // non-blocking molecule with integer value
  val incr = m[Unit] // non-blocking molecule with empty (i.e. Unit) value
  val decr = m[Unit] // empty non-blocking molecule
  val get = b[Unit, Int] // empty blocking molecule returning integer value

  site {
    go { counter(n) + incr(_) => counter(n + 1) },
    go { counter(n) + decr(_) => counter(n - 1) },
    go { counter(n) + get(_,res) => counter(n) + res(n) }
  }

  counter(initCount) // emit a single “counter(initCount)” molecule

  (incr, decr, get) // return the molecule emitters
}

// make a new counter: get the emitters
val (inc, dec, get) = makeCounter(100)

// use the counter: we can be on any thread,
// we can increment and decrement multiple times,
// and there will be no race conditions

inc() // non-blocking increment
      // more code

dec() // non-blocking decrement
      // more code

val x = get() // blocking call, returns the current value of the counter
```


## Comparison: chemical machine vs. actor model

Chemical machine programming is similar in some aspects to the well-known Actor Model (e.g. the [Akka framework](https://github.com/akka/akka)).

| Chemical machine | Actor model |
|---|---|
| molecules carry values | messages carry data | 
| reactions wait to receive certain molecules | actors wait to receive certain messages | 
| synchronization is implicit in molecule emission | synchronization is implicit in message-passing | 
| reactions start when molecules are available | actors start running when a message is received |

Main differences between the chemical machine and the Actor model:

| Chemical machine | Actor model |
|---|---|
| concurrent processes start automatically whenever several input data sets are available | a desired number of concurrent actors must be created and managed manually|
| processes are implicit, the user's code only manipulates messages | the user's code must manipulate explicit references to actors as well as messages |
| processes typically wait for (and consume) several input messages at once | actors wait for (and consume) only one input message at a time |
| processes are immutable and stateless, all data is stored on messages (which are also immutable) | actors can mutate (“become another actor”); actors can hold mutable state |
| messages are held in an unordered bag and processed in random order | messages are held in an ordered queue and processed in the order received |
| message data is statically typed | message data is untyped |

In talking about `Chymyst`, I follow the chemical machine metaphor and terminology, which differs from the terminology usually employed in academic papers on JC.
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

As another comparison, here is some code in academic Join Calculus, taken from [this tutorial](http://research.microsoft.com/en-us/um/people/fournet/papers/join-tutorial.pdf):

<img alt="def newVar(v0) def put(w) etc." src="docs/academic_join_calculus_2.png" width="400" />

This code creates a shared value container `val` with synchronized single access.

The equivalent `Chymyst` code looks like this:

```scala
def newVar[T](v0: T): (B[T, Unit], B[Unit, T]) = {
  val put = b[T, Unit] 
  val get = b[Unit, T]
  val _val = m[T] // have to use `_val` since `val` is a Scala keyword
  
  site(
    go { case put(w, ret) + _val(v) => _val(w); ret() },
    go { case get(_, ret) + _val(v) => _val(v); ret(v) }
  )
  _val(v0)
  
  (put, get)
}

```

## Comparison: chemical machine vs. CSP

CSP (Communicating Sequential Processes) is another approach to declarative concurrency, used today in the Go programming language.

Similarities:

The channels of CSP are similar to blocking molecules: sending a message will block until a process can be started that consumes the message and replies with a value.

Differences:

The chemical machine admits only one reply to a blocking channel; CSP can open a channel and send many messages to it.

The chemical machine will start processes automatically and concurrently whenever input molecules are available.
In CSP, the user needs to create and manage new threads manually.

JC has non-blocking channels as a primitive construct.
In CSP, non-blocking channels need to be simulated by [additional user code](https://gobyexample.com/non-blocking-channel-operations).

# Main features of `JoinRun`

Compared to [`ScalaJoin` (Jiansen He's 2011 implementation of JC)](https://github.com/Jiansen/ScalaJoin), `JoinRun` offers the following improvements:

- Lighter syntax for reaction sites (join definitions). Compare:

JoinRun:

```scala
val a = m[Int]
val c = m[Int, Int]
site(
  go { case a(x) + c(y, reply) =>
    a(x+y)
    reply(x)
  }
)
a(1)
```

ScalaJoin:

```scala
object join1 extends Join {
  object a extends AsyName[Int]
  object c extends SynName[Int, Int]

  join {
    case a(x) and c(y) =>
      a(x+y)
      c.reply(x)
  }
}
a(1)
```

As a baseline reference, the most concise syntax for JC is available in [JoCaml](http://jocaml.inria.fr), at the price of modifying the OCaml compiler:

```ocaml
def a(x) & c(y) =  
   a(x+y) & reply x to c
spawn a(1)
```

In the JoCaml syntax, `a` and `c` are declared implicitly, together with the reaction.
This kind of implicit declaration is not possible in `JoinRun` because Scala macros do not allow us to insert a new top-level name declaration into the code.
So, declarations of molecule emitters need to be explicit.
Other than that, `JoinRun`'s syntax is closely modeled on that of `ScalaJoin` and JoCaml.

- Molecule emitters (or “channels”) are not singleton objects as in `ScalaJoin` but locally scoped values. This is how the semantics of JC is implemented in JoCaml. In this way, we get more flexibility in defining molecules.
- Reactions are not merely `case` clauses but locally scoped values (instances of class `Reaction`). `JoinRun` uses macros to perform some static analysis of reactions at compile time and detect some errors.
- Reactions and molecules are composable: we can begin constructing a reaction site incrementally, until we have `n` reactions and `n` different molecules, where `n` is a runtime parameter, with no limit on the number of reactions in one reaction site, and no limit on the number of different molecules. (However, a reaction site is immutable once it is written.)
- Reaction sites are instances of class `ReactionSite` and are invisible to the user (as they should be according to the semantics of JC).
- Some common cases of invalid chemistry are flagged (as run-time errors) before starting any processes; others are flagged when reactions are run (e.g. if a blocking molecule gets no reply).
- Fine-grained threading control: each reaction site and each reaction can be run on a different, separate thread pool; we can use Akka actor-based or thread-based pools.
- Fair nondeterminism: whenever a molecule can start several reactions, the reaction is chosen at random.
- Reactions marked as fault-tolerant will be automatically restarted if exceptions are thrown.
- Tracing the execution via logging levels; automatic naming of molecules for debugging is available (via macro).

# Status

Current released version is `0.1.4`.
The semantics of the chemical machine (restricted to single-host, multicore computations) is fully implemented and tested.

Unit tests include examples such as concurrent counters, parallel “or”, concurrent merge-sort, and “dining philosophers”.
`JoinRun` is about 50% faster than `ScalaJoin` on certain benchmarks that exercise a very large number of very short reactions.
Performance tests indicate that the runtime can schedule about 300,000 reactions per second per CPU core, and the performance bottleneck is in submitting jobs to threads (a distant second bottleneck is pattern-matching in the internals of the library).


Known limitations:

- `JoinRun` is about 3x slower than `ScalaJoin` on the blocking molecule benchmark (but faster on non-blocking molecules).
- `JoinRun` has no fairness with respect to the choice of molecules: If a reaction could proceed with many alternative sets of input molecules, the input molecules are not chosen at random.
- `JoinRun` has no distributed execution (Jiansen He's `Disjoin.scala` is not ported to `JoinRun`, and probably will not be).
Distributed computation should be implemented in a better way than posting channel names on an HTTP server.
(However, `JoinRun` will use all cores on a single machine.)

# Run unit tests

`sbt test`

The tests will produce some error messages and stack traces - this is normal, as long as all tests pass.

Some tests are timed and will fail on a slow machine.

# Build the benchmark application

`sbt benchmark/run` will run the benchmark application.

# Build the library

To build all JARs:

```
sbt assembly

```

This will prepare a `joinrun`, `benchmark`, `lib`, and `macros` JAR assemblies.

The main library is in the `joinrun` JAR assembly (`joinrun/target/scala-2.11/joinrun-assembly-*.jar`).
User code should depend on that JAR only.

