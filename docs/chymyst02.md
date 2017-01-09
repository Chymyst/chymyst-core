<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Blocking vs. non-blocking molecules

So far, we have used molecules whose emission was a non-blocking call.
Emitting a molecule, such as `a(123)`, immediately returns `Unit` but performs a concurrent side effect, adding a new molecule to the soup.
Such molecules are called **non-blocking**.

An important feature of the chemical machine is the ability to define **blocking molecules**.
Just like non-blocking molecules, a blocking molecule can be emitted into the soup with a value.
However, there are two major differences:

- emitting a blocking molecule is a function call that returns a **reply value**, which is supplied by some reaction that consumed that blocking molecule;
- emitting a blocking molecule will block the emitting process, until some reaction consumes the blocking molecule and sends the reply value.

Here is an example of declaring a blocking molecule:

```scala
val f = b[Unit, Int]

```

The blocking molecule `f` carries a value of type `Unit`; the reply value is of type `Int`.
(These types can be arbitrary, just as for non-blocking molecules.)

The runtime engine treats an emission of a blocking molecule in a special way:

1. The emission call such as `f()` will insert a new copy of the `f()` molecule into the soup.
2. The emitting process, which can be in another reaction body or any other code, will be blocked until some reaction consumes the newly emitted copy of `f()`.
3. Once such a reaction starts, this reaction's body can (and should) **reply to the blocking molecule**. Replying is a special operation that sends a **reply value** to the emitting process.
4. The emitting process becomes unblocked after it receives the reply value. To the emitting process, the reply value appears to be the value returned by the function call `f()`.

Sending a reply value is a special feature available only within reactions that consume blocking molecules.
We call this feature the **reply action**.

Here is an example showing the `JoinRun` syntax for the reply action.
Suppose we have a reaction that consumes a non-blocking molecule `c` with an integer value and the blocking molecule `f` defined above.
We would like the blocking molecule to return the integer value that `c` carries:

```scala
val f = b[Unit, Int]
val c = m[Int]

site( go { case c(n) + f(_ , reply) => reply(n) } )

c(123) // emit an instance of `c` with value 123

val x = f() // now x = 123

```

Let us walk through the execution of this example step by step.

After defining the chemical law `c + f => ...`, we first emit an instance of `c(123)`.
By itself, this does not start any reactions since the chemical law states `c + f => ...`, and we don't yet have any instances of `f` in the soup.
So `c(123)` will remain in the soup for now.

Next, we call the blocking emitter `f()`, which emits an instance of `f()` into the soup.
Now the soup has both `c` and `f`, while the calling process is blocked until a reaction involving `f` can start.

We have such a reaction: this is `c + f => ...`. This reaction is ready to start since both its inputs, `c` and `f`, are now present.
Nevertheless, the start of the reaction is concurrent with the process that calls `f`, and may occur somewhat later than the call to `f()`, depending on the CPU load.
So, the call to `f()` will be blocked for some (hopefully short) time.

Once the reaction starts, it will receive the value `n = 123` from the input molecule `c(123)`.
Both `c(123)` and `f()` will be consumed by the reaction.
The reaction will then perform the reply action `reply(n)` with `n = 123`.
Only at this point the calling process will get unblocked and receive `123` as the return value of the function call `f()`.

From the point of view of the reaction that consumea a blocking molecule, the reply action is a non-blocking (i.e. a very fast) function call.
The reaction will continue evaluating its reaction body, _concurrently_ with the newly unblocked process that received the reply value and can also continue its computations.

This is how the chemical machine implements blocking molecules.
Blocking molecules work at once as [synchronizing barriers](https://en.wikipedia.org/wiki/Barrier_(computer_science)) and as channels of communication between processes.

The syntax for the reply action makes it appear as if the molecule `f` carries _two_ values - its `Unit` value and a special `reply` function, and that the reaction body calls this `reply` function with an integer value.
However, `f` is emitted with the syntax `f()` -- just as any other molecule with `Unit` value.
The `reply` function appears only in the pattern-matching expression for `f` inside a reaction.

Blocking molecule emitters are values of type `B[T,R]`, while non-blocking molecule emitters have type `M[T]`.
Here `T` is the type of value that the molecule carries, and `R` (for blocking molecules) is the type of the reply value.

The pattern-matching expression for a blocking molecule of type `B[T,R]` has the form

```scala
{ case ... + f(v, r) + ... => ... }

```
where `v` is of type `T` and `r` is a function of type `R => Unit`.
Since `r` has a function type, users must match it with a pattern variable.
For clarity, we will usually name this pattern variable `reply` or `r`.

## Example: Benchmarking the concurrent counter

To illustrate the usage of non-blocking and blocking molecules, let us consider the task of _benchmarking_ the concurrent counter we have previously defined.
The plan is to initialize the counter to a large value _N_, then to emit _N_ decrement molecules, and finally wait until the counter reaches the value 0.
We will use a blocking molecule to wait until this happens and thus to determine the time elapsed during the countdown.

Let us now extend the previous reaction site to implement this new functionality.
The simplest solution is to define a blocking molecule `fetch`, which will react with the counter molecule only when the counter reaches zero.
Since the `fetch()` molecule does not need to pass any data, we will define it as type `Unit` with a `Unit` reply.

```scala
val fetch = b[Unit, Unit]

```

We can implement this reaction by using a guard in the `case` clause:

```scala
go { case fetch(_, reply) + counter(n) if n == 0  => reply() }

```

For more clarity, we can also use Scala's pattern matching facility to implement the same reaction like this:

```scala
go { case counter(0) + fetch(_, reply)  => reply() }

```

Here is the complete code:

```scala
import code.chymyst.jc._

import java.time.LocalDateTime.now
import java.time.temporal.ChronoUnit.MILLIS

object C extends App {

  // declare molecule types
  val fetch = b[Unit, Unit]
  val counter = m[Int]
  val decr = m[Unit]

  // declare reactions
  site(
    go { case counter(0) + fetch(_, reply)  => reply() },
    go { case counter(n) + decr(_) => counter(n-1) }
  )

  // emit molecules

  val N = 10000
  val initTime = now
  counter(N)
  (1 to N).foreach( _ => decr() )
  fetch()
  val elapsed = initTime.until(now, MILLIS)
  println(s"Elapsed: $elapsed ms")
}

```

Some remarks:

- Molecules with unit values still require a pattern variable when used in the `case` construction.
For this reason, we write `decr(_)` and `fetch(_, reply)` in the match patterns.
However, these molecules can be emitted simply by calling `decr()` and `fetch()`.
- We declare both reactions in one reaction site, because these two reactions share the input molecule `counter`.
- Blocking molecules are like functions except that they will block as long as their reactions are unavailable.
If the relevant reaction never starts, -- for instance, because some input molecules are missing, -- a blocking molecule will block forever.
The runtime engine cannot detect this situation because it cannot determine whether the missing input molecules might become available in the near future.
- If several reactions are available for the blocking molecule, one of these reactions will be selected at random.
- Blocking molecule names are printed with the suffix `"/B"` in the debugging output.


# Molecules and reactions in local scopes

Since molecules and reactions are local values, they are lexically scoped within the block where they are defined.
If we define molecules and reactions in the scope of an auxiliary function, or in the scope of a reaction body, these newly defined molecules and reactions will be encapsulated and protected from outside access.

To illustrate this feature of the chemical paradigm, let us implement a function that defines a “concurrent counter” and initializes it with a given value.

Our previous implementation of the concurrent counter has a drawback: The molecule `counter(n)` must be emitted by the user and remains globally visible.
If the user emits two copies of `counter` with different values, the `counter + decr` and `counter + fetch` reactions will work unreliably, choosing between the two copies of `counter` at random.
We would like to emit exactly one copy of `counter` and then prevent the user from emitting any further copies of that molecule.

A solution is to define `counter` and its reactions within a function that returns the `decr` and `fetch` molecules to the outside scope.
The `counter` emitter will not be returned to the outside scope, and so the user will not be able to emit extra copies of that molecule.

```scala
def makeCounter(initCount: Int): (M[Unit], B[Unit, Int]) = {
  val counter = m[Int]
  val decr = m[Unit]
  val fetch = m[Unit, Int]

  site(
    go { counter(n) + fetch(_, r) => counter(n) + r(n)},
    go { counter(n) + decr(_) => counter(n - 1) }
  )
  // emit exactly one copy of `counter`
  counter(initCount)

  // return these two emitters to the outside scope
  (decr, fetch)
}

```

The closure captures the emitter for the `counter` molecule and emits a single copy of that molecule.
Users from other scopes cannot emit another copy of `counter` since the emitter is not visible outside the closure.
In this way, it is guaranteed that one and only one copy of `counter` will be present in the soup.

Nevertheless, the users receive the emitters `decr` and `fetch` from the closure.
So the users can emit these molecules and start their reactions.

The function `makeCounter` can be called like this:

```scala
val (d, f) = makeCounter(10000)
d() + d() + d() // emit 3 decrement molecules
val x = f() // fetch the current value

```

Also note that each invocation of `makeCounter()` will create new, fresh emitters `counter`, `decr`, and `fetch` inside the closure, because each invocation will create a fresh local scope and a new reaction site.
In this way, the user can create as many independent counters as desired.
Molecules defined in the scope of a certain invocation of `makeCounter()` will be chemically different from molecules defined in other invocations,
since they will be bound to the fresh reaction site defined during the same invocation of `makeCounter()`.

This example shows how we can encapsulate some molecules and yet use their reactions.
A closure can define local reaction with several input molecules, emit some of these molecules initially, and return some (but not all) molecule emitters to the outer scope.

## Example: implementing "First Result"

Suppose we have two blocking molecules `f` and `g` that return a reply value.
We would like to emit both `f` and `g` together and wait until a reply value is received from whichever molecule unblocks sooner.
If the other molecule gets a reply later, we will just ignore that value.

The result of this nondeterministic operation is the value of type `T` obtained from one of the molecules `f` and `g`, depending on which molecule got its reply first.

Let us now implement this operation in `JoinRun`.
We will derive the required chemistry by reasoning about the behavior of molecules.

The task is to define a blocking molecule emitter `firstReply` that will unblock when `f` or `g` unblocks, whichever happens first.
Let us assume for simplicity that both `f()` and `g()` return values of type `Int`.

It is clear that we need to emit both `f()` and `g()` concurrently.
But we cannot do this from one reaction, since `f()` will block and prevent us from emitting `g()`.
Therefore we need two different reactions, one emitting `f()` and another emitting `g()`.

These two reactions need some input molecules.
These input molecules cannot be `f` and `g` since these two molecules are given to us, their chemistry is already fixed, and so we cannot add new reactions that consume them.
Therefore, we need at least one new molecule that will be consumed to start these two reactions.
However, if we declare the two reactions as `c() => f()` and `c() => g()` and emit two copies of `c()`, we are not guaranteed that both reactions will start.
It is possible that two copies of the first reaction or two copies of the second reaction are started instead.
In other words, there will be an _unavoidable nondeterminism_ in our chemistry.

`JoinRun` will in fact detect this problem and generate an error:

```scala
val c = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
site(
    go { case c(_) => val x = f(); ??? },
    go { case c(_) => val x = g(); ??? }
)

```
`java.lang.Exception: In Site{c => ...; c => ...}: Unavoidable nondeterminism:`
`reaction c => ... is shadowed by c => ...`

So, we need to define two _different_ molecules (say, `c` and `d`) as inputs for these two reactions.

```scala
val c = m[Unit]
val d = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
site(
    go { case c(_) => val x = f(); ??? },
    go { case d(_) => val x = g(); ??? }
)
c() + d()

```

Since we have emitted both `c()` and `d()`, both reactions can now proceed concurrently.
When one of them finishes and gets a reply value `x`, we would like to use that value for replying to our new blocking molecule `firstResult`.

Can we reply to `firstResult` in the same reactions? This would require us to make `firstResult` an input molecule in each of these reactions.
However, we will have only one copy of `firstResult` emitted.
Having `firstResult` as input will therefore prevent both reactions from proceeding concurrently.

Therefore, there must be some _other_ reaction that consumes `firstResult` and replies to it.
This reaction must have the form

```scala
go { case firstResult(_, reply) + ??? => reply(x) }

```

In this reaction, we somehow need to obtain the value `x` that we will reply with.
So we need a new auxiliary molecule, say `done(x)`, that will carry `x` on itself.
The reaction with `firstResult` will then have the form

```scala
go { case firstResult(_, reply) + done(x) => reply(x) }

```

Now it is clear that the value `x` should be emitted on `done(x)` in both of the `c => ...` and `d => ...` reactions.
The complete program looks like this:

```scala
val firstResult = b[Unit, Int]
val c = m[Unit]
val d = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
val done = m[Int]

site(
  go { case c(_) => val x = f(); done(x) },
  go { case d(_) => val x = g(); done(x) }
)
site(
  go { case firstResult(_, r) + done(x) => r(x) }
)

c() + d()
val result = firstResult()

```

### Encapsulating chemistry in a function

The code as written works but is not encapsulated -- in this code, we define new molecules and new chemistry inline.
To remedy this, we can create a function that will return the `firstResult` emitter, given the emitters `f` and `g`.

The idea is to define new chemistry in a _local scope_ and return a new molecule emitter.
To make things more interesting, let us introduce a type parameter `T` for the result value of the given blocking molecules `f` and `g`.
The code looks like this:

```scala
def makeFirstResult[T](f: B[Unit, T], g: B[Unit, T]): B[Unit, T] = {
    // The same code as above, but now in a local scope.
    val firstResult = b[Unit, T]
    val c = m[Unit]
    val d = m[Unit]
    val f = b[Unit, Int]
    val g = b[Unit, Int]
    val done = m[Int]

    site(
      go { case c(_) => val x = f(); done(x) },
      go { case d(_) => val x = g(); done(x) }
    )
    site(
      go { case firstResult(_, r) + done(x) => r(x) }
    )

    c() + d()

    // Return only the `firstResult` emitter:
    firstResult
}

```

The main advantage of this code is to encapsulate all the auxiliary molecule emitters within the local scope of the method `makeFirstResult()`.
Only the `firstResult` emitter is returned:
This is the only emitter that users of `makeFirstResult()` need.
The other emitters (`c`, `d`, and `done`) are invisible to the users because they are local variables in the scope of `makeFirstResult`.
Now the users of `makeFirstResult()` cannot inadvertently break the chemistry by emitting some further copies of `c`, `d`, or `done`.
This is the hallmark of a successful encapsulation.

## Example: Parallel Or

We will now consider a task called “Parallel Or”; this is somewhat similar to the “first result” task considered above.

The goal is to create a new blocking molecule `parallelOr` from two given blocking molecules `f` and `g` that return a reply value of `Boolean` type.
We would like to emit both `f` and `g` together and wait until a reply value is received.
The `parallelOr` should reply with `true` as soon as one of the blocking molecules `f` and `g` returns `true`.
If both `f` and `g` return `false` then `parallelOr` will also return `false`.
If both molecules `f` and `g` block (or one of them returns `false` while the other blocks) then `parallelOr` will block as well.

We will now implement this operation in `JoinRun`.
Our task is to define the necessary molecules and reactions that will simulate the desired behavior.

Let us recall the logic we used when reasoning about the "First Result" problem.
We will again need two non-blocking molecules `c` and `d` that will emit `f` and `g` concurrently.
We begin by writing the two reactions that consume `c` and `d`:

```scala
val c = m[Unit]
val d = m[Unit]
val f = b[Unit, Boolean]
val g = b[Unit, Boolean]

site(
  go { case c(_) => val x = f(); ??? },
  go { case d(_) => val y = g(); ??? }
)
c() + d()

```

Now, the Boolean values `x` and `y` could appear in any order (or not at all).
We need to implement the logic of receiving these values and computing the final result value.
This final result must sit on some molecule, say `result`.
We should keep track of whether we already received both `x` and `y`, or just one of them.

When we receive a `true` value, we are dont.
So, we only need to keep track of the number of `false` values received.
Therefore, let us define `result` with integer value that shows how many intermediate `false` results we already received.

The next step is to communicate the values of `x` and `y` to the reaction that updates the `result` value.
For this, we can use a non-blocking molecule `done` and write reactions like this:

```scala
site(
  go { case c(_) => val x = f(); done(x) },
  go { case d(_) => val y = g(); done(y) },
  go { case result(n) + done(x) => result(n + 1) }
)
c() + d() + result(0)

```

The effect of this chemistry is that `result`'s value will get incremented whenever one of `f` or `g` returns.
This is not yet what we need, but we are getting closer.

What remains is to implement the required logic:
When `result` receives a `true` value, it should return `true` as a final answer, regardless of how many answers it received.
When `result` receives a `false` value twice, it should return `false` as a final answer.
Otherwise, there is no final answer yet.

We are required to deliver the final answer as a reply to the `parallelOr` molecule.
It is clear that `parallelOr` cannot be reacting with the `result` molecule -- this would prevent the `result + done => result` reaction from running.
Therefore, `parallelOr` needs to react with _another_ auxiliary molecule, say `finalResult`.
There is only one way of defining this kind of reaction,

```scala
go { case parallelOr(_, r) + finalResult(x) => r(x) }

```

Now it is clear that the problem will be solved if we emit `finalResult` only when we actually have the final answer.
This can be done from the `result + done => result` reaction, which we modify as follows:

```scala
go { case result(n) + done(x) =>
        if (x == true) finalResult(true)
        else if (n==1) finalResult(false)
        else result(n + 1)
   }

```

To make the chemistry clearer, we may rewrite this reaction as three reactions with conditional pattern matching:

```scala
site(
  go { case result(1) + done(false) => finalResult(false) },
  go { case result(0) + done(false) => result(1) },
  go { case result(_) + done(true)  => finalResult(true) }
)

```

Here is the complete code for `parallelOr`, where we have separated the reactions in three independent reaction sites.

```scala
val c = m[Unit]
val d = m[Unit]
val done = m[Boolean]
val result = m[Int]
val finalResult = m[Boolean]
val f = b[Unit, Boolean]
val g = b[Unit, Boolean]
val parallelOr = b[Unit, Boolean]

site(
  go { case parallelOr(_, r) + finalResult(x) => r(x) }
)
site(
  go { case result(1) + done(false) => finalResult(false) },
  go { case result(0) + done(false) => result(1) },
  go { case result(_) + done(true)  => finalResult(true) }
)
site(
  go { case c(_) => val x = f(); done(x) },
  go { case d(_) => val y = g(); done(y) }
)

c() + d() + result(0)

```

As an exercise, the reader should now try to encapsulate the `parallelOr` operation into a function.
(This is done in the tests in `ParallelOrSpec.scala`.)

Another exercise: Implement `parallelOr` for _three_ blocking emitters (say, `f`, `g`, `h`).
The `parallelOr` emitter should return `true` whenever one of these emitters returns `true`; it should return `false` if _all_ of them return `false`; and it should block otherwise.

## Errors with blocking molecules

Each blocking molecule must receive one (and only one) reply.
It is an error if a reaction consumes a blocking molecule but does not reply.
It is also an error to reply again after a reply was made.

Sometimes, errors of this type can be caught at compile time:

```scala
val f = b[Unit, Int]
val c = m[Int]
site( go { case f(_,r) + c(n) => c(n + 1) } ) // forgot to reply!
// compile-time error: "blocking input molecules should receive a reply but no reply found"

site( go { case f(_,r) + c(n) => c(n + 1) + r(n) + r(n) } ) // replied twice!
// compile-time error: "blocking input molecules should receive one reply but multiple replies found"

```

However, the reply could depend on a run-time condition, which is impossible to evaluate at compile time.
In this case, a reaction that does not reply will generate a run-time exception:

```scala
val f = b[Unit, Int]
val c = m[Int]
site( go { case f(_,r) + c(n) => c(n + 1); if (n != 0) r(n) } )

c(1)
f()

```
`java.lang.Exception: Error: In Site{c + f/B => ...}: Reaction {c + f/B => ...} finished without replying to f/B`

Note that this exception will be thrown only when reaction actually starts and the run-time condition `n != 0` is evaluated to `false`.
Also, the exception may occur on another thread, which will not be immediately visible.
For these reasons, it is not easy to catch errors of this type, either at compile time or at run time.
To avoid these problems, it is advisable to reorganize the chemistry such that reply actions are unconditional.
