# joinrun - A new implementation of Join Calculus in Scala
Join Calculus (JC) is a micro-framework for purely functional concurrency.

The code is inspired by previous implementations by Jiansen He (https://github.com/Jiansen/ScalaJoin, 2011)
and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008).

# Overview of join calculus

If you are new to Join Calculus, begin with this [tutorial introduction to `JoinRun`](doc/join_calculus_joinrun_tutorial.md).

See also my presentation at _Scala by the Bay 2016_ ([talk slides are available](https://github.com/winitzki/talks/tree/master/join_calculus)).

Join calculus (JC) is somewhat similar to the well-known “actors” framework (e.g. Akka).

JC has these features that are similar to actors:

- the user's code does not explicitly work with mutexes / semaphores / locks
- concurrent processes interact by message-passing; messages carry immutable data
- JC processes start when messages of certain type become available, just as actors start processing when a message is received

Main differences between actors and JC processes:

| JC processes | Actors |
|---|---|
| concurrent processes start automatically whenever several sets of input messages are available | a desired number of actors must be created manually|
| processes are implicit, the user's code only manipulates "concurrent data" | the user's code must manipulate explicit references to actors |
| processes typically wait for (and process) several input messages at once | actors wait for (and process) only one input message at a time |
| processes are immutable and stateless, all data lives on messages | actors can mutate ("become another actor"); actors can hold mutable state |
| messages are held in an unordered bag | messages are held in an ordered queue and processed in the order received |
| messages are typed | messages are untyped |

In talking about `JoinRun`, I follow the "chemical machine" metaphor and terminology, which differs from the terminology usually employed in academic papers on JC. Here is the dictionary:

| “Chemistry”  | JC terminology | JoinRun |
|---|---|---|
| molecule | message on channel | `a(123)` _// side effect_ |
| molecule injector | channel (port) name | `val a :  JA[Int]` |
| blocking injector | blocking channel | `val q :  JS[Int]` |
| reaction | process | `run { case a(x)+...=> }` |
| injecting a molecule | sending a message | `a(123)` _// side effect_ |
| join definition | join definition | `join(r1, r2, ...)` |

There is now some [technical documetation of `JoinRun` library](doc/joinrun.md).

More documentation is forthcoming.

# Main improvements

Compared to `ScalaJoin` (Jiansen He's 2011 implementation of JC), `JoinRun` offers the following improvements:

- Molecule injectors ("channels") are _locally scoped values_ (instances of abstract class `AbsMol` having types `JA[T]` or `JS[T,R]`) rather than singleton objects, as in `ScalaJoin`; 
this is more faithful to the semantics of JC
- Reactions are also locally scoped values (instances of `JReaction`)
- Reactions and molecules are composable: e.g. we can construct a join definition
 with `n` reactions and `n` molecules, where `n` is a runtime parameter, with no limit on the number of reactions in one join definition, and no limit on the number of molecules (no stack overflows and no runtime penalty)
- "Join definitions" are instances of class `JoinDefinition` and are invisible to the user (as they should be according to the semantics of JC)
- Some common cases of invalid join definitions are flagged (as run-time errors) even before starting any processes; others are flagged when reactions are run (e.g. if a synchronous molecule gets no reply)
- Fine-grained threading control: each join definition and each reaction can be on a different, separate thread pool; we can use actor-based or thread executor-based pools
- "Fair" nondeterminism: whenever a molecule can start several reactions, the reaction is chosen at random
- Fault tolerance: failed reactions are restarted
- Somewhat lighter syntax for join definitions
- The user can trace the execution via logging levels; automatic naming of molecules for debugging is available (via macro)
- Unit tests and benchmarks

# Status

Current version is `0.0.6`.
The semantics of Join Calculus (restricted to single machine) is fully implemented and tested.
Unit tests include examples such as concurrent counters, parallel "or", concurrent merge-sort, and "dining philosophers".
Performance tests indicate that the runtime can schedule about 300,000 reactions per second per CPU core,
and the performance bottleneck is the thread switching and pattern-matching.

Known limitations:

- `JoinRun` is currently at most 20% slower than `ScalaJoin` on certain benchmarks that exercise a very large number of very short reactions.
- Pattern-matching in join definitions is limited due to Scala's pattern matcher being too greedy (but this does not restrict the expressiveness of the language)
- No fairness with respect to the choice of molecules: if the same reaction could proceed with many input molecules, the input molecules are not chosen at random
- No distributed execution (Jiansen's `Disjoin.scala` is still not ported to `JoinRun`)
- No javadocs

# Run unit tests

`sbt test`

The tests will produce some error messages and stack traces - this is normal, as long as all tests pass.

Some tests are timed and will fail on a slow machine.

# Build the benchmark application

`sbt run` will run the benchmark application.

To build a JAR:

```
sbt assembly
```
will prepare a "root", "core", and "macros" assemblies.

Run the benchmark application from JAR:

`java -jar core/target/scala-2.11/core-assembly-1.0.0.jar`

# Basic usage of `JoinRun`

Here is an example of "single-access non-blocking counter".
There is an integer counter value, to which we have non-blocking access
via `incr` and `decr` molecules.
We can also fetch the current counter value via the `get` molecule, which is blocking.
The counter is initialized to the number we specify.
```scala
    import code.winitzki.jc.JoinRun._
     
    // Define the logic of the "non-blocking counter".
    def makeCounter(initCount: Int)
                  : (JA[Unit], JA[Unit], JS[Unit, Int]) = {
      val counter = ja[Int] // non-blocking channel with integer value
      val incr = ja[Unit] // non-blocking channel with empty value
      val decr = ja[Unit] // non-blocking channel with empty value
      val get = js[Unit, Int] // blocking channel returning integer value
    
      join {
        run { counter(n) + incr(_) => counter(n+1) },
        run { counter(n) + decr(_) => counter(n-1) },
        run { counter(n) + get(_,res) => counter(n) + res(n) }
      }
    
      counter(initCount) // inject a single "counter(initCount)" message
      
      (incr, decr, get) // return the channels
    }

    // make a new counter: get the channels
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

- each molecule can be named
- a macro is available to assign names automatically
- the user can set a log level on each join definition
 
 Here are the typical results:

```scala
    import code.winitzki.jc.JoinRun._
    import code.winitzki.jc.Macros._
    
    val counter = jA[Int]
    val decr = jA[Unit]
    val get = jS[Unit,Int]
    
    join (
        run { case counter(n) + decr(_) if n > 0 => counter(n-1) },
        run { case counter(n) + get(_, res) => res(n) + counter(n) }
    )
    
    counter(5)
    
    /* Let's start debugging... */
    counter.setLogLevel(2)
    
    /* Each molecule is automatically named: */
    counter.toString // returns "counter"
    
    decr()+decr()+decr()
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
    println(counter.joinDef.get.printBag)
    /* This prints:
     Join{counter + decr => ...; counter + get/S => ...}
     Molecules: counter(2)
     */
    decr()+decr()+decr()
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
    println(counter.joinDef.get.printBag)
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
