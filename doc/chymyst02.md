# Blocking vs. non-blocking molecules

So far, we have used molecules whose injection was a non-blocking call:
Injecting a molecule, such as `b(123)`, immediately returns `Unit` but performs a concurrent side effect, which is to add a new molecule to the soup.
Such molecules are called “non-blocking”.

An important feature of the chemical machine is the ability to define “blocking” (or “synchronous”) molecules.

The runtime engine simulates the injecting of a blocking molecule in a special way.
The injection call will be blocked until some reaction can start with the newly injected molecule.
This reaction's body will be able to send a “reply value” back to the injecting process (which can be another reaction body or any other process).
Once the reply value has been sent, the injecting process is unblocked.

Here is an example of declaring a blocking molecule:

```scala
val f = b[Unit, Int]
```

The molecule `f` carries a value of type `Unit`; the reply value is of type `Int`.
(These types can be arbitrary, just as for non-blocking molecules.)

Sending a reply value is a special feature available only with blocking molecules.
We call this feature the **reply action**.
The reply action is a non-blocking operation.

Here is an example showing the syntax for the reply action.
Suppose we have a reaction that consumes a non-blocking molecule `c` with an integer value and a blocking molecule `f` defined above.
We would like the blocking molecule to return the integer value that `c` carries.

```scala
val f = b[Unit, Int]
val c = m[Int]

join( run { case c(n) + f(_ , reply) => reply(n) } )

c(123) // inject a copy of `c`

val x = f() // now x = 123
```

The syntax for the reply action makes it appear as if the molecule `f` carries _two_ values - its `Unit` value and a special `reply` function, and that the reaction body calls this `reply` function with an integer value.
However, `f` is injected with the syntax `f()` -- just as any other molecule with `Unit` value.
The `reply` function appears only in the pattern-matching expression for `f` inside a reaction.

Blocking molecule injectors are values of type `B[T,R]`, while non-blocking molecule injectors have type `M[T]`.
Here `T` is the type of value that the molecule carries, and `R` (for blocking molecules) is the type of the reply value.

The pattern-matching expression for a blocking molecule of type `B[T,R]` has the form `case ... + f(v, r) + ...` where `v` is of type `T` and `r` is of type `Function1[R]`.

## Example: Benchmarking the concurrent counter

To illustrate the usage of non-blocking and blocking molecules, let us consider the task of benchmarking the concurrent counter we have previously defined.
The plan is to initialize the counter to a large value _N_, then to inject _N_ decrement molecules, and finally wait until the counter reaches the value 0.
We will use a blocking molecule to wait until this happens and thus to determine the time elapsed during the countdown.

Let us now extend the previous join definition to implement this new functionality.
The simplest solution is to define a blocking molecule `fetch`, which will react with the counter molecule only when the counter reaches zero.
Since we don't need to pass any data (just the fact that the counting is over), the `fetch` molecule will carry `Unit` and also bring back a `Unit` reply.
This reaction can be written in pseudocode like this:
```
fetch() + counter(n) if n==0 => reply () to fetch
```

We can implement this reaction by using a guard in the `case` clause:

```scala
run { case fetch(_, reply) + counter(n) if n == 0  => reply() }
```

For more clarity, we can also use the pattern-matching facility of `JoinRun` to implement the same reaction like this:

```scala
run { case counter(0) + fetch(_, reply)  => reply() }
```

Here is the complete code:

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

import java.time.LocalDateTime.now
import java.time.temporal.ChronoUnit.MILLIS

object C extends App {

  // declare molecule types
  val fetch = b[Unit, Unit]
  val counter = m[Int]
  val decr = m[Unit]

  // declare reactions
  join(
    run { case counter(0) + fetch(_, reply)  => reply() },
    run { case counter(n) + decr(_) => counter(n-1) }
  )

  // inject molecules

  val n = 10000
  val initTime = now
  counter(n)
  (1 to n).foreach( _ => decr() )
  fetch()
  val elapsed = initTime.until(now, MILLIS)
  println(s"Elapsed: $elapsed ms")
}
```

Some remarks:
- Molecules with unit values still require a pattern variable when used in the `case` construction.
For this reason, we write `decr(_)` and `fetch(_, reply)` in the match patterns.
However, these molecules can be injected simply by calling `decr()` and `fetch()`, since Scala inserts a `Unit` value automatically when calling functions.
- We declared both reactions in one join definition, because these two reactions share the input molecule `counter`.
- The injected blocking molecule `fetch()` will not remain in the soup after the reaction is finished.
Actually, it would not make sense for `fetch()` to remain in the soup:
If a molecule remains in the soup after a reaction, it means that the molecule is going to be available for some later reaction without blocking its injecting call; but this is the behavior of a non-blocking molecule.
- Blocking molecules are like functions except that they will block until their reactions are not available.
If the relevant reaction never starts, a blocking molecule will block forever.
The runtime engine cannot detect this situation because it cannot determine whether the relevant input molecules for that reaction might become available in the future.
- If several reactions are available for the blocking molecule, one of these reactions will be selected at random.
- Blocking molecules are printed with the suffix `"/B"`.

## Example: Wait for the first reply

Suppose we have two blocking molecules `f` and `g` that return a reply value of type `T`.
We would like to inject both `f` and `g` together and wait until a reply value is received, from whichever molecule unblocks sooner.
If the other molecule gets a reply later, we will just ignore that.

The result of this nondeterministic operation is the value of type `T` obtained from one of the molecules `f` and `g`, depending on which molecule got its reply first.

Let us now implement this operation in `JoinRun`.
We will derive the required chemistry by reasoning about the behavior of molecules.

We need to define a blocking molecule injector `firstReply` that will unblock when `f` or `g` unblocks, whichever is earliest.

It is clear that we need to inject both `f()` and `g()` at the same time.
But we cannot do this from one reaction, since `f()` will block and prevent us from injecting `g()`.
Therefore we need two different reactions, one injecting `f()` and another injecting `g()`.

These two reactions need some input molecules.
These input molecules cannot be `f` and `g` since these two molecules are given to us, and we cannot add reactions that consume them.
Therefore, we need at least one new molecule that will be consumed to start these two reactions.
However, if we declare the two reactions as `c() => f()` and `c() => g()` and inject two copies of `c()`, we are not guaranteed that both reactions will start.
It is possible that two copies of the first reaction (or two copies of the second reaction) are started instead.
In other words, there will be an unavoidable indeterminism in our chemistry.
`JoinRun` will detect this and refuse the join definition:

```scala
val c = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
join(
    run { case c(_) => val x = f(); ... },
    run { case c(_) => val x = g(); ... }
)
java.lang.Exception: In Join{c => ...; c => ...}: Unavoidable indeterminism: reaction c => ... is shadowed by c => ...
```

So, we need to define two _different_ molecules (say, `c` and `d`) as inputs for these two reactions.

```scala
val c = m[Unit]
val d = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
join(
    run { case c(_) => val x = f() },
    run { case d(_) => val x = g() }
)
c() + d()
```

Since we have injected both `c()` and `d()`, both reactions can now proceed concurrently.
When one of them finishes and gets a reply value `x`, we would like to use that value for replying to our new blocking molecule `firstResult`.

Can we reply to `firstResult` in the same reactions? This would require us to make `firstResult` an input molecule in each of these reactions.
However, we will have only one copy of `firstResult` injected.
Having `firstResult` as input will therefore prevent both reactions from proceeding.

Then there must be some other reaction that consumes `firstResult` and replies to it.
This reaction must have the form `firstResult(_, reply) + ... => reply(x)`.
It is clear that we need to have access to the value `x` that we will reply with.
Therefore, we need a new auxiliary molecule, say `done(x)`, that will carry `x` on itself.
The reaction with `firstResult` will then have the form `firstResult(_, reply) + done(x) => reply(x)`.

Now it is clear that the value `x` should be injected on `done(x)` in both of the reactions.
The complete program looks like this:

```scala
val c = m[Unit]
val d = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
val done = m[Int]

join(
  run { case c(_) => val x = f(); done(x) },
  run { case d(_) => val x = g(); done(x) }
)

val firstResult = b[Unit, Int]

join(
  run { case firstResult(_, r) + done(x) => r(x) }
)

c() + d()
val result = firstResult()
```

### Refactoring into a library

The code as written works but is not encapsulated - we are defining new molecules and new chemistry inline.
There are two ways we could encapsulate this chemistry:

- create a function that will return the `firstResult` injector, given the injectors `f` and `g`
- create a “universal first result molecule” that carries `f` and `g` as its value and performs the required chemistry

TODO: complete

## Example: Parallel Or

We will now consider a task called “Parallel Or”; this is somewhat similar to the “first result” task considered above.

The task is to create a new blocking molecule `parallelOr` from two given blocking molecules `f` and `g` that return a reply value of `Boolean` type.
We would like to inject both `f` and `g` together and wait until a reply value is received.
The `parallelOr` will reply with `true` as soon as one of the blocking molecules `f` and `g` returns `true`.
If both `f` and `g` return `false` then `parallelOr` will also return `false`.
If both molecules `f` and `g` block (or one of them returns `false` while the other blocks) then `parallelOr` will block as well.

We will now implement this operation in `JoinRun`.

TODO: complete

## Error checking for blocking molecules

It is an error if a reaction consumes a blocking molecule but does not reply.
It is also an error to reply again after a reply was made.

Sometimes, these errors can be caught at compile time:

```scala
val f = b[Unit, Int]
val c = m[Int]
join( run { case f(_,reply) + c(n) => c(n+1) } ) // forgot to reply!
// compile-time error: "blocking input molecules should receive a reply but no reply found"

join( run { case f(_,reply) + c(n) => c(n+1) + r(n) + r(n) } ) // replied twice!
// compile-time error: "blocking input molecules should receive one reply but multiple replies found"
```

However, a reaction could depend on a run-time condition, which is impossible to evaluate at compile time.
In this case, a reaction that does not reply will generate a run-time error:

```scala
val f = b[Unit, Int]
val c = m[Int]
join( run { case f(_,reply) + c(n) => c(n+1); if (n==0) reply(n) } )

c(1)
f()
java.lang.Exception: Error: In Join{c + f/B => ...}: Reaction {c + f/B => ...} finished without replying to f/B
```

Note that this error will occur only when reactions actually start and the run-time condition is evaluated to `false`.
In order to make reasoning about reactions easier, it is advisable to reorganize the chemistry such that reply actions (and, more generally, output molecule injections) are unconditional.

