<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# `Chymyst Core` library documentation

`Chymyst Core` provides an embedded DSL for declarative concurrency in Scala.
It follows the **chemical machine** paradigm and provides high-level, purely functional concurrency primitives used by the `Chymyst` framework.

Currently, it is tested with Scala 2.11 and Scala 2.12 on Oracle JDK 8.

# Main structures

The main concepts of the chemical machine paradigm are molecule emitters, reactions, and reaction sites.

There are only two primitive operations:

- define reactions by writing a reaction site
- emit molecules by calling molecule emitters with argument values

## Molecule emitters

Molecule emitters are instances of one of the two classes:

- `M[T]` for non-blocking molecules carrying a value of type `T`
- `B[T, R]` for blocking molecules carrying a value of type `T` and returning a value of type `R`

Before molecules can be used in defining reactions, their molecule emitters must be defined as local values:

```scala
val x = new M[Int]("x") // define a non-blocking emitter with name "x" and integer value type
val y = new B[Unit, String]("y") // define a blocking emitter with name "y", with empty value type, and String return type

```

Each molecule carries a name, which can be obtained as `m.name`.
The name will be used when printing the molecule for debugging purposes.
Otherwise, names have no effect on run-time behavior.

The convenience macros `m` and `b` can be used to further reduce the boilerplate:

```scala
val counter = m[Int] // same as new M[Int]("counter")
val fetch = b[Unit, String] // same as new B[Unit, String]("fetch")

counter.name == "counter" // true
fetch.name == "fetch" // true

```

These macros will read the enclosing `val` definition at compile time and substitute the name of the variable as a string into the class constructor.

Note that the macros only work when emitters are created one by one, rather than destructured from a compound value.
The following code will not assign names correctly:

```scala
val (counter, fetch) = (m[Int], b[Unit, String])
counter.name == "x$1" // true
fetch.name == "x$1" // true

```



## Emitting molecules

Molecule emitters inherit from `Function1` and can be used as functions with one argument.
Calling these functions will perform the side-effect of emitting the molecule into the soup that pertains to the reaction site to which the molecule is bound.

```scala
... M[T] extends (T => Unit) ...
... B[T, R] extends (T => R) ...

val x = new M[Int]("x") // define emitter using class constructor

// Need to define some reactions with "x" - that code is omitted here.

x(123) // emit molecule with value 123

val y = m[Unit] // define emitter using macro

y() // emit molecule with unit value

val f = b[Int, String] // define emitter using macro

val result = f(10) // emitting a blocking molecule: "result" is of type String

```

It is a run-time error to emit molecules that is not yet bound to any reaction site.

### Timeout for a blocking molecule

The call to emit a blocking molecule will block until some reaction consumes that molecule and the molecule receives a "reply action" with a value.

A timeout can be imposed on that call by using the `timeout` method:

```scala
import scala.concurrent.duration.DurationInt

val f = b[Int, String]

val result: Option[String] = f.timeout(10)(100 millis)

```

Timed-out emission will result in an `Option` value.
The value will be `None` if timeout is reached before a reaction replies to the molecule.

### Detecting the time-out status

If a blocking molecule was emitted with a timeout, but no reaction has started within the timeout, the molecule will be removed from the soup after timeout.

It can also happen that a reaction started but the timeout was reached before the reaction performed the reply.
In that case, the reaction that replies to a blocking molecule can detect whether the reply was not received due to timeout.
This is achieved by checking the `Boolean` value returned by the reply emitter:

```scala
import scala.concurrent.duration.DurationInt
val a = m[Unit]
val b = m[Boolean]
val f = b[Int, String]

site ( go { case f(x, r) + a(_) => val status = r(x); b(status) } )

val result: Option[String] = f.timeout(10)(100 millis)

```

In this example, the `status` value will be `true` only if the caller has actually received the reply and did not time out.

If the caller has timed out before the reply was sent (or even before the replying reaction started), the `status` value will be `false`.

Reply emitters are instances of class `ReplyEmitter[T, R]` which extends `R => Boolean`.
For this reason, reply emitters look like functions that return a `Boolean` value.

### Logging the present molecules

The method `logSite` returns a `String` that represents the molecules currently present in the reaction site to which the molecule is bound.
This is intended as a debugging tool only.

```scala
val x = m[Int]
site(...)
println(x.logSite)

```

It is a run-time error to use `logSite` on molecules that are not yet bound to any reaction site.

## Reactions

A reaction is a value of type `Reaction`.
Reactions are declared using the `go` method with a partial function syntax that resembles pattern-matching on molecule values:

```scala
val reaction1: Reaction = go { case a(x) + b(y) => a(x + y) }

```

Molecule emitters appearing within the pattern match (between `case` and  `=>`) are the **input molecules** of that reaction.
Molecule emitters used in the reaction body (which is the Scala expression after `=>`) are the **output molecules** of that reaction.

All input and output molecule emitters involved in the reaction must be already defined and visible in the local scope.
(Otherwise, there will be a compile error.)

Note: Although molecules with `Unit` type can be emitted as `a()`, the pattern-matching syntax for those molecules must be `case a(_) + ... => ...` 

### Pattern-matching in reactions

Each molecule carries one value of a fixed type.
This type can be arbitrary — a simple type such as `Int`, a tuple, a case class, etc.

The values carried by input molecules in reactions can be pattern-matched using all the features of the `case` clause in Scala.
For example, reactions can match on a constant, destructure a case class, and use guard conditions.
A reaction will start only if all matchers succeed and the guard condition returns `true`.

Here is an example with pattern-matching on non-blocking molecules `c` and `d` that carry non-simple types:

```scala
val c = m[Option[Int]] // non-blocking molecule
val d = m[(Int, String, Boolean)] // non-blocking molecule

val reaction = go { case c(Some(x)) + d((y, "hello", true)) if x == y ⇒ c(Some(y)) }

```

Pattern-matching is a particular case of a guard condition that constrains the values of a reaction's input molecules.
A reaction can have guard conditions of three types:

1. Single-molecule guards
2. Cross-molecule guards
3. Static guards

The chemical machine will run a reaction only if it can find suitable input molecules such that all the guard conditions hold.

#### Single-molecule guards

**Single-molecule** guards are conditions that constrain the value carried by a single input molecule.
For example, the reaction

```scala
go { case a(x) + c(y) if x > 0 ⇒ ??? }

```

declares a guard condition that constrains the input molecule value `x`.
We call this condition a single-molecule guard for the input molecule `a(x)`.

Another example of a single-molecule guard is

```scala
go { case a(Some(x)) + c(y) ⇒ ??? }

```

The condition that the value of the molecule `a()` should be of the form `Some(x)` is a single-molecule guard
because it constrains only the value of one molecule.

#### Cross-molecule guards

**Cross-molecule** guards are conditions that constrain _several_ molecule values at once.
An example is

```scala
go { case a(x) + c(y) if x > y ⇒ ??? }

```

As a rule, the chemical machine will schedule such reactions more slowly because it must find a _pair_ of molecules `a(x)` and `c(y)` such that the condition holds.
If many copies of `a()` and `c()` are present in the soup, the search for a suitable pair can take a long time.

#### Static guards

**Static** guards are conditions that are independent of input molecule values.
For example,

```scala
go { case a(x) if someCond(n) > 0 ⇒ ??? }

```

declares a static guard `someCond(n) > 0` that depends on an externally defined function `someCond()` and an externally defined value `n`.
Neither `someCond` nor `n` are input molecule values; they must be defined in an outer scope. 

Note that the chemical machine may need to evaluate the static guard multiple times even if the reaction will not be started due to failure of other conditions.
For this reason, static guards must be pure functions without side effects. 

### Pattern-matching of blocking molecules

The syntax for matching on blocking molecules requires a two-place matcher, such as `f(x, r)`, unlike the one-place matchers for non-blocking molecules such as `c(x)`.

```scala
val c = m[Option[Int]] // non-blocking molecule
val f = b[Int, String] // blocking molecule

val reaction2 = go { case c(Some(x)) + f(y, r) => r(x.toString) + c(Some(y)) }

val result = f(123) // emit f(123), get reply value of type String

```

In this reaction, the pattern-match on `f(y, r)` involves _two_ pattern variables:

- The pattern variable `y` is of type `Int` and matches the value carried by the emitted molecule `f(123)`
- The pattern variable `r` is of type `Int => String` and matches a **reply emitter** — a function object that emits a reply aimed at the process that emits `f(123)`.

Calling `r` as `r(x.toString)` will perform the reply action, sending the value of `x.toString` back to the calling process, which has been blocked by emitting `f(123)`.
The reply action will unblock the calling process concurrently with continuing to evaluate the reaction body.

This reply action must be performed as `r(...)` in the reaction body exactly once, and cannot be performed afterwards.

It is a compile-time error to write a reaction that either does not perform the reply action or does it more than once.
The reply emitter `r` should not be used in any other way except to send a reply.
It should not be used by any other reactions or any other code outside the reaction body where `r` was defined.
It is an error to store `r` in a variable, to call another function with `r` as argument, and so on.

It is also a compile-time error to omit the reply matcher variable from the pattern:

```scala
val f = b[Int, Unit]

// correct usage is case f(x, r) => ... r()

// this is incorrect usage because "r" is not being matched:
go { case f(x, _) => ... } // Error: blocking input molecules should not contain a pattern that matches on anything other than a simple variable
go { case f(_) = ... } // Same error message

```

## Reaction sites

Creating a reaction site (RS) is the way to define some molecules and reactions jointly.

Reaction sites are created with the `site()` call:

```scala
site(reaction1, reaction2, reaction3, ...)

```

A reaction site can take any number of reactions.
With Scala's `:_*` syntax, an RS can also take a sequence of reactions.

Whenever we emit any molecule that is used as input to one of these reactions, it is _this_ RS (and no other) that will decide which reactions to run.
For this reason, we say that those molecules are **bound** to this RS, or that they are **consumed** at that RS, or that they are **input molecules** at this RS.
To build intuition, we can imagine that each molecule must travel to its reaction site in order to start a reaction.
When a reaction requires several input molecules, the molecule will wait at the reaction site for the arrival of other molecules.

Values of type `Reaction` are declarative descriptions of chemistry.
When a reaction site is created, it activates the given reactions and binds their input molecules.
Until then, these molecules cannot be emitted, and no reactions will start.

Here is an example of an RS where reactions are written inline as the arguments to the `site()` call:

```scala
val c = new M[Int]("counter")
val d = new M[Unit]("decrement")
val i = new M[Unit]("increment")
val f = new M[Unit]("finished")

site(
  go { case c(x) + d(_) => c(x - 1); if (x == 1) f() },
  go { case c(x) + i(_) => c(x + 1) }
)

```

In this RS, the input molecules are `c()`, `d()`, and `i()`, while the output molecules are `c()` and `f()`.
The molecules `c()`, `d()`, and `i()` are consumed at this RS, which is the same as to say that they are **bound** to it.

Note that `f()` is not consumed by any reactions at this RS.
Therefore, `f()` is not bound to this RS; we will need to create another RS where `f()` will be bound.

It is perfectly acceptable for a reaction to emit a molecule such as `f()` that is not consumed by any reaction in this RS.
However, if we forget to write any _other_ RS that consumes `f()`, it will be a run-time error to emit `f()`.

As a warning, note that in the present example the molecule `f()` will be emitted only if `x == 1` (and it is impossible to determine at compile time whether `x == 1` will be true at run time).
So, if we forget to write an RS to which `f()` is bound, it will be not necessarily easy to detect the error at run time!

An important requirement for reaction sites is that any given molecule must be bound to one and only one reaction site.
It is a run-time error to write reactions consuming the same molecule in different reaction sites.
(This error will occur before any reactions are run.)

An example of this error would be writing the previous RS as two separate ones:

```scala
val c = new M[Int]("counter")
val d = new M[Unit]("decrement")
val i = new M[Unit]("increment")
val f = new M[Unit]("finished")

site(
  go { case c(x) + d(_) => c(x - 1); if (x == 1) f() }
)

site(
  go { case c(x) + i(_) => c(x + 1) }
)
// throws java.lang.Exception:
// "Molecule c cannot be used as input in Site{c + i → ...} since it is already bound to Site{c + d → ...}"

```

This rule enforces the immutability of chemical laws:
Once a reaction site is created, we have fixed the reactions that a given molecule could initiate (i.e. the reactions that **consume** this molecule).
Once a molecule is bound to some RS, it is impossible to add a new reaction that consumes that molecule (either at the same RS, or by creating a new RS).

This feature of the chemical machine allows us to create a library of reactions with a guarantee that user programs will not be able to modify the intended flow of reactions.

Also, because of this rule, different reaction sites do not contend on input molecules.
In other words, the decisions about which reactions to start are local to each RS.

# Thread pools

There are two kinds of tasks that `Chymyst Core` performs concurrently:

- running reactions;
- emitting new molecules and deciding which reactions will run next.

Each RS is a local value and is separate from all other RSs.
So, if enough CPU cores are available, all RSs can perform their tasks fully concurrently and independently from each other.

In practice, there are situations where we need to force certain reactions to run on certain threads.
For example, user interface (UI) programming frameworks typically allocate one thread for all UI-related operations, such as updating the screen or receiving callbacks from user interactions.
The Android SDK, as well as JavaFX, both adopt this approach to thread management for user interface programming.
In these environments, it is a non-negotiable requirement to have control over which threads are used by which tasks.
In particular, all screen updates (as well as all user event callbacks) must be scheduled on the single UI thread, while all long-running tasks must be delegated to specially created non-UI or "background" threads.

To facilitate this control, `Chymyst Core` implements the thread pool feature.

Each RS uses a special thread pool (the `reactionPool`).
This thread pool contains two sets of threads:

1. A `ThreadPoolExecutor` for running reactions. This thread executor (called `workerExecutor` in `Pool.scala`) manages one or more worker threads.
2. A single, dedicated scheduler thread for deciding new reactions (called the `schedulerExecutor` in the code).

All reactions are run on the reaction pool's worker threads.
The code for choosing input molecules for new reactions and scheduling these reactions is run on the reaction pool's scheduler thread.

By default, the reaction sites use a statically allocated reaction pool that is shared by all RSs unless a new reaction pool is specified.
Users can also create new reaction pools and assign them to specific reaction sites.

The dedicated scheduler thread is also a part of a reaction pool, although it is separate and free of contention with the worker threads.
So, if several reaction sites share a reaction pool, they also share the (single) scheduler thread, which may lead to scheduler starvation if a reaction takes a long time to schedule.

Also note that the total number of threads in the JVM is limited to about 2,000.
Thus, creating more than about a thousand separate reaction pools is impossible in any case.

## Creating a new reaction pool

`Chymyst` provides two classes for reaction pools: `BlockingPool` and `FixedPool`.

A new thread pool is created like this:

```scala
val tp = BlockingPool(8)

```

This initializes a thread pool with 8 initial worker threads.

As a convenience, the method `cpuCores` can be used to determine the number of available CPU cores.
This value is used by `BlockingPool`'s default constructor.

```scala
val tp1 = BlockingPool() // same as BlockingPool(cpuCores)

```

A `FixedPool` pool holds a specified number of worker threads (in addition to a single, dedicated scheduler thread).

```scala
val tp1 = FixedPool(4) // 4 threads for reactions, one thread for scheduler

```

## Specifying thread pools for sites and reactions

The `site()` call can take an additional argument that specifies a thread pool for all reactions at this RS.

```scala
val tp = BlockingPool(8)

val a = m[Unit]
val c = m[Unit]
// etc.

site(tp)(
 go { case a(_) => ... },
 go { case c(_) => ... },
)

```

When it is desired that a particular reaction should be scheduled on a particular thread pool, the `onThreads()` method can be used:

```scala
val tp = BlockingPool(8)

val tp2 = BlockingPool(2)

val a = m[Unit]
val c = m[Unit]
// etc.

site(tp)(
 go { case a(_) => ... } onThreads tp2,
 go { case c(_) => ... }, // this reaction will run on `tp`
)

```

By default, all sites will use the `defaultPool`, unless another thread pool is specified in the `site(tp)(...)` call.

If the thread pool is specified for a particular RS, all reactions in that RS will use that thread pool, except for reactions with their own `onTreads()` specifications.

## Stopping a thread pool

Sometimes the programmer needs to stop the thread pool imediately, so that no more reactions can be run.

The method `shutdownNow()` will interrupt all threads in the thread pool and clear its reaction queue.
Calling this method on a thread pool will also prevent new reactions to be scheduled in the affected reaction sites.

```scala
val tp = BlockingPool(8)

site(tp)(...)

// Emit molecules
  ...
// All work needs to be stopped now.
tp.shutdownNow()

```

Thread pools also implement the `AutoCloseable` interface.
The `close()` method is an alias to `shutdownNow()`.

Thread pools will stop their threads when idle for a certain time `Pool.recycleThreadTimeMs()`.
So usually it is not necessary to shut down the pools manually.

Once a thread pool is shut down, it cannot be restarted.

## Blocking calls and the `BlockingPool`

The `BlockingPool` class is used to create thread pools for reactions that may emit a lot of blocking molecules, or perform other blocking calls that leave the thread idle (the **idle blocking** calls).
This thread pool will automatically increment the pool size while an idle blocking call is performed, and decrement the pool size when the blocking call is finished and the process is unblocked.

This functionality is available with the `BlockingIdle()` function, which is analogous to `scala.concurrent.blocking()` normally used with the Scala's default implicit execution context.

Whenever a reaction contains an idle blocking call, the corresponding thread will be blocked while doing no computations.
If the thread pool does not increase the number of available threads in this case, it is possible that the blocking call is waiting for a molecule that is never going to be emitted since no free threads are available to run reactions.
To prevent this kind of starvation and deadlock, the user should surround an idle blocking call with `BlockingIdle(...)`.

Emitters of blocking molecules already use `BlockingIdle` in their implementation.
The user needs to write `BlockingIdle` explicitly only when a reaction contains blocking idle calls, such as `Thread.sleep`, synchronous HTTP calls, database queries, and so on.

Example:

```scala
val pool = BlockingPool(8)

val a = m[Url]
val b = m[Client]
val c = m[Result] // whatever

site(pool)(
  go { case a(url) + b(client) ⇒
      val result = BlockingIdle { client.callSyncHttpApi(url) }
      c(result)
    },
  go { case c(result) ⇒ ... }
)
```

The reaction scheduler (i.e. the code that decides which reaction will start next) is running on a single dedicated thread (the `schedulerExecutor`).
User programs should avoid defining reactions with complicated conditions that could cause the RS scheduler to work very slowly or to be blocked.
For example, code like this should be avoided:

```scala
... // define molecule emitters

site(pool)(
// Guard condition executes a blocking HTTP call. Not recommended!
  go { case a(url) + b(client) if client.callSyncHttpApi(url).isSuccessful ⇒ ...}
)

```

Instead, the chemistry should be reorganized (e.g. as shown in the previous example) so that the blocking call is performed within a reaction body.

## Fault tolerance and exceptions

The fault tolerance facility is intended to help in situations when a reaction throws an exception due to a transient failure.

The following syntax is used to specify fault tolerance in reactions:

```scala
site(
  go { case a(x) + b(y) => ... }.withRetry, // will be retried
  go { case c(z) => ...} // will not be retried - this is the default
)

```

A reaction body could throw an exception of two kinds:

- `ExceptionInChymyst` due to incorrect usage of `Chymyst Core` - such as, failing to perform a reply action with a blocking molecule;
- any other `Exception` in the code of the reaction body.

The first kind of exception is generated by `Chymyst Core` and leads to stopping the reaction and printing an error message.

The second kind of exception is assumed to be generated by the user and is handled specially for reactions marked `withRetry`.
For these reactions, `Chymyst Core` assumes that the reaction has died due to some transient malfunction and could be retried.
Then the reaction is scheduled again if it is marked `withRetry`.

By default, reactions are not marked `withRetry`, and any exception thrown by the reaction body will stop the evaluation at the point of the exception.

As a rule, the user cannot catch an exception thrown in a different thread.
Therefore, it is advisable not to allow exceptions to be thrown within reactions.

If there is an operation that could intermittently throw an exception, and if it is useful to retry the reaction in that case, the best way is mark the reaction `withRetry` and to make sure that all output molecules are emitted at the end of the reaction body, after any exceptions were thrown.
(Also, any other irreversible side effects should not happen before exceptions are thrown.)
In this case, the retry mechanism will be able to restart the reaction without repeating any of its side effects.

# Debugging the flow of reactions

It is sometimes not easy to make sure that the reactions are correctly designed.
`Chymyst` offers some debugging facilities:

- all molecules, thread pools, and reaction threads are named, and macros will assign these names automatically
- the user can log the current contents of a reaction site by using `logSite()`
- the user can trace the operation of a reaction site by using event reporters
- the user can orchestrate asynchronous unit tests by waiting for specific events (molecule consumed, molecule emitted, reaction decided for molecule) 

## Printing the list of present molecules

Calling `c.logSite()` on a molecule emitter `c` will return a `String` with a description of all molecules present at the reaction site to which `c` is bound.

Note that reaction sites may contain molecules whose emitters are not visible to the user.
It is important to guarantee that the user does not have access to those emitters.
Since `logSite()` returns a `String`, it will not contain any emitter values (but may reveal the names of the emitters that the user has no access to).

Currently, `logSite()` is allowed only outside reactions; it will return an error string if used on a reaction thread.
User code should never depend on the output of `logSite()` for implementing the logic of a concurrent application.

## Event reporters

Each reaction pool contains an event reporter, which is an implementation of the `EventReporting` trait.

Events are broadly divided into several categories:

- Severe errors (e.g. when the application contains a programming error such as an unbound emitter) 
- Minor errors (currently, deadlocks in `FixedPool`s and thread overflows in `BlockingPool`s)
- Warnings while creating reaction sites, e.g. a possible livelock
- Reaction site events - creating a new reaction site, searching for reactions, scheduling new reactions
- Reaction events - starting, stopping a reaction
- Molecule events - emitting or consuming a molecule, sending a reply to a blocking molecule, timing out on a blocking wait

For each of the supported events, `Chymyst` generates a log message (currently, a `String`).
An event reporter can be configured to print these messages to the console, store them in a queue in memory, or publish them via another log transport.
The log transport configuration is represented by a function of type `String => Unit` that will be called to publish each log message.

In this way, users can create custom event reporters to, say, to create log entries in a database, or to choose to report certain events but not others.

The events generated by a given reaction site will be reported to the reaction pool of that reaction site.
If several reaction sites share a reaction pool, their events will be reported together.

### Using event reporters

To create and configure an event reporter, start by choosing a log transport.
`Chymyst` predefines two log transports: `ConsoleLogOutput` and `MemoryLogger`.
A custom log transport could be an instance of any class that extends `String => Unit`.

Then choose the reporter class for events that need to be logged.
`Chymyst` predefines several reporter classes such as `ErrorReporter`, `ErrorsAndWarningsReporter`, `DebugMoleculesReporter`, `DebugAllReporter` and so on.

The most verbose setting is the `DebugAllReporter`; the `EmptyReporter` is completely silent.

`Chymyst` also predefines console-printing reporters such as `ConsoleErrorReporter`, `ConsoleErrorsAndWarningsReporter`, and `ConsoleDebugAllReporter`:

```scala
object ConsoleErrorReporter extends ErrorReporter(ConsoleLogOutput)
object ConsoleErrorsAndWarningsReporter extends ErrorsAndWarningsReporter(ConsoleLogOutput)

```

Users can easily define other event reporters by mixing in the desired event reporting traits, for example like this:

```scala
object ConsoleReactionAndMoleculeDebugger extends EmptyReporter(ConsoleLogOutput)
   with DebugReactions
   with DebugMolecules
   with DebugBlockingMolecules

```

See the source code of `EventReporting.scala` for more details.

Finally, a reaction pool needs to be assigned an event reporter.
This can be done together with creating a reaction pool or later. 
By default, reaction pools are assigned the `ConsoleErrorReporter` that logs run-time errors to the console.

Here is sample code that defines the fully verbose console reporter and assigns it to a new reaction pool:

```scala
val tp = FixedPool(2).withReporter(ConsoleDebugAllReporter)

```

If we now create a reaction site using the pool `tp`, all events in that reaction site will be reported to the console.

Here is typical debug output from running some reactions with this reporter:

```scala
import io.chymyst.jc._

val counter = m[Int] // the name of this molecule is "counter"
val decr = m[Unit] // the name is "decr"
val get = b[Unit, Int] // the name is "get"

site(tp) (
  go { case counter(n) + decr(_) if n > 0 ⇒ counter(n - 1) },
  go { case counter(n) + get(_, res) ⇒ res(n); counter(n) }
)
// This prints:
// [1742522843584742] Debug: Created reaction site 1: Site{counter + decr → ...; counter + get/B → ...} at 1742522634128757 ns, took 209334151 ns

counter(5)
// This prints:
// [1742537166199398] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule counter(5), molecules present: [counter(5)]
// [1742537169586371] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule counter, molecules present: [counter(5)]
// [1742537175881229] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule counter, molecules present: [counter(5)]

/* Each molecule is automatically named: */
counter.toString // returns the string "counter"

decr() + decr() + decr()
/* This prints:
[1742620631122532] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule decr(), molecules present: [counter(5) + decr/P()]
[1742620632433889] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule decr, molecules present: [counter(5) + decr/P()]
[1742620633365719] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule decr(), molecules present: [counter(5) + decr/P() * 2]
[1742620639051113] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule decr(), molecules present: [counter(5) + decr/P() * 3]
[1742620647873588] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduled reaction {counter(n if ?) + decr(_) → } for molecule decr, inputs [counter(5) + decr/P()], remaining molecules [decr/P() * 2]
[1742620650275553] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule decr, molecules present: [decr/P() * 2]
[1742620650429874] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule decr, molecules present: [decr/P() * 2]
[1742620650549649] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule decr, molecules present: [decr/P() * 2]
[1742620651326091] Info: In Site{counter + decr → ...; counter + get/B → ...}: started reaction {counter(n if ?) + decr(_) → } with inputs [counter(5) + decr/P()] on thread FixedPool:tp,worker_thread:0
[1742620654421113] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule counter(4), molecules present: [counter(4) + decr/P() * 2]
[1742620654809781] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule counter, molecules present: [counter(4) + decr/P() * 2]
[1742620655009798] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduled reaction {counter(n if ?) + decr(_) → } for molecule counter, inputs [counter(4) + decr/P()], remaining molecules [decr/P()]
[1742620655586370] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule counter, molecules present: [decr/P()]
[1742620655596017] Info: In Site{counter + decr → ...; counter + get/B → ...}: started reaction {counter(n if ?) + decr(_) → } with inputs [counter(4) + decr/P()] on thread FixedPool:tp,worker_thread:1
[1742620655808072] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule counter(3), molecules present: [counter(3) + decr/P()]
[1742620656006161] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule counter, molecules present: [counter(3) + decr/P()]
[1742620656123784] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduled reaction {counter(n if ?) + decr(_) → } for molecule counter, inputs [counter(3) + decr/P()], remaining molecules []
[1742620656171688] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule counter, molecules present: []
[1742620656701691] Info: In Site{counter + decr → ...; counter + get/B → ...}: finished reaction {counter(n if ?) + decr(_) → } with inputs [counter(5) + decr/P()], status ReactionExitSuccess, took 4479038 ns
[1742620656762618] Info: In Site{counter + decr → ...; counter + get/B → ...}: started reaction {counter(n if ?) + decr(_) → } with inputs [counter(3) + decr/P()] on thread FixedPool:tp,worker_thread:0
[1742620656881947] Info: In Site{counter + decr → ...; counter + get/B → ...}: finished reaction {counter(n if ?) + decr(_) → } with inputs [counter(4) + decr/P()], status ReactionExitSuccess, took 193742 ns
[1742620656928384] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule counter(2), molecules present: [counter(2)]
[1742620656985952] Info: In Site{counter + decr → ...; counter + get/B → ...}: finished reaction {counter(n if ?) + decr(_) → } with inputs [counter(3) + decr/P()], status ReactionExitSuccess, took 185948 ns
[1742620657091402] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule counter, molecules present: [counter(2)]
[1742620657196664] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule counter, molecules present: [counter(2)]
*/
println(counter.logSite)
/* This prints:
 Site{counter + decr → ...; counter + get/S → ...}
 Molecules: counter(2)
 */
decr() + decr() + decr()
/* This prints:
[1742669441488900] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule decr(), molecules present: [counter(2) + decr/P()]
[1742669443751867] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule decr, molecules present: [counter(2) + decr/P()]
[1742669443903120] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduled reaction {counter(n if ?) + decr(_) → } for molecule decr, inputs [counter(2) + decr/P()], remaining molecules []
[1742669444843991] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule decr, molecules present: []
[1742669444952240] Info: In Site{counter + decr → ...; counter + get/B → ...}: started reaction {counter(n if ?) + decr(_) → } with inputs [counter(2) + decr/P()] on thread FixedPool:tp,worker_thread:2
[1742669445157222] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule counter(1), molecules present: [counter(1)]
[1742669445209517] Info: In Site{counter + decr → ...; counter + get/B → ...}: finished reaction {counter(n if ?) + decr(_) → } with inputs [counter(2) + decr/P()], status ReactionExitSuccess, took 216108 ns
[1742669445331921] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule counter, molecules present: [counter(1)]
[1742669445426155] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule counter, molecules present: [counter(1)]
[1742669447845348] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule decr(), molecules present: [counter(1) + decr/P()]
[1742669448038499] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule decr(), molecules present: [counter(1) + decr/P() * 2]
[1742669448064598] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule decr, molecules present: [counter(1) + decr/P() * 2]
[1742669448261435] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduled reaction {counter(n if ?) + decr(_) → } for molecule decr, inputs [counter(1) + decr/P()], remaining molecules [decr/P()]
[1742669448698914] Info: In Site{counter + decr → ...; counter + get/B → ...}: started reaction {counter(n if ?) + decr(_) → } with inputs [counter(1) + decr/P()] on thread FixedPool:tp,worker_thread:3
[1742669448702588] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule decr, molecules present: [decr/P()]
[1742669448875080] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule counter(0), molecules present: [counter(0) + decr/P()]
[1742669448937413] Info: In Site{counter + decr → ...; counter + get/B → ...}: finished reaction {counter(n if ?) + decr(_) → } with inputs [counter(1) + decr/P()], status ReactionExitSuccess, took 181723 ns
[1742669449027057] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule counter, molecules present: [counter(0) + decr/P()]
[1742669449173158] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule counter, molecules present: [counter(0) + decr/P()]
*/
println(counter.logSite)
/* This prints:
 Site{counter + decr → ...; counter + get/S → ...}
 Molecules: counter(0) + decr/P()
 */

val x = get()
/* This results in setting x = 0 and also prints:
[1742722592701505] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule get/B(), molecules present: [counter(0) + decr/P() + get/BP()]
[1742722594017965] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule get/B, molecules present: [counter(0) + decr/P() + get/BP()]
[1742722594581047] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduled reaction {counter(n) + get/B(_) → } for molecule get/B, inputs [counter(0) + get/BP()], remaining molecules [decr/P()]
[1742722595057089] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule get/B, molecules present: [decr/P()]
[1742722595276930] Info: In Site{counter + decr → ...; counter + get/B → ...}: started reaction {counter(n) + get/B(_) → } with inputs [counter(0) + get/BP()] on thread FixedPool:tp,worker_thread:4
[1742722595846304] Debug: In Site{counter + decr → ...; counter + get/B → ...}: emitted molecule counter(0), molecules present: [counter(0) + decr/P()]
[1742722595934923] Info: In Site{counter + decr → ...; counter + get/B → ...}: finished reaction {counter(n) + get/B(_) → } with inputs [counter(0) + get/BP()], status ReactionExitSuccess, took 599784 ns
[1742722596425786] Debug: In Site{counter + decr → ...; counter + get/B → ...}: scheduler looks for reactions for molecule counter, molecules present: [counter(0) + decr/P()]
[1742722596631627] Debug: In Site{counter + decr → ...; counter + get/B → ...}: no more reactions scheduled for molecule counter, molecules present: [counter(0) + decr/P()]
*/

```

A reaction pool will recycle the threads on which reactions were started.
However, after a certain period of inactivity, threads are freed.
For this reason, the 2-thread `FixedPool` reused its two threads `worker_thread:0` and `worker_thread:1` while running the first three reactions, but created new threads `worker_thread:2` and `worker_thread:3` after a period of inactivity.

### Using `MemoryLogger`

A `MemoryLogger` will accumulate all messages in a memory-based queue and print nothing to the console.
At any time, the accumulated messages are available as a `messages()` iterable.

Here is some example code using a `MemoryLogger` to configure an event reporter:

```scala
val memLog = new MemoryLogger
val reporter = new DebugReactionsReporter(memLog)
val tp = FixedPool(2).withReporter(reporter)

```

Once the event reporter has gathered some messages, the `MemoryLogger` instance can be queried for messages or cleared:

```scala
memLog.messages.foreach(println)
memLog.clearLog()

```

### Reassigning an event reporter

An event reporter can be reassigned at any time:

```scala
tp.reporter = ConsoleDebugAllReporter
...
tp.reporter = ConsoleErrorsAndWarningsReporter
...

```

A call to the method `withReporter()` produces a _new_ thread pool instance, which, however, uses the same threads as the old thread pool.
In this way, reaction sites can be configured to share threads but to use different event reporters:

```scala
val tp = FixedPool(2)
val tp1 = tp.withReporter(...) // same threads, different reporter

site(tp)(...)

site(tp1)(...)

```

## Unit testing and property checking

`Chymyst` includes a basic API intended for unit testing.
With this API, users can orchestrate a sequence of asynchronous events, wait until certain molecules are emitted or consumed, and verify that reactions proceed as required.
Since this is done without modifying any reaction code, users can write unit tests for reactions and reaction sites that are encapsulated in inaccessible local scopes.

The unit testing API provides hooks that return `Future[]` values for certain events occurring for a specific molecule.
Three types of events can be monitored:

- Molecule `c` was emitted with value `v`.
- The reaction site has searched for reactions that would consume molecule `c` and either scheduled such a reaction or found no suitable reactions.
- Molecule `c` with value `v` was consumed by a newly started reaction.

### `whenEmitted()`

To monitor emission events, use `c.whenEmitted`.
This returns a `Future[T]` that resolves to a value of type `T` when a molecule `c` is emitted next.

### `whenScheduled()`

To monitor the reaction site's scheduler events, use `c.whenScheduled`.
This returns a `Future[String]` that resolves when the reaction site's scheduler has finished its current round of searching for new reactions.

To use `c.whenScheduled` effectively, it is necessary to know a few details about `Chymyst`'s reaction scheduling strategy.

Each reaction site runs the reaction scheduler on the dedicated "scheduler" thread.
While no new molecules are emitted to the reaction site, this thread is dormant.
Whenever a new molecule is emitted, the reaction scheduler wakes up and starts a "search round", looking for reactions that might consume the newly emitted molecule.
We say that the search round is "driven by" a particular newly emitted molecule.

The `Future` returned by `c.whenScheduled` will complete when the next search round is finished.
If the search was successful, the future will resolve successfully to the name of the molecule that was driving the search round. 
If the search failed, the future will complete with a failure.

The failure message emphasizes that this failure is not necessarily a sign of an error.
Keep in mind that the scheduling process is non-deterministic and depends on the presence of other molecules and the timings of other concurrent reactions.
Depending on the chemistry of the application, it may not be possible to schedule new reactions immediately after some new molecules are emitted.

```
java.lang.Exception: c.whenScheduled() failed because no reaction could be scheduled (this is not an error)
```

When using `c.whenScheduled`, it is important to exclude the possibility that the returned `Future` never completes.
This will happen if the reaction scheduler finished scheduling the reaction too quickly, before the `c.whenScheduled` call is completed.

It is safe to call `c.whenScheduled` _before_ emitting a new copy of the `c()` molecule.
In that case, it is guaranteed that the scheduler will perform another search round for this molecule, and so will resolve the future in one way or another. 

### `emitUntilConsumed()`

The method `c.emitUntilConsumed(v)` emits a new copy of the molecule `c(v)` and returns a `Future[T]` that resolves when some reaction consumes _the emitted copy_ of the molecule.

It is important to provide for the possibility that the returned `Future` never completes.
This will happen if some reaction starts immediately and consumes another copy of `c()`, while our copy is still present in the soup and waits for new reactions.

### Example

Consider the “asynchronous counter” chemistry encapsulated in a function,

```scala
def makeCounter(initValue: Int, tp: Pool): (M[Unit], B[Unit, Int]) = {
  val c = m[Int]
  val d = m[Unit]
  val f = b[Unit, Int]

  site(tp)(
    go { case c(x) + f(_, r) ⇒ c(x) + r(x) },
    go { case c(x) + d(_) ⇒ c(x - 1) },
    go { case _ ⇒ c(initValue) }
  )
  
  (d, f)
}

```

The current value of `c()` can be queried using `f()`. 
We would like to verify that when we emit `d()`, the value of `c()` is decremented.
However, `d()` is a non-blocking molecule, and so `c()` will be decremented asynchronously, at some future time when `d()` is consumed.
We need to emit `d()`, wait until _that_ copy of `d()` is consumed, and only then query the value of `c()`.

We can orchestrate this using the testing hook `emitUntilConsumed()`:

```scala
val (d, f) = makeCounter(10, FixedPool(2))

val x = f() // get the current value
val fut = d.emitUntilConsumed()
// give a timeout just to be safe; actually, this will be quick
Await.result(fut, 5.seconds)
f() shouldEqual x - 1

```

# Troubleshooting and known issues

## Using single variables to match a tuple

If a molecule carries a tuple but the reaction input pattern matches that tuple with a wildcard or a single variable, the compiler will print a warning.

For example,

```scala
type Result = (Int, Int, Long, Boolean)
val a = m[Result]

site( go { case a(x) => ??? } )

```

This code will compile and work correctly but produce a warning such as:

```
Warning:(137, 43) class M expects 4 patterns to hold (Int, Int, Long, Boolean) but crushing into 4-tuple to fit single pattern (SI-6675)
      go { case a(x) => ??? }

```

## Using parameterized types in molecule values

If a molecule carries a `List[Int]` and the reaction input pattern matches the entire list, the compiler will print a warning.

For example,

```scala
val res = m[List[Int]]

site( go { case res(list) => ??? } )

```

This code will compile and work correctly but produce a warning such as:

```
Warning:(29, 10) non-variable type argument Int in type pattern List[Int] (the underlying of List[Int]) is unchecked since it is eliminated by erasure
      go { case res(list) => ??? }

```


## scalac: Error: Could not find proxy for value `x`

This compile-time error is generated after macro expansion. The message is similar to this:
 
```
scalac: Error: Could not find proxy for val x2: Tuple2 in List(value x2, method applyOrElse, ...)"

```

With `Chymyst Core` 0.1.5, this error occurs with certain complicated pattern matching constructions involving tuples.
A typical example is a tuple with a pattern variable that scopes over a compound value inside the tuple:

```scala
val d = m[(Int, Option[Int])]
go { case d((x, z@Some(_))) => }

```

In this example, the pattern variable `z` scopes over a sub-pattern `Some(_)` that contains a compound value.

As a workaround to make the error disappear, remove `z@` from the pattern, or match on `z@_` or just on `z` but add a guard condition, for example like this:

```scala
val d = m[(Int, Option[Int])]
go { case d((x, z)) if z.nonEmpty => }

```

This code compiles and works, and is equivalent to the more complicated pattern match.

## Type mismatch "found `Any`, required `X`" with pattern variables

When pattern variables have type parameters, these type parameters are replaced by `Any` due to type erasure.
The current implementation of guard conditions in `Chymyst` is unable to recognize the type parameters that pattern variables actually have.
This limitation does not affect the reaction body; however, guard conditions involving these pattern variables will suffer from incorrect type parameter `Any`.

An (artificial) example is shown in this reaction:

```scala
val a = m[Option[Int]]

go { case a(p@Some(_)) if 1 - p.get > 0 => ??? }

```

The pattern variable `p` has type `Some[Int]`, which has a type parameter set to `Int`.
The reaction body is able to evaluate expressions such as `1 - p.get` with no problems, with `p.get` typed as `Int`.
However, the guard condition as implemented by `Chymyst` suffers from type erasure,
and so the type of `p` within the guard expression becomes `Some[Any]`.
Therefore, `p.get` is typed as `Any`, and the presence of `1 - p.get` in the guard condition
will cause a compile-time error "type mismatch: found `Any`, required `Int`".

Several workarounds are possible:
 
- Avoid using pattern variables whose type is parameterized; in this example, write `a(Some(x))` instead of `a(p@Some(_))`.
- Rewrite some expressions so that compilation succeeds; in this example, `p.get < 1` would compile without errors, despite the type `Any`.

# Implementation notes

## Choice of molecules for reactions

At any given time, each reaction site (RS) must decide which reactions can start.
This decision depends only on the molecule values of molecules bound to this site, since we can decide whether to start a reaction when we know which input molecules are present and with what values.

Therefore, each RS maintains a multiset of input molecules present at that site.
The elements of this multiset can be visualized as pairs `(molecule emitter, value)`.

Whenever a molecule is emitted, it is added to the multiset of the RS to which the molecule is bound.

At the same time, a new "reaction search round" is scheduled on the RS's scheduler thread.
The RS starts a new search round because now, possibly, some new reaction should be started, consuming this new molecule.

This molecule is said to "drive" the new search round.
All other molecules waiting at this RS are "inert": they cannot start any reactions by themselves, because if they could we would have already started those reactions at a previous round when a previous molecule was emitted.

Therefore, at this round we can have at most one new reaction that can start, and if so, this reaction will consume the driving molecule.

Thus, we take the list of reactions that consume this molecule (this list is known at compile time), and go through this list, checking whether one of these reactions can find its required input molecules among the molecules present at the RS at this time.
 
This is a multiset matching problem: a reaction requires a multiset of input molecules with possibly some conditions on their values, and we have a multiset of available molecules with values.
We need to find the first reaction that can obtain all its input molecules from the multiset.

If we find no such reactions, we are done with this round, since we have established that the current multiset of molecules is "inert".

If we find a reaction that can obtain all its input molecules, we atomically remove all these input molecules from the multiset, extract their values, and start the reaction.

While we were completing the reaction search, more copies of the driving molecule could have been emitted.
Therefore, we repeat the round with the same driving molecule, until no more reactions can be scheduled.
