# Blocking vs. non-blocking molecules

So far, we have used molecules whose injection was a non-blocking call:
Injecting a molecule, such as `b(123)`, immediately returns `Unit` but performs a concurrent side effect (to add a new molecule to the soup).
Such molecules are called **non-blocking**.

An important feature of the chemical machine is the ability to define **blocking molecules**.
Just like non-blocking molecules, a blocking molecule can be injected into the soup with a value.
However, there are two major differences:

- injecting a blocking molecule will return a **reply value**, which is supplied by some reaction that consumed that blocking molecule;
- injecting a blocking molecule will block the injecting process, until some reaction consumes the blocking molecule and sends the reply value.

Here is an example of declaring a blocking molecule:

```scala
val f = b[Unit, Int]

```

The blocking molecule `f` carries a value of type `Unit`; the reply value is of type `Int`.
(These types can be arbitrary, just as for non-blocking molecules.)

The runtime engine treats an injection of a blocking molecule in a special way:

1. The injection call such as `f()` will insert `f()` into the soup.
2. The injecting process (which can be another reaction body or any other code) will be blocked until _some reaction can consume_ the newly injected molecule `f()`.
3. Once such a reaction starts, this reaction's body will **reply to the blocking molecule** -- that is, send a reply value back to the injecting process.
4. Once the reply value has been received, the injecting process is unblocked. To this process, the reply value appears to be the value returned by the function call `f()`.

Sending a reply value is a special feature available only within reactions that consume blocking molecules.
We call this feature the **reply action**.

Here is an example showing the `JoinRun` syntax for the reply action.
Suppose we have a reaction that consumes a non-blocking molecule `c` with an integer value and the blocking molecule `f` defined above.
We would like the blocking molecule to return the integer value that `c` carries:

```scala
val f = b[Unit, Int]
val c = m[Int]

join( run { case c(n) + f(_ , reply) => reply(n) } )

c(123) // inject an instance of `c` with value 123

val x = f() // now x = 123

```

Let us walk through the execution of this example step by step.

After defining the chemical law `c + f => ...`, we first inject an instance of `c(123)`.
By itself, this does not start any reactions since the chemical law states `c + f => ...`, and we don't yet have any instances of `f` in the soup.
So `c(123)` will remain in the soup for now.

Next, we call the blocking injector `f()`, which injects an instance of `f()` into the soup.
Now the soup has both `c` and `f`, while the calling process is blocked until a reaction involving `f` can start.

We have such a reaction: this is `c + f => ...`. This reaction is ready to start since both its inputs, `c` and `f`, are now present.
Nevertheless, the start of the reaction is concurrent with the process that calls `f`, and may occur somewhat later than the call to `f()`, depending on the CPU load.
So, the call to `f()` will be blocked for some (hopefully short) time.

Once the reaction starts, it will receive the value `n = 123` from the input molecule `c(123)`.
Both `c(123)` and `f()` will be consumed by the reaction.
The reaction will then perform the reply action `reply(n)` with `n` set to `123`.
Only at this point the calling process will get unblocked and receive `123` as the return value of the function call `f()`.
From the point of view of the reaction that consumed a blocking molecule, the reply action is a non-blocking (i.e. very fast) function call.
The reaction will continue evaluating its reaction body, concurrently with the newly unblocked process that received the reply value `123` and can continue its computations.

This is how the chemical machine implements blocking molecules.
Blocking molecules work at once as synchronizing barriers and as channels of communication between processes.

The syntax for the reply action makes it appear as if the molecule `f` carries _two_ values - its `Unit` value and a special `reply` function, and that the reaction body calls this `reply` function with an integer value.
However, `f` is injected with the syntax `f()` -- just as any other molecule with `Unit` value.
The `reply` function appears only in the pattern-matching expression for `f` inside a reaction.

Blocking molecule injectors are values of type `B[T,R]`, while non-blocking molecule injectors have type `M[T]`.
Here `T` is the type of value that the molecule carries, and `R` (for blocking molecules) is the type of the reply value.

The pattern-matching expression for a blocking molecule of type `B[T,R]` has the form `case ... + f(v, r) + ...` where `v` is of type `T` and `r` is of type `R => Boolean`.
Since `r` has a function type, users must match it with a pattern variable.
The names `reply` or `r` can be used for clarity.

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

## Example: implementing "First Result"

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
In other words, there will be an unavoidable nondeterminism in our chemistry.
`JoinRun` will in fact detect this problem and generate an error:

```scala
val c = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
join(
    run { case c(_) => val x = f(); ... },
    run { case c(_) => val x = g(); ... }
)
java.lang.Exception: In Join{c => ...; c => ...}: Unavoidable nondeterminism: reaction c => ... is shadowed by c => ...
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
This reaction must have the form

`firstResult(_, reply) + ??? => reply(x)`

It is clear that we need to have access to the value `x` that we will reply with.
Therefore, we need a new auxiliary molecule, say `done(x)`, that will carry `x` on itself.
The reaction with `firstResult` will then have the form `firstResult(_, reply) + done(x) => reply(x)`.

Now it is clear that the value `x` should be injected on `done(x)` in both of the reactions.
The complete program looks like this:

```scala
val firstResult = b[Unit, Int]
val c = m[Unit]
val d = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
val done = m[Int]

join(
  run { case c(_) => val x = f(); done(x) },
  run { case d(_) => val x = g(); done(x) }
)
join(
  run { case firstResult(_, r) + done(x) => r(x) }
)

c() + d()
val result = firstResult()
```

### How to encapsulate the new chemistry

The code as written works but is not encapsulated - we are defining new molecules and new chemistry inline.
There are two ways we could encapsulate this chemistry:

- create a function that will return the `firstResult` injector, given the injectors `f` and `g`
- create a “universal First Result molecule” that carries `f` and `g` as parts of its value and performs the required chemistry

#### Refactoring as a function

The idea is to create a function that will define the new chemistry in its _local scope_ and return the new molecule injector.
To make things more interesting, let us introduce a type parameter `T` for the result value of the given blocking molecules `f` and `g`.
The code should look like this:

```scala
def makeFirstResult[T](f: B[Unit, T], g: B[Unit, T]): B[Unit, T] = {
    // the same code as above
    val firstResult = b[Unit, T]
    val c = m[Unit]
    val d = m[Unit]
    val f = b[Unit, Int]
    val g = b[Unit, Int]
    val done = m[Int]

    join(
      run { case c(_) => val x = f(); done(x) },
      run { case d(_) => val x = g(); done(x) }
    )
    join(
      run { case firstResult(_, r) + done(x) => r(x) }
    )

    c() + d()

    // return only the `firstResult` injector:
    firstResult
}

```

The main advantage of this code is to encapsulate all the auxiliary molecule injectors within the local scope of the method `makeFirstResult`.
Only the `firstResult` injector is returned:
This is the only injector that the user of this library needs.
The other injectors (`c`, `d`, and `done`) are invisible to the user since they are local variables in the scope of `makeFirstResult`.
The user of the library cannot break the chemistry by inadvertently injecting some further copies of `c`, `d`, or `done`.


#### Refactoring as a molecule

TODO

## Example: Parallel Or

We will now consider a task called “Parallel Or”; this is somewhat similar to the “first result” task considered above.

The task is to create a new blocking molecule `parallelOr` from two given blocking molecules `f` and `g` that return a reply value of `Boolean` type.
We would like to inject both `f` and `g` together and wait until a reply value is received.
The `parallelOr` should reply with `true` as soon as one of the blocking molecules `f` and `g` returns `true`.
If both `f` and `g` return `false` then `parallelOr` will also return `false`.
If both molecules `f` and `g` block (or one of them returns `false` while the other blocks) then `parallelOr` will block as well.

We will now implement this operation in `JoinRun`.
Our task is to define the necessary molecules and reactions that will simulate the desired behavior.

Let us recall the logic we used when reasoning about the "First Result" problem.
We will again need two non-blocking molecules `c` and `d` that will inject `f` and `g` on different threads.
We begin by writing the two reactions that consume `c` and `d`:

```scala
val c = m[Unit]
val d = m[Unit]
val f = b[Unit, Boolean]
val g = b[Unit, Boolean]

join(
  run { case c(_) => val x = f(); ??? },
  run { case d(_) => val y = g(); ??? }
)
c() + d()

```

Now, the Boolean values `x` and `y` could be obtained in any order (or not at all).
We need to implement the logic of receiving these values and computing the final result value.
This final result must sit on some molecule, say `result`.
We should keep track of whether we already received both `x` and `y`, or just one of them.
So let us define `result` with integer value that shows how many intermediate `false` results we already received.

The next task is to be able to communicate the value of `x` and `y` to the reaction that will update the `result` value.
For this, we can use a non-blocking molecule `done` and write reactions like this:

```scala
val c = m[Unit]
val d = m[Unit]
val done = m[Boolean]
val result = m[Int]
val f = b[Unit, Boolean]
val g = b[Unit, Boolean]

join(
  run { case c(_) => val x = f(); done(x) },
  run { case d(_) => val y = g(); done(y) },
  run { case result(n) + done(x) => result(n+1) }
)
c() + d() + result(0)

```

The effect of this chemistry will be that `result`'s value will get incremented whenever one of `f` or `g` returns.
This is not yet what we need, but we are getting closer.

What remains is to implement the required logic:
When `result` receives a `true` value, it should return `true` as a final answer (regardless of how many answers it received).
When `result` receives a `false` value twice, it should return `false` as a final answer.
Otherwise, there should be no final answer.

We are required to deliver the final answer as a reply to the `parallelOr` molecule.
It is clear that `parallelOr` cannot be reacting with the `result` molecule -- this would prevent the `result + done => result` reaction from running.
Therefore, `parallelOr` needs to react with another auxiliary molecule, say `finalResult`.
There is only one way of defining this kind of reaction,

```scala
run { case parallelOr(_, r) + finalResult(x) => r(x) }

```

Now it is clear that the problem will be solved if we inject `finalResult` only when we actually have the final answer.
This can be done from the `result + done => result` reaction, which we modify as follows:

```scala
run { case result(n) + done(x) =>
        if (x == true) finalResult(true)
        else if (n==1) finalResult(false)
        else result(n+1)
    }
```

To make the chemistry clearer, we can rewrite this as three reactions using pattern-matching:

```scala
join(
  run { case result(_) + done(true) => finalResult(true) },
  run { case result(1) + done(false) => finalResult(false) },
  run { case result(0) + done(false) => result(1) }
)
```

Here is the complete code for `parallelOr`, where we have separated the reactions in three independent join definitions.

```scala
val c = m[Unit]
val d = m[Unit]
val done = m[Boolean]
val result = m[Int]
val finalResult = m[Boolean]
val f = b[Unit, Boolean]
val g = b[Unit, Boolean]
val parallelOr = b[Unit, Boolean]

join(
  run { case parallelOr(_, r) + finalResult(x) => r(x) }
)
join(
  run { case result(_) + done(true) => finalResult(true) },
  run { case result(1) + done(false) => finalResult(false) },
  run { case result(0) + done(false) => result(1) }
)
join(
  run { case c(_) => val x = f(); done(x) },
  run { case d(_) => val y = g(); done(y) }
)

c() + d() + result(0)
```

As an exercise, the reader should now try to encapsulate the `parallelOr` operation into a library function.
(This is done in the tests in `ParallelOrSpec.scala`.)

As another exercise: Revise these reactions to incorporate more than two blocking injectors (say, `f`, `g`, `h`, `i`, `j`).
The `parallelOr` injector should return `true` whenever one of these injectors returns `true`; it should return `false` if _all_ of them return `false`; and it should block otherwise.

## Error checking for blocking molecules

Each blocking molecule must receive one (and only one) reply.
It is an error if a reaction consumes a blocking molecule but does not reply.
It is also an error to reply again after a reply was made.

Sometimes, errors of this type can be caught at compile time:

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
Also, the exception may be thrown on another thread, which may not be immediately visible.
For these reasons, it is not easy to catch errors of this type, either at compile time or at runtime.
To avoid these problems, it is advisable to reorganize the chemistry such that reply actions (and, more generally, output molecule injections) are unconditional.

