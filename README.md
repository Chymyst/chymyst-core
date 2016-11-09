# joinrun - A new implementation of Join Calculus in Scala
Join Calculus (JC) is a micro-framework for purely functional concurrency.

The code is inspired by previous implementations by Jiansen He (https://github.com/Jiansen/ScalaJoin, 2011)
and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008).

# Overview of join calculus

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


More documentation is forthcoming.

For a general introduction to Join Calculus, see [this JoCaml tutorial](https://sites.google.com/site/winitzki/tutorial-on-join-calculus-and-its-implementation-in-ocaml-jocaml).


# Main improvements

Compared to `ScalaJoin` (Jiansen He's 2011 implementation of JC), `JoinRun` offers the following improvements:

- Channels are _locally scoped values_ (instances of abstract class `JChan` having types `JA[T]` or `JS[T,R]`) rather than singleton objects, as in `ScalaJoin`; 
this is more faithful to the semantics of JC
- Reactions are also locally scoped values (instances of `JReaction`)
- Reactions and channels are composable: e.g. we can construct a join definition
 with `n` reactions and `n` channels, where `n` is a runtime parameter, with no limit on the number of reactions in one join definition, and no limit on the number of channels (no stack overflows)
- "Join definitions" are instances of class `JoinDefinition` which are invisible to the user (as they should be according to the semantics of JC)
- Some common cases of invalid join definitions are flagged (as run-time errors) even before starting any processes; others are flagged when reactions are run (e.g. if a synchronous molecule gets no reply)
- Fine-grained threading control: each join definition and each reaction can be on a different, separate thread pool; can use actor-based or thread executor-based pools
- "Fair" nondeterminism: whenever a message can start several reactions, the reaction is chosen at random
- Fault tolerance: failed reactions are restarted
- Somewhat lighter syntax for join definitions
- The user can trace the execution via logging levels; automatic naming of molecules for debugging is available (via macro)
- Unit tests and benchmarks

# Status

Current version is `0.0.5`.
The semantics of Join Calculus (restricted to single machine) is fully implemented and tested.
Unit tests include examples such as concurrent counters, parallel "or", concurrent merge-sort, and "dining philosophers".
Performance tests indicate that the runtime can schedule about 200,000 - 500,000 reactions per second per CPU core,
and the performance bottleneck is the thread switching and pattern-matching.

Known limitations:

- `JoinRun` is currently at most 2x slower than `ScalaJoin` on certain benchmarks
- Pattern-matching in join definitions is quite limited due to Scala's pattern matcher being too greedy (but this does not restrict the expressiveness of the language)
- No fairness with respect to the choice of molecules: if the same reaction could proceed with many input molecules, the input molecules are not chosen at random
- No distributed execution (Jiansen's `Disjoin.scala` is still not ported to `JoinRun`)
- No packaging as a library - so far the project is monolithic

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
via `incr` and `decr` messages.
We can also fetch the current counter value via the `get` message, which is blocking.
The counter is initialized to the number we specify.

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
    
