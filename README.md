# joinrun - A new implementation of Join Calculus in Scala
Join Calculus (JC) is a micro-framework for purely functional concurrency.

The code is inspired by previous implementations by Jiansen He (https://github.com/Jiansen/ScalaJoin, 2011)
and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008).

# Overview of join calculus

Join calculus (JC) is somewhat similar to the well-known “actors” framework (e.g. Akka).

JC has these features that are similar to actors:

- the user's code does not explicitly work with threads / semaphores / locks, synchronization is declarative
- concurrent processes interact by message-passing

Main differences between actors and JC processes:

- actors are untyped and mutable (an actor can "become" another actor); JC processes is type-safe and (is designed to be) immutable and purely functional
- actors can hold mutable state; JC processes are stateless
- actors are created and maintained by hand, so the user's code needs to manipulate references to actors;
JC processes are implicit, so that new JC processes are started automatically, and the user's code does not need to describe threads
by the runtime whenever input data is available for processing
- actors wait on a channel that holds messages in an ordered queue; JC processes can wait on several channels at once,
while messages are accumulated in an unordered bag

More documentation and tutorials are forthcoming.

# Main improvements

Compared to `ScalaJoin` (Jiansen He's 2011 implementation of JC), `JoinRun` offers the following improvements:

- Channels are _locally scoped values_ (instances of abstract class `JChan` having types `JA[T]` or `JS[T,R]`) rather than singleton objects, as in `ScalaJoin`; 
this is more faithful to the semantics of JC
- Reactions are also locally scoped values (instances of `JReaction`)
- Reactions and channels are composable: e.g. we can construct a join definition
 with `n` reactions and `n` channels, where `n` is a runtime parameter, with no limit on the number of reactions in one join definition, and no limit on the number of channels
- "Join definitions" are instances of class `JoinDefinition` which are invisible to the user (as they should be according to the semantics of JC)
- Some common cases of invalid join definitions are flagged (as run-time errors) even before starting any processes
- Fine-grained threading control: each join definition and each reaction can be on a different, separate thread pool
- "Fair" nondeterminism: whenever a message can start several reactions, the reaction is chosen at random
- Fault tolerance: failed reactions are restarted
- Somewhat lighter syntax (but still no macros and no introspection)
- Unit tests and benchmarks

# Status

Current version is `0.0.3`

- `JoinRun` is currently at most 2x slower than `ScalaJoin` on certain benchmarks
- Actor-based concurrency, distribution, and a few other significant features are not yet implemented

# Run unit tests

`sbt test`

The tests will produce some error messages and stack traces - this is normal, as long as all tests pass.

# Build the benchmark application

```
sbt assembly
```
will prepare a "root", "core", and "macros" assemblies.

Run the benchmark application:

`java -jar core/target/scala-2.11/core-assembly-1.0.0.jar`

# Basic usage of `JoinRun`

Here is an example of "synchronized non-blocking counter".
There is an integer counter value, to which we have non-blocking access
via `incr` and `decr` messages.
We can also fetch the current counter value via the `get` message, which is blocking.
The counter is initialized to zero.

    import code.winitzki.jc.JoinRun._
     
    def makeCounter(initCount: Int)
                  : (JA[Unit], JA[Unit], JS[Unit, Int]) = {
      val counter = ja[Int] // same as new JAsynChan[T]
      val incr = ja[Unit]
      val decr = ja[Unit]
      val get = js[Unit, Int]
    
      join {
        run { counter(n) + incr(_) => counter(n+1) },
        run { counter(n) + decr(_) => counter(n-1) },
        run { counter(n) + get(_,res) => counter(n) + res(n) }
      }
    
      counter(initCount)
      
      (incr, decr, get)
    }

    // make a new counter
    
    val (inc, dec, get) = makeCounter(100)
    
    // use the counter: we can be on any thread,
    // we can increment and decrement multiple times,
    // and still there will be no race conditions
    
    inc() // non-blocking increment
          // more code
    
    dec() // non-blocking decrement
          // more code
     
    val x = get() // blocking call, returns the current value of the counter
    
