# `JoinRun` library documentation

`JoinRun` is an implementation of Join Calculus as an embedded DSL in Scala.

Currently, it compiles with Scala 2.11 and Scala 2.12 on Oracle JDK 8.

# Previous work

Here are previous implementations of Join Calculus that I was able to find.
- The `Funnel` programming language: [M. Odersky et al., 2000](http://lampwww.epfl.ch/funnel/). This project was discontinued.
- _Join Java_: [von Itzstein et al., 2001-2005](http://www.vonitzstein.com/Project_JoinJava.html). This was a modified Java language. The project is not maintained. 
- The `JoCaml` language: [Official site](http://jocaml.inria.fr) and a publication about JoCaml: [Fournet et al. 2003](http://research.microsoft.com/en-us/um/people/fournet/papers/jocaml-afp4-summer-school-02.pdf). This is a dialect of OCaml implemented as a patch to the OCaml compiler. The project is still supported. 
- “Join in Scala” compiler patch: [V. Cremet 2003](http://lampwww.epfl.ch/~cremet/misc/join_in_scala/index.html). The project is discontinued.
- Joins library for .NET: [P. Crusso 2006](http://research.microsoft.com/en-us/um/people/crusso/joins/). The project is available as a binary .NET download from Microsoft Research.
- `ScalaJoins`, a prototype implementation in Scala: [P. Haller 2008](http://lampwww.epfl.ch/~phaller/joins/index.html). The project is not maintained.
- `ScalaJoin`: an improvement over `ScalaJoins`, [J. He 2011](https://github.com/Jiansen/ScalaJoin). The project is not maintained.
- Joinads, a not-quite-Join-Calculus implementation in F# and Haskell: [Petricek and Syme 2011](https://www.microsoft.com/en-us/research/publication/joinads-a-retargetable-control-flow-construct-for-reactive-parallel-and-concurrent-programming/). The project is not maintained.
- Proof-of-concept implementations of Join Calculus for iOS: [CocoaJoin](https://github.com/winitzki/AndroJoin) and Android: [AndroJoin](https://github.com/winitzki/AndroJoin). These projects are not maintained.

The implementation in `JoinRun` is based on ideas from Jiansen He's `ScalaJoin` as well as on CocoaJoin / AndroJoin.

# Main structures

Join Calculus is implemented using molecule injectors, reactions, and join definitions.

There are only two primitive operations:

- define reactions by writing a join definition
- inject molecules by calling molecule injectors with argument values

## Molecule injectors

Molecule injectors are instances of one of the two classes:
- `M[T]` for non-blocking molecules carrying a value of type `T`
- `B[T, R]` for blocking molecules carrying a value of type `T` and returning a value of type `R`

Molecule injectors should be defined as local values, before these molecules can be used in reactions.

```scala
val x = new M[Int]("x")
val y = new B[Unit, String]("y")
```

Each molecule carries a name.
The name will be used when printing the molecule for debugging purposes.
Otherwise, names have no effect on runtime behavior.

The convenience macros `m` and `b` can be used to further reduce the boilerplate:

```scala
val counter = m[Int] // same as new M[Int]("counter")
val fetch = b[Unit, String] // same as new B[Unit, String]("fetch")
```

These macros will read the enclosing `val` definition at compile time and substitute the name of the variable into the class constructor.

## Injecting molecules

Molecule injectors inherit from `Function1` and can be used as functions with one argument.
Calling these functions will perform the side-effect of injecting the molecule into the soup that pertains to the join definition to which the molecule is bound.

```scala
... M[T] extends Function1[T, Unit]
... B[T, R] extends Function1[T, R]

val x = new M[Int]("x") // define injector using class constructor

// Need to define reactions - this is omitted here.

x(123) // inject molecule with value 123

val y = m[Unit] // define injector using macro

y() // inject molecule with unit value

val f = b[Int, String] // define injector using macro

val result = f(10) // injecting a blocking molecule: "result" is of type String
```

It is a runtime error to inject molecules that is not yet bound to any join definition.

### Timeout for a blocking molecule

The call to inject a blocking molecule will block until some reaction consumes that molecule and the molecule receives a "reply action" with a value.

A timeout can be imposed on that call by using this syntax:

```scala
import scala.concurrent.duration.DurationInt

val f = b[Int, String]

val result: Option[String] = f(timeout = 100 millis)(10)
```

Injection with timeout results in an `Option` value.
The value will be `None` if timeout is reached.

Exceptions may be thrown as a result of injecting of a blocking molecule when it is unblocked:
For instance, this happens when the reaction code attempts to execute the reply action more than once.

## Debugging

Molecule injectors have the method `setLogLevel`, which is by default set to 0.
Positive values will lead to more debugging output.

The log level will affect the entire join definition to which the molecule is bound.

```scala
val x = m[Int]
x.setLogLevel(2)
```

The method `logSoup` returns a string that represents the molecules currently present in the join definition to which the molecule is bound.

```scala
val x = m[Int]
join(...)
println(x.logSoup)
```

It is a runtime error to use `setLogLevel` or `logSoup` on molecules that are not yet bound to any join definition.

## Reactions

A reaction is an instance of class `Reaction`.
It is created using the `run` method with a partial function syntax that resembles pattern-matching on molecule values:

```scala
val reaction1 = run { case a(x) + b(y) => a(x+y) }
```

Molecule injectors appearing within the pattern match (between `case` and  `=>`) are the **input molecules** of that reaction.
Molecule injectors used in the reaction body (which is the Scala expression after `=>`) are the **output molecules** of that reaction.

All input and output molecule injectors involved in the reaction must be already defined and visible in the local scope.
(Otherwise, there will be a compile error.)

Note: Although molecules with `Unit` type can be injected as `a()`, the pattern-matching syntax for those molecules must be `case a(_) + ... => ...` 

### Blocking molecules in reactions

Blocking molecules use a syntax that suggests the existence of a special pseudo-molecule that performs the reply action:

```scala
val a = new M[Int]("a") // non-blocking molecule
val f = new B[Int, String]("f") // blocking molecule

val reaction2 = run { case a(x) + f(y, r) => r(x.toString) + a(y) }

val result = f(123) // result is of type String
```

In this reaction, the pattern-match on `f(y, r)` involves two pattern variables:
- The pattern variable `y` is of type `Int` and matches the value carried by the injected molecule `f(123)`
- The pattern variable `r` is of private type `ReplyValue[Int, String]` and matches and object that performs the reply action aimed at the caller of `f(123)`.
Calling it as `r(x.toString)` will perform the reply action, - that is, will send the string back to the calling process, unblocking the call to `f(123)` in that process.

This reply action must be performed as `r(...)` in the reaction body exactly once, and cannot be performed afterwards.

It is a runtime error to write a reaction that either does not inject the reply action or uses it more than once.

Also, the reply action object should not be used outside the reaction body.
(This will have no effect.)

When a reaction is defined using the `run` macro, the compiler will detect some errors at compile time.
For instance, it is a compile-time error to omit the reply pseudo-molecule from the pattern:

```scala
val f = b[Int, Unit]

// correct usage is case f(x, r) => ... r()

// this is incorrect usage because "r" is not being matched:
run { case f(x, _) => ... } // Error: blocking input molecules should not contain a pattern that matches on anything other than a simple variable
```

## Join definitions

Join definitions activate molecules and reactions:
Until a join definition is made, molecules cannot be injected, and no reactions will start.

Join definitions are made with the `join` method:

```scala
join(reaction1, reaction2, reaction3, ...)
```

A join definition can take any number of reactions.
With Scala's `:_*` syntax, a join definition can take a sequence of reactions computed at runtime.

All reactions listed in the join definition will be activated at once.

Whenever we inject any molecule that is used as input to one of these reactions, it is _this_ join definition (and no other) that will decide which reactions to run.
For this reason, we say that those molecules are "bound" to this join definition, or that they are "consumed" in it, or that they are "input molecules" in this join definition.

Here is an example of a join definition:

```scala
val c = new M[Int]("counter")
val d = new M[Unit]("decrement")
val i = new M[Unit]("increment")
val f = new M[Unit]("finished")

join(
  run { case c(x) + d(_) => c(x-1); if (x==1) f() },
  run { case c(x) + i(_) => c(x+1) }
)
```

In this join definition, the input molecules are `c`, `d`, and `i`, while the output molecules are `c` and `f`.
We say that the molecules `c`, `d`, and `i` are consumed in this join definition, or that they are bound to it.

Note that `f` is not an input molecule here; we will need to write another join definition to which `f` will be bound.

It is perfectly acceptable for a reaction to output a molecule such as `f` that is not consumed by any reaction in this join definition.
But if we forget to write any other join definition that consumes `f`, it will be a runtime error to inject `f`.
As a warning, note that in the present example, `f` will be injected only if `x==1` (and it is impossible to determine at compile time whether `x==1` will be true at runtime).
So, if we forget to write a join definition to which `f` is bound, it will be not necessarily easy to detect the error at runtime!

An important requirement for join definitions is that any given molecule must be bound to one and only one join definition.
It is a runtime error to use separate join definitions for reactions that consume the same molecule.
An example of this error would be writing the previous join definition as two separate ones:

```scala
val c = new M[Int]("counter")
val d = new M[Unit]("decrement")
val i = new M[Unit]("increment")
val f = new M[Unit]("finished")

join(
  run { case c(x) + d(_) => c(x-1); if (x==1) f() }
)

join(
  run { case c(x) + i(_) => c(x+1) }
) // runtime error: "c" is already bound to another join definition
```

# Thread pools

There are two kinds of tasks that `JoinRun` can perform concurrently:
- running some reactions
- injecting some new molecules and deciding which reactions will run next

Each join definition is a local value and is separate from all other join definitions.
So, in principle all join definitions can perform their tasks fully concurrently and independently from each other.

In practice, there are situations where we need to force certain reactions to run on certain threads.
For example, user interface (UI) programming frameworks typically allocate one thread for all UI-related operations, such as updating the screen and callbacks for interacting with the user.
The Android SDK as well as JavaFX both adopt this approach to thread management.
In these environments, it is a non-negotiable requirement to be able to control which threads are used by which tasks.
All screen updates as well as all user events must be scheduled on the single UI thread, while all long-running tasks must be delegated to specially created non-UI or "background" threads.

To facilitate this control, `JoinRun` implements the thread pool feature.

Each join definition uses two thread pools: a thread pool for running reactions (`reactionPool`) and a thread pool for injecting molecules and deciding new reactions (`joinPool`).

By default, these two thread pools are statically allocated and shared by all join definitions.

Users can create custom thread pools and specify, for any given join definition,
- on which thread pool the decisions will run
- on which thread pool each reaction will run

## Creating a custom thread pool

TODO

## Specifying thread pools for reactions

## Specifying thread pools for decisions

## Stopping a thread pool

TODO

## Blocking calls and thread pools

The `SmartPool` class is used to create thread pools for reactions that may generate a lot of blocking molecules.
This thread pool will automatically increment the pool size when a blocking molecule is injected, and decrement it when the blocking molecule receives a reply and unblocks the calling process.

This functionality is available with the `BlockingIdle` function.
Whenever a reaction contains an idle blocking call, the corresponding thread will be blocked while doing no computations.
If the thread pool does not increase the number of available threads in this case, it is possible that the blocking call is waiting for a molecule that is never going to be injected since no free threads are available to run reactions.
To prevent this kind of starvation, the user can surround the idle blocking calls with `BlockingIdle(...)`.

Injectors of blocking molecules already use `BlockingIdle` in their implementation.
The user needs to employ `BlockingIdle` explicitly only when a reaction contains blocking idle calls, such as `Thread.sleep`, synchronous HTTP calls, database queries, and so on.

Example:

```scala
... run { case a(url) + b(_) =>
      val result = BlockingIdle{ callSyncHttpApi(url) }
      c(result)
    }
```

Another case when `BlockingIdle` might be useful is when a reaction contains a complicated condition that will block the join decision thread.
In that case, `BlockingIdle` should be used, together with a `SmartPool` for join decisions.
 
Example:

```scala
val pool = new SmartPool(8)
  ...
join(pool, defaultReactionPool)(
  run { case a(url) if BlockingIdle{ callSyncHttpApi(url).isSuccessful } => ...}
)
```

## Fault tolerance and exceptions

A reaction body could throw an exception of two kinds:
- `ExceptionInJoinRun` due to incorrect usage of `JoinRun` - such as, failing to perform a reply action with a blocking molecule
- any other `Exception` in user's reaction code 

The first kind of exception leads to stopping the reaction and printing an error message.

The second kind of exception is handled specially for reactions marked as `withRetry`:
For these reactions, `JoinRun` assumes that the reaction has died due to some transient malfunction and should be retried.
Then the input molecules for the reaction are injected again.
This will make it possible for the reaction to restart.

By default, reactions are not marked as `withRetry`, and any exception thrown by the reaction body will lead to the 
input molecules being consumed and lost.

The following syntax is used to specify fault tolerance in reactions:

```scala
join(
  run { case a(x) + b(y) => ... }.withRetry, // will be retried
  run { case b(y) => ...} // will not be retried - this is the default
)
```

As a rule, the user cannot catch an exception thrown in a different thread.
Therefore, it may be advisable not to use exceptions within reactions.

# Limitations in the current version of `JoinRun`

TODO

# Version history

- 0.0.9 Macros for static analysis of reactions; unrestricted pattern-matching now available for molecule values.

- 0.0.8 Add a timeout option for blocking molecules. Add `CachedPool` option. Tutorial text and ScalaDocs are almost finished. Minor cleanups and simplifications in the API.

- 0.0.7 Refactor into proper library structure. Add tutorial text and start adding documentation. Minor cleanups. Add `Future`/molecule interface.

- 0.0.6 Initial release on Github. Basic functionality, unit tests.

# Roadmap for the future

These features are considered for implementation in the next versions:

1. Perform static analysis of reactions, and warn the user about certain livelock and deadlock situations.
1. Rework the decisions to start reactions so that the static analysis is used (inputs and outputs of reactions). In particular, do not lock the entire molecule bag - only lock some clusters that have contention on certain molecule inputs.
1. Implement fairness with respect to molecules (random choice of input molecules for reactions). 
1. Rework the decisions to start reactions so that many reactions can start at once.
1. Implement injecting several molecules at once (and arbitrarily many at once).
1. Implement nonlinear patterns for input molecules.

These features are further away from implementation because they require some research:

1. Investigate interoperability with streaming frameworks such as Scala Streams, Scalaz Streams, FS2, Akka streams.
1. Investigate an implicit distributed execution of thread pools.
