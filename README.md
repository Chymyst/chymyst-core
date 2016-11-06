# joinrun - A new implementation of Join Calculus in Scala
This is a micro-framework for purely functional concurrency, called “join calculus”.

The code is inspired by previous implementations by Jiansen He (https://github.com/Jiansen/ScalaJoin, 2011)
and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008).

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
