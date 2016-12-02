[![Build Status](https://travis-ci.org/winitzki/joinrun-scala.svg?branch=master)](https://travis-ci.org/winitzki/joinrun-scala)

# `JoinRun` - a new implementation of Join Calculus in Scala
Join Calculus (JC) is a micro-framework for purely functional concurrency.

The code of `JoinRun` is inspired by previous implementations by Jiansen He (https://github.com/Jiansen/ScalaJoin, 2011) and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008).

The current implementation of `JoinRun` is tested under Oracle JDK 8 with Scala 2.11.8 and 2.12.0.
It also works with Scala 2.10 (except Akka support) and with OpenJDK 7 (except for the new `LocalDateTime` functions and some performance issues).

# Overview of join calculus

If you are new to Join Calculus, begin with this [tutorial introduction to `JoinRun`](doc/join_calculus_joinrun_tutorial.md).

See also my presentation at _Scala by the Bay 2016_ ([talk slides are available](https://github.com/winitzki/talks/raw/master/join_calculus/join_calculus_talk_2016.pdf)).

There is some [technical documentation for `JoinRun` library](doc/joinrun.md).

## Join Calculus vs. "Actors"

Join calculus (JC) is similar in some aspects to the well-known “actors” framework (e.g. Akka).

JC has these features that are similar to actors:

- the user's code does not explicitly work with threads / mutexes / semaphores / locks
- concurrent processes interact by message-passing
- messages carry immutable data
- JC processes start automatically when messages of certain type become available, just as actors run automatically when a message is received

Main differences between actors and JC processes:

| JC processes | Actors |
|---|---|
| concurrent processes start automatically whenever several sets of input messages are available | a desired number of actors must be created manually|
| processes are implicit, the user's code only manipulates “concurrent data” | the user's code must manipulate explicit references to actors |
| processes typically wait for (and consume) several input messages at once | actors wait for (and consume) only one input message at a time |
| processes are immutable and stateless, all data is stored on messages (which are also immutable) | actors can mutate (“become another actor”); actors can hold mutable state |
| messages are held in an unordered bag | messages are held in an ordered queue and processed in the order received |
| message data is statically typed | message data is untyped |

In talking about `JoinRun`, I follow the “chemical machine” metaphor and terminology, which differs from the terminology usually employed in academic papers on JC. Here is a dictionary:

| “Chemistry”  | JC terminology | JoinRun |
|---|---|---|
| molecule | message on channel | `a(123)` _// side effect_ |
| molecule injector | channel (port) name | `val a :  M[Int]` |
| blocking injector | blocking channel | `val q :  B[Int]` |
| reaction | process | `run { case a(x) + ... => ... }` |
| injecting a molecule | sending a message | `a(123)` _// side effect_ |
| join definition | join definition | `join(r1, r2, ...)` |

# Main features of `JoinRun`

Compared to `ScalaJoin` (Jiansen He's 2011 implementation of JC), `JoinRun` offers the following improvements:

- Molecule injectors (“channels”) are locally scoped values (instances of abstract class `Molecule`) rather than singleton objects, as in `ScalaJoin`; this is more faithful to the semantics of JC, and allows much more flexibility when defining reactions
- Reactions are also locally scoped values (instances of class `Reaction`). `JoinRun` performs some static analysis of reactions at compile time, using macros.
- Reactions and molecules are composable: e.g. we can construct a join definition with `n` reactions and `n` different molecules, where `n` is a runtime parameter, with no limit on the number of reactions in one join definition, and no limit on the number of different molecules
- “Join definitions” are instances of class `JoinDefinition` and are invisible to the user (as they should be according to the semantics of JC)
- Some common cases of invalid join definitions are flagged (as run-time errors) even before starting any processes; others are flagged when reactions are run (e.g. if a blocking molecule gets no reply)
- Fine-grained threading control: each join definition and each reaction can be on a different, separate thread pool; we can use actor-based or thread-based pools
- “Fair” nondeterminism: whenever a molecule can start several reactions, the reaction is chosen at random
- Fault tolerance: failed reactions are automatically restarted (when desired)
- Lighter syntax for join definitions, compared with previous implementations
- The user can trace the execution via logging levels; automatic naming of molecules for debugging is available (via macro)

# Status

Current version is `0.0.9`.
The semantics of Join Calculus (restricted to single machine) is fully implemented and tested.
Unit tests include examples such as concurrent counters, parallel “or”, concurrent merge-sort, and “dining philosophers”.
Performance tests indicate that the runtime can schedule about 300,000 reactions per second per CPU core, and the performance bottleneck is the thread switching and pattern-matching.

Known limitations:

- `JoinRun` is currently at most 20% slower than `ScalaJoin` on certain benchmarks that exercise a very large number of very short reactions.
- No fairness with respect to the choice of molecules: If a reaction could proceed with many alternative sets of input molecules, the input molecules are not chosen at random
- No distributed execution (Jiansen He's `Disjoin.scala` is not ported to `JoinRun`)

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
will prepare a “root”, “core”, and “macros” assemblies.

The main library is in the “core” and “macros” artifacts.

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

join (
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
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules counter(5), decr()
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules decr()
Debug: In Join{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool code.winitzki.jc.JReactionPool@57efee08 while on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with inputs decr(), counter(5)
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules decr() * 2
Debug: In Join{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with thread id 547
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting counter(4) on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules counter(4), decr() * 2
Debug: In Join{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool code.winitzki.jc.JReactionPool@57efee08 while on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with inputs decr(), counter(4)
Debug: In Join{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with thread id 548
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting counter(3) on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules counter(3), decr()
Debug: In Join{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool code.winitzki.jc.JReactionPool@57efee08 while on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with inputs decr(), counter(3)
Debug: In Join{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with thread id 549
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting counter(2) on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules counter(2)

*/
println(counter.logSoup)
/* This prints:
 Join{counter + decr => ...; counter + get/S => ...}
 Molecules: counter(2)
 */
decr() + decr() + decr()
/* This prints:
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules counter(2), decr()
Debug: In Join{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool code.winitzki.jc.JReactionPool@57efee08 while on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with inputs decr(), counter(2)
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules decr()
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting decr() on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules decr() * 2
Debug: In Join{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with thread id 613
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting counter(1) on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules counter(1), decr() * 2
Debug: In Join{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool code.winitzki.jc.JReactionPool@57efee08 while on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with inputs decr(), counter(1)
Debug: In Join{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with thread id 548
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting counter(0) on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules counter(0), decr()
*/
println(counter.logSoup)
/* This prints:
 Join{counter + decr => ...; counter + get/S => ...}
 Molecules: counter(0), decr()
 */

val x = get()
/* This results in x = 0 and prints:
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting get/S() on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules counter(0), decr(), get/S()
Debug: In Join{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + get/S => ...} on thread pool code.winitzki.jc.JReactionPool@57efee08 while on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with inputs counter(0), get/S()
Debug: In Join{counter + decr => ...; counter + get/S => ...}: reaction {counter + get/S => ...} started on thread pool code.winitzki.jc.JJoinPool@36ce2e5d with thread id 549
Debug: Join{counter + decr => ...; counter + get/S => ...} injecting counter(0) on thread pool code.winitzki.jc.JJoinPool@36ce2e5d, now have molecules counter(0), decr()
*/
```
