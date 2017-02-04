<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# `Chymyst Core` library documentation

`Chymyst Core` provides an embedded DSL for declarative concurrency in Scala.
It follows the **chemical machine** paradigm and provides high-level, purely functional concurrency primitives used by the `Chymyst` framework.

Currently, it compiles with Scala 2.11 and Scala 2.12 on Oracle JDK 8.

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
This is achieved with the `checkTimeout()` method of the reply emitter:

```scala
import scala.concurrent.duration.DurationInt
val a = m[Unit]
val b = m[Boolean]
val f = b[Int, String]

site ( go { case f(x, r) + a(_) => val status = r.checkTimeout(x); b(status) } )

val result: Option[String] = f.timeout(10)(100 millis)

```

In this example, the call `r.checkTimeout(x)` performs the same reply action as `r(x)`, and additionally a `Boolean` status value is returned.
The status value will be `true` only if the caller has actually received the reply and did not time out.

## Debugging

Molecule emitters have the method `setLogLevel`, which is by default set to 0.
Positive values will lead to more debugging output.

The log level will affect the entire reaction site to which the molecule is bound.

```scala
val x = m[Int]
x.setLogLevel(2)

```

The method `logSoup` returns a string that represents the molecules currently present in the reaction site to which the molecule is bound.

```scala
val x = m[Int]
site(...)
println(x.logSoup)

```

It is a run-time error to use `setLogLevel` or `logSoup` on molecules that are not yet bound to any reaction site.

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
This type can be arbitrary -- a simple type such as `Int`, a tuple, a case class, etc.

The values carried by input molecules in reactions can be pattern-matched using all the features of the `case` clause in Scala.
For example, reactions can match on a constant, destructure a case class, and use guard conditions.
A reaction will start only if all matchers succeed and the guard condition returns `true`.

Here is an example with pattern-matching on non-blocking molecules `c` and `d` that carry non-simple types:

```scala
val c = m[Option[Int]] // non-blocking molecule
val d = m[(Int, String, Boolean)] // non-blocking molecule

val reaction = go { case c(Some(x)) + d((y, "hello", true)) if x == y => c(Some(y)) }

```

### Pattern-matching of blocking molecules

The syntax for matching on blocking molecules requires a two-place matcher, such as `f(x,y)`, unlike the one-place matchers for non-blocking molecules such as `c(x)`.

```scala
val c = m[Option[Int]] // non-blocking molecule
val f = b[Int, String] // blocking molecule

val reaction2 = go { case c(Some(x)) + f(y, r) => r(x.toString) + c(Some(y)) }

val result = f(123) // emit f(123), get reply value of type String

```

In this reaction, the pattern-match on `f(y, r)` involves _two_ pattern variables:

- The pattern variable `y` is of type `Int` and matches the value carried by the emitted molecule `f(123)`
- The pattern variable `r` is of type `Int => String` and matches a **reply emitter** -- a function object that emits a reply aimed at the caller of `f(123)`.

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

Writing a reaction site (RS) will at once activate molecules and reactions.
Until an RS is written, molecules cannot be emitted, and no reactions will start.

Reaction sites are written with the `site` method:

```scala
site(reaction1, reaction2, reaction3, ...)

```

A reaction site can take any number of reactions.
With Scala's `:_*` syntax, an RS can also take a sequence of reactions.

All reactions listed in the RS will be activated at once.

Whenever we emit any molecule that is used as input to one of these reactions, it is _this_ RS (and no other) that will decide which reactions to run.
For this reason, we say that those molecules are **bound** to this RS, or that they are **consumed** at that RS, or that they are **input molecules** at this RS.
To build intuition, we can imagine that each molecule must travel to its reaction site in order to start a reaction.
When a reaction requires several input molecules, the molecule will wait at the reaction site for the arrival of other molecules.

Here is an example of an RS:

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

In this RS, the input molecules are `c`, `d`, and `i`, while the output molecules are `c` and `f`.
We say that the molecules `c`, `d`, and `i` are consumed at this RS, or that they are bound to it.

Note that `f` is not consumed at this RS; we will need to write another RS where `f` will be consumed.

It is perfectly acceptable for a reaction to emit a molecule such as `f` that is not consumed by any reaction in this RS.
However, if we forget to write any _other_ RS that consumes `f`, it will be a run-time error to emit `f`.

As a warning, note that in the present example the molecule `f` will be emitted only if `x == 1` (and it is impossible to determine at compile time whether `x == 1` will be true at run time).
So, if we forget to write an RS to which `f` is bound, it will be not necessarily easy to detect the error at run time!

An important requirement for reaction sites is that any given molecule must be bound to one and only one reaction site.
It is a run-time error to write reactions consuming the same molecule in different reaction sites.

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
) // run-time error: "c" is already bound to another RS

```

This rule enforces the immutability of chemical laws:
Once a reaction site is written, we have fixed the reactions that a given molecule could initiate (i.e. the reactions that consume this molecule).
It is impossible to add a new reaction that consumes a molecule if that molecule is already bound to another RS.

This feature of the chemical machine allows us to create a library of reactions and guarantee that user programs will not be able to modify the intended flow of reactions.

Also, because of this rule, different reaction sites do not contend on input molecules.
The decisions about which reactions to start are local to each RS.


# Debugging the flow of reactions

It is sometimes not easy to make sure that the reactions are correctly designed.
The library offers some debugging facilities:

- each molecule is named
- a macro is available to assign names automatically
- the user can set a log level on each reaction site
 
 Here are the typical results:

```scala
import io.chymyst.jc._

val counter = b[Int] // the name of this molecule is "counter"
val decr = b[Unit] // the name is "decr"
val get = b[Unit,Int] // the name is "get"

site (
  go { case counter(n) + decr(_) if n > 0 => counter(n - 1) },
  go { case counter(n) + get(_, res) => res(n) + counter(n) }
)

counter(5)

/* Let's start debugging... */
counter.setLogLevel(2)

/* Each molecule is automatically named: */
counter.toString // returns the string "counter"

decr() + decr() + decr()
/* This prints:
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting decr() on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules counter(5), decr()
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting decr() on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules decr()
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool io.chymyst.jc.ReactionPool@57efee08 while on thread pool io.chymyst.jc.SitePool@36ce2e5d with inputs decr(), counter(5)
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting decr() on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules decr() * 2
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool io.chymyst.jc.SitePool@36ce2e5d with thread id 547
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting counter(4) on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules counter(4), decr() * 2
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool io.chymyst.jc.ReactionPool@57efee08 while on thread pool io.chymyst.jc.SitePool@36ce2e5d with inputs decr(), counter(4)
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool io.chymyst.jc.SitePool@36ce2e5d with thread id 548
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting counter(3) on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules counter(3), decr()
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool io.chymyst.jc.ReactionPool@57efee08 while on thread pool io.chymyst.jc.SitePool@36ce2e5d with inputs decr(), counter(3)
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool io.chymyst.jc.SitePool@36ce2e5d with thread id 549
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting counter(2) on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules counter(2)

*/
println(counter.logSoup)
/* This prints:
 Site{counter + decr => ...; counter + get/S => ...}
 Molecules: counter(2)
 */
decr() + decr() + decr()
/* This prints:
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting decr() on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules counter(2), decr()
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool io.chymyst.jc.ReactionPool@57efee08 while on thread pool io.chymyst.jc.SitePool@36ce2e5d with inputs decr(), counter(2)
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting decr() on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules decr()
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting decr() on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules decr() * 2
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool io.chymyst.jc.SitePool@36ce2e5d with thread id 613
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting counter(1) on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules counter(1), decr() * 2
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + decr => ...} on thread pool io.chymyst.jc.ReactionPool@57efee08 while on thread pool io.chymyst.jc.SitePool@36ce2e5d with inputs decr(), counter(1)
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + decr => ...} started on thread pool io.chymyst.jc.SitePool@36ce2e5d with thread id 548
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting counter(0) on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules counter(0), decr()
*/
println(counter.logSoup)
/* This prints:
 Site{counter + decr => ...; counter + get/S => ...}
 Molecules: counter(0), decr()
 */

val x = get()
/* This results in x = 0 and prints:
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting get/S() on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules counter(0), decr(), get/S()
Debug: In Site{counter + decr => ...; counter + get/S => ...}: starting reaction {counter + get/S => ...} on thread pool io.chymyst.jc.ReactionPool@57efee08 while on thread pool io.chymyst.jc.SitePool@36ce2e5d with inputs counter(0), get/S()
Debug: In Site{counter + decr => ...; counter + get/S => ...}: reaction {counter + get/S => ...} started on thread pool io.chymyst.jc.SitePool@36ce2e5d with thread id 549
Debug: Site{counter + decr => ...; counter + get/S => ...} emitting counter(0) on thread pool io.chymyst.jc.SitePool@36ce2e5d, now have molecules counter(0), decr()
*/
```

# Thread pools

There are two kinds of tasks that `Chymyst Core` performs concurrently:

- running reactions
- emitting new molecules and deciding which reactions will run next

Each RS is a local value and is separate from all other RSs.
So, in principle all RSs can perform their tasks fully concurrently and independently from each other.

In practice, there are situations where we need to force certain reactions to run on certain threads.
For example, user interface (UI) programming frameworks typically allocate one thread for all UI-related operations, such as updating the screen or receiving callbacks from user interactions.
The Android SDK, as well as JavaFX, both adopt this approach to thread management for user interface programming.
In these environments, it is a non-negotiable requirement to have control over which threads are used by which tasks.
In particular, all screen updates (as well as all user event callbacks) must be scheduled on the single UI thread, while all long-running tasks must be delegated to specially created non-UI or "background" threads.

To facilitate this control, `Chymyst` implements the thread pool feature.

Each RS uses two thread pools: a thread pool for running reactions (`reactionPool`) and a thread pool for emitting molecules and deciding new reactions (`sitePool`).

By default, these two thread pools are statically allocated and shared by all RSs.

Users can create custom thread pools and specify, for any given RS,
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
This thread pool will automatically increment the pool size when a blocking molecule is emitted, and decrement it when the blocking molecule receives a reply and unblocks the calling process.

This functionality is available with the `BlockingIdle` function.
Whenever a reaction contains an idle blocking call, the corresponding thread will be blocked while doing no computations.
If the thread pool does not increase the number of available threads in this case, it is possible that the blocking call is waiting for a molecule that is never going to be emitted since no free threads are available to run reactions.
To prevent this kind of starvation, the user can surround the idle blocking calls with `BlockingIdle(...)`.

Emitters of blocking molecules already use `BlockingIdle` in their implementation.
The user needs to employ `BlockingIdle` explicitly only when a reaction contains blocking idle calls, such as `Thread.sleep`, synchronous HTTP calls, database queries, and so on.

Example:

```scala
val pool = new SmartPool(8)

... 

site(pool, defaultSitePool)(
  go { case a(url) + b(client) =>
      val result = BlockingIdle { client.callSyncHttpApi(url) }
      c(result)
    }
)
```

Another case when `BlockingIdle` might be useful is when a reaction contains a complicated condition that will block the RS decision thread.
In that case, `BlockingIdle` should be used, together with a `SmartPool` for join decisions.
 
Example:

```scala
val pool = new SmartPool(2)

...

site(defaultReactionPool, pool)(
  go { case a(url) + b(client) if BlockingIdle { client.callSyncHttpApi(url).isSuccessful } => ...}
)

```

## Fault tolerance and exceptions

A reaction body could throw an exception of two kinds:

- `ExceptionInChymyst` due to incorrect usage of `Chymyst Core` - such as, failing to perform a reply action with a blocking molecule
- any other `Exception` in user's reaction code 

The first kind of exception is generated by `Chymyst Core`  and leads to stopping the reaction and printing an error message.

The second kind of exception is assumed to be generated by the user and is handled specially for reactions marked `withRetry`.
For these reactions, `Chymyst Core` assumes that the reaction has died due to some transient malfunction and should be retried.
Then the input molecules for the reaction are emitted again.
This will make it possible for the reaction to restart.

By default, reactions are not marked `withRetry`, and any exception thrown by the reaction body will lead to the 
input molecules being consumed and lost.

The following syntax is used to specify fault tolerance in reactions:

```scala
site(
  go { case a(x) + b(y) => ... }.withRetry, // will be retried
  go { case c(z) => ...} // will not be retried - this is the default
)

```

As a rule, the user cannot catch an exception thrown in a different thread.
Therefore, it may be advisable not to use exceptions within reactions.
If there is an operation that could intermittently throw an exception, and if it is useful to retry the reaction in that case, the best way is mark the reaction `withRetry` and to make sure that all output molecules are emitted at the end of the reaction body, after any exceptions were thrown.
(Also, any other irreversible side effects should not happen before exceptions are thrown.)
In this case, the retry mechanism will be able to restart the reaction without repeating any of its side effects.

# Limitations in the current version of `Chymyst Core`

- when a thread pool's queue is full, new reactions cannot be run, - this situation is not processed well, exceptions are thrown and not handled

# Troubleshooting and known bugs

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

# Implementation notes

## Choice of molecules for reactions

At any given time, each reaction site (RS) must decide which reactions can start.
This decision depends only on the molecule values of molecules bound to this site, since we can decide whether to start a reaction when we know which input molecules are present and with what values.

Therefore, RS maintains a multiset of input molecules present at that site.
This multiset can be visualized as containing pairs `(molecule emitter, value)`.

Whenever a molecule is emitted, it goes into the multiset at the RS to which the molecule is bound.

At this time, an RS knows that some reaction might become possible that consumes this new molecule.
We can assume that all other molecules waiting at this RS are "inert" - they do not start any reactions, because if they did, we would have already started those reactions at a previous step when a previous molecule was emitted.

Therefore, at this step we can have at most one reaction that can start, and if so, this reaction will consume the new molecule.

Thus, we take the list of reactions that consume this molecule (this list is known at compile time), and go through this list, checking whether one of these reactions can find its required input molecules among the molecules present at the RS at this time.
 
This is a multiset matching problem: a reaction requires a multiset of input molecules with possibly some conditions on their values, and we have a multiset of available molecules with values.
We need to find the first reaction that can obtain all its input molecules.

If we find no such reactions, we are done with this step, since we have established that the current multiset of molecules is "inert".

If we find a reaction that can obtain all its input molecules, we atomically remove all these input molecules from the multiset, extract their values, and start the reaction.
