[![Build Status](https://travis-ci.org/chymyst/joinrun-scala.svg?branch=master)](https://travis-ci.org/chymyst/joinrun-scala)
[![Coverage Status](https://codecov.io/gh/chymyst/joinrun-scala/coverage.svg?branch=master)](https://codecov.io/gh/chymyst/joinrun-scala?branch=master)
[![Version](http://img.shields.io/badge/version-0.1.2-blue.svg?style=flat)](https://github.com/chymyst/joinrun-scala/releases)
[![License](https://img.shields.io/github/license/mashape/apistatus.svg)](https://opensource.org/licenses/MIT)

[![Join the chat at https://gitter.im/joinrun-scala/Lobby](https://badges.gitter.im/joinrun-scala/Lobby.svg)](https://gitter.im/joinrun-scala/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

# `JoinRun` and `Chymyst` - declarative concurrency in Scala

`JoinRun` is a core library that provides a Scala domain-specific language for declarative concurrency.
`Chymyst` is a framework-in-planning that will build upon `JoinRun` to enable creating concurrent applications declaratively.

`JoinRun`/`Chymyst` are based on the chemical machine paradigm, known in the academic world as Join Calculus (JC).
JC has the same expressive power as CSP ([Communicating Sequential Processes](https://en.wikipedia.org/wiki/Communicating_sequential_processes)) and [the Actor model](https://en.wikipedia.org/wiki/Actor_model), but is easier to use.

The initial code of `JoinRun` was based on previous work by Jiansen He (https://github.com/Jiansen/ScalaJoin, 2011) and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008), as well as on my earlier prototypes in [Objective-C/iOS](https://github.com/winitzki/CocoaJoin) and [Java/Android](https://github.com/winitzki/AndroJoin).

The current implementation is tested under Oracle JDK 8 with Scala 2.11 and 2.12.
It also works with Scala 2.10 and with OpenJDK 7 (except for the new `LocalDateTime` functions used in tests, and some performance issues).

# Overview of `JoinRun`

To get started, begin with this [tutorial introduction](http://chymyst.github.io/joinrun-scala/chymyst00.html).

I gave a presentation on an early version of `JoinRun` at [Scalæ by the Bay 2016](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala). See the [talk video](https://www.youtube.com/watch?v=jawyHGjUfBU) and these [talk slides revised for the current version of `JoinRun`](https://github.com/winitzki/talks/raw/master/join_calculus/join_calculus_2016_revised.pdf).

There is some [technical documentation for `JoinRun` library](docs/joinrun.md).


## Comparison: JC vs. actor model

JC is similar in some aspects to the well-known actor model framework (e.g. Akka).

JC has these features that are similar to actors:

- the user's code does not explicitly work with threads / mutexes / semaphores / locks / monitors
- concurrent processes interact by message-passing
- messages carry immutable data
- JC processes start automatically when messages of certain type become available, just as actors run automatically when a message is received

Main differences between actors and JC processes:

| JC processes | Actors |
|---|---|
| concurrent processes start automatically whenever several input data sets are available | a desired number of concurrent actors must be created manually|
| processes are implicit, the user's code only manipulates messages | the user's code must manipulate explicit references to actors as well as messages |
| processes typically wait for (and consume) several input messages at once | actors wait for (and consume) only one input message at a time |
| processes are immutable and stateless, all data is stored on messages (which are also immutable) | actors can mutate (“become another actor”); actors can hold mutable state |
| messages are held in an unordered bag | messages are held in an ordered queue and processed in the order received |
| message data is statically typed | message data is untyped |

In talking about `JoinRun` and `Chymyst`, I follow the “chemical machine” metaphor and terminology, which differs from the terminology usually employed in academic papers on JC.
Here is a dictionary:

| Chemical machine  | Join Calculus | JoinRun |
|---|---|---|
| input molecule | message on channel | `case a(123) => ...` _// pattern-matching_ |
| molecule injector | channel (port) name | `val a :  M[Int]` |
| blocking injector | synchronous channel | `val q :  B[Unit, Int]` |
| reaction | process | `val r1 = run { case a(x) + ... => ... }` |
| injecting an output molecule | sending a message | `a(123)` _// side effect_ |
| injecting a blocking molecule | sending a synchronous message | `q()` _// returns Int_ |
| reaction site | join definition | `site(r1, r2, ...)` |

## Comparison: JC vs. CSP

Similarities:

The channels of CSP are similar to JC's blocking molecules: sending a message will block until a process can be started that consumes the message and replies with a value.

Differences:

JC admits only one reply to a blocking channel; CSP can open a channel and send many messages to it.

JC will start processes automatically and concurrently whenever input molecules are available.
In CSP, the user needs to create and manage new threads manually.

JC has non-blocking channels as a primitive construct.
In CSP, non-blocking channels need to be simulated by [additional user code](https://gobyexample.com/non-blocking-channel-operations).

# Main features of `JoinRun`

Compared to [`ScalaJoin` (Jiansen He's 2011 implementation of JC)](https://github.com/Jiansen/ScalaJoin), `JoinRun` offers the following improvements:

- Lighter syntax for join definitions. Compare:

JoinRun:

```scala
val a = m[Int]
val c = m[Int, Int]
site(
  run { case a(x) + c(y, reply) =>
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
So, declarations of molecule injectors need to be explicit.
Other than that, `JoinRun`'s syntax is closely modeled on that of `ScalaJoin` and JoCaml.

- Molecule injectors (or “channels”) are not singleton objects as in `ScalaJoin` but locally scoped values. This is how the semantics of JC is implemented in JoCaml. In this way, we get more flexibility in defining molecules.
- Reactions are not merely `case` clauses but locally scoped values (instances of class `Reaction`). `JoinRun` uses macros to perform some static analysis of reactions at compile time and detect some errors.
- Reactions and molecules are composable: we can begin constructing a join definition incrementally, until we have `n` reactions and `n` different molecules, where `n` is a runtime parameter, with no limit on the number of reactions in one join definition, and no limit on the number of different molecules. (However, a join definition is immutable once it is written.)
- Join definitions are instances of class `ReactionSite` and are invisible to the user (as they should be according to the semantics of JC).
- Some common cases of invalid join definitions are flagged (as run-time errors) before starting any processes; others are flagged when reactions are run (e.g. if a blocking molecule gets no reply).
- Fine-grained threading control: each join definition and each reaction can be on a different, separate thread pool; we can use Akka actor-based or thread-based pools.
- Fair nondeterminism: whenever a molecule can start several reactions, the reaction is chosen at random.
- Reactions marked as fault-tolerant will be automatically restarted if exceptions are thrown.
- Tracing the execution via logging levels; automatic naming of molecules for debugging is available (via macro).

# Status

Current version is `0.1.2`.
The semantics of JC (restricted to single machine) is fully implemented and tested.

Unit tests include examples such as concurrent counters, parallel “or”, concurrent merge-sort, and “dining philosophers”.
`JoinRun` is about 50% faster than `ScalaJoin` on certain benchmarks that exercise a very large number of very short reactions.
Performance tests indicate that the runtime can schedule about 300,000 reactions per second per CPU core, and the performance bottleneck is in submitting jobs to threads (a distant second bottleneck is pattern-matching in the internals of the library).


Known limitations:

- `JoinRun` is about 3x slower than `ScalaJoin` on the blocking molecule benchmark.
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
will prepare a `joinrun`, `benchmark`, `lib`, and `macros` JAR assemblies.

The main library is in the `joinrun` JAR assembly.
User code should depend on that JAR only.

# Basic usage of `JoinRun`

Here is an example of “single-access non-blocking counter”.
There is an integer counter value, to which we have non-blocking access via `incr` and `decr` molecules.
We can also fetch the current counter value via the `get` molecule, which is blocking.
The counter is initialized to the number we specify.
```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

// Define the logic of the “non-blocking counter”.
def makeCounter(initCount: Int)
              : (M[Unit], M[Unit], B[Unit, Int]) = {
  val counter = m[Int] // non-blocking molecule with integer value
  val incr = m[Unit] // non-blocking molecule with empty value
  val decr = m[Unit] // empty non-blocking molecule
  val get = b[Unit, Int] // empty blocking molecule returning integer value

  join {
    run { counter(n) + incr(_) => counter(n+1) },
    run { counter(n) + decr(_) => counter(n-1) },
    run { counter(n) + get(_,res) => counter(n) + res(n) }
  }

  counter(initCount) // inject a single “counter(initCount)” molecule

  (incr, decr, get) // return the molecule injectors
}

// make a new counter: get the injectors
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

# Debugging and macros

It is sometimes not easy to make sure that the reactions are correctly designed.
The library offers some debugging facilities:

- each molecule is named
- a macro is available to assign names automatically
- the user can set a log level on each join definition
 
 Here are the typical results:

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

val counter = b[Int] // the name of this molecule is "counter"
val decr = b[Unit] // the name is "decr"
val get = b[Unit,Int] // the name is "get"

site (
    run { case counter(n) + decr(_) if n > 0 => counter(n-1) },
    run { case counter(n) + get(_, res) => res(n) + counter(n) }
)

counter(5)

/* Let's start debugging... */
counter.setLogLevel(2)

/* Each molecule is automatically named: */
counter.toString // returns the string "counter"

decr() + decr() + decr()
/* This prints:
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules counter(5), decr()
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules decr()
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool code.winitzki.jc.ReactionPool@57efee08 while on thread pool code.winitzki.jc.SitePool@36ce2e5d with inputs decr(), counter(5)
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules decr() * 2
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool code.winitzki.jc.SitePool@36ce2e5d with thread id 547
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting counter(4) on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules counter(4), decr() * 2
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool code.winitzki.jc.ReactionPool@57efee08 while on thread pool code.winitzki.jc.SitePool@36ce2e5d with inputs decr(), counter(4)
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool code.winitzki.jc.SitePool@36ce2e5d with thread id 548
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting counter(3) on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules counter(3), decr()
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool code.winitzki.jc.ReactionPool@57efee08 while on thread pool code.winitzki.jc.SitePool@36ce2e5d with inputs decr(), counter(3)
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool code.winitzki.jc.SitePool@36ce2e5d with thread id 549
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting counter(2) on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules counter(2)

*/
println(counter.logSoup)
/* This prints:
 Site{counter + decr => ...; counter + get/S => ...}
 Molecules: counter(2)
 */
decr() + decr() + decr()
/* This prints:
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules counter(2), decr()
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool code.winitzki.jc.ReactionPool@57efee08 while on thread pool code.winitzki.jc.SitePool@36ce2e5d with inputs decr(), counter(2)
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules decr()
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules decr() * 2
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool code.winitzki.jc.SitePool@36ce2e5d with thread id 613
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting counter(1) on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules counter(1), decr() * 2
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool code.winitzki.jc.ReactionPool@57efee08 while on thread pool code.winitzki.jc.SitePool@36ce2e5d with inputs decr(), counter(1)
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool code.winitzki.jc.SitePool@36ce2e5d with thread id 548
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting counter(0) on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules counter(0), decr()
*/
println(counter.logSoup)
/* This prints:
 Site{counter + decr => ...; counter + get/S => ...}
 Molecules: counter(0), decr()
 */

val x = get()
/* This results in x = 0 and prints:
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting get/S() on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules counter(0), decr(), get/S()
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + get/S => ...} on thread pool code.winitzki.jc.ReactionPool@57efee08 while on thread pool code.winitzki.jc.SitePool@36ce2e5d with inputs counter(0), get/S()
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + get/S => ...} started on thread pool code.winitzki.jc.SitePool@36ce2e5d with thread id 549
Debug: Site{counter + decr => ...; counter + get/S => ...} injecting counter(0) on thread pool code.winitzki.jc.SitePool@36ce2e5d, now have molecules counter(0), decr()
*/
```
