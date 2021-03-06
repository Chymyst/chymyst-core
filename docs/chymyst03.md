<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Blocking vs. non-blocking molecules

## Motivation for the blocking molecule feature

So far, we have used molecules whose emission was a non-blocking call.
Emitting a molecule `a()`, for example by calling its emitter as `a(123)`, immediately returns `Unit`
but performs a concurrent side effect, adding a new copy of the molecule `a()` to the soup.
Such molecules are called **non-blocking**.

When a reaction emits a non-blocking molecule, that molecule could later cause another reaction to start and compute some result.
However, the emitting reaction continues to run concurrently and therefore has no direct access to the result computed by another reaction.
It is sometimes convenient to be able to wait until the other reaction starts and computes the result, and then to obtain the result value in the first reaction.

Here is some skeleton code that illustrates the problem.

```scala
val a = m[Int]
val result = m[Int]

site(
  go { case a(x) ⇒
    val y = f(x) // some computation
    result(y)
  }
)
a(123)
// Waiting for `result()` to be emitted.
// We would like to obtain `y` here
// and continue computations with it.

```

This feature is realized in the chemical machine with help of **blocking molecules**.

## How blocking molecules work

Just like non-blocking molecules, a blocking molecule carries a value and can be emitted into the soup.
However, there are two major differences:

- emitting a blocking molecule is a function call that returns a **reply value** of a fixed type, rather than `Unit`;
- emitting a blocking molecule will block the emitting reaction or thread, until some reaction consumes the blocking molecule and sends the reply value.

Here is an example of declaring a blocking molecule:

```scala
val f = b[Unit, Int]

```

The blocking molecule `f` carries a value of type `Unit`; the reply value is of type `Int`.
We can choose these types at will.

This is how we can emit the blocking molecule `f` and wait for the reply:

```scala
val x: Int = f() // blocking emitter

```

The chemical machine emits a blocking molecule in a special way:

1. The emission call, such as `f(x)`, will add a new copy of the molecule `f(x)` to the soup, — this is so with any molecule.
2. However, the original emitting process (which can be a running reaction body or any other code) will be blocked at least until _some other_ reaction starts running and consumes the emitted copy of `f(x)`.
3. Once such a reaction starts, that reaction's body must **send a reply value** to the original process that emitted `f()`.
Sending a reply value is accomplished by calling a special **reply emitter**, which is only available within the scope of reactions that consume blocking molecules.
4. The original process becomes unblocked right after it receives the reply value.
To the original process, the reply value appears to be the value returned by the function call `f(x)`.
Within the reaction that sends a reply, the reply call is asynchronous (it returns immediately, without waiting for the unblocked process).

Here is an example showing `Chymyst`'s syntax for emitting a reply.
Suppose we have a reaction that consumes a non-blocking molecule `c()` with an integer value and the blocking molecule `f()` defined above.
We would like to use the blocking molecule in order to fetch the integer value that `c()` carries.
The typical code for this kind of reaction looks like this:

```scala
val f = b[Unit, Int]
val c = m[Int]

site( go { case c(n) + f(_ , rpl) ⇒ rpl(n) } )

c(123) // emit a molecule `c` with value 123

val x = f() // now x = 123

```

Let us walk through the execution of this code step by step.

The blocking molecule `f()` is defined with two type parameters `[Unit, Int]`.
The `Unit` type is the value it carries; the `Int` type is the reply value it receives.

After defining the chemical law `c + f → ...`, we first emit an instance of `c(123)`.
By itself, this does not start any reactions since the chemical law states `c + f → ...`, and we don't yet have any copies of `f()` in the soup.
So `c(123)` will remain in the soup for now, waiting at the reaction site.

Next, we call `f()`, which emits an instance of `f()` into the soup.
Now the soup has both `c` and `f`.
According to the semantics of blocking molecules, the calling process is blocked until a reaction consuming `f()` can start.

At this point, we have such a reaction: this is `c + f → ...`.
This reaction is actually ready to start since both its inputs, `c()` and `f()`, are now present.
Nevertheless, the start of that reaction is concurrent with the process that calls `f()`, and may occur somewhat later than the call to `f()`, depending on the CPU load.
So, the call to `f()` will be blocked for some (hopefully short) time.

Once the reaction `c + f → ...` starts, it will consume both `c(123)` and `f()` and receive the value `n = 123` from the input molecule `c(123)`.
The value `rpl` is defined within the scope of the reaction as the _second_ pattern variable in the input molecule pattern `f(_, rpl)`.
The value `rpl` is the reply emitter for the blocking molecule `f()`, and the reaction calls it as `rpl(n)` with `n = 123`.
This call sends the reply value `123` back to the calling process.
After that, the calling process will get unblocked and receive `123` as the return value of the function call `f()`.

From the point of view of the reaction `c + f → ...`, sending a reply is a non-blocking (i.e. a very fast) function call.
So, reply emitters are similar to non-blocking molecules.

After replying, the reaction `c + f → ...` will continue evaluating whatever code follows the reply emitter call.
The newly unblocked process that received the reply value will continue to run _concurrently_ with the reaction that replied.

We see that blocking molecules work at once as [synchronizing barriers](https://en.wikipedia.org/wiki/Barrier_(computer_science)) and as channels of communication between processes.

The syntax for the reaction,

```scala
go { case c(n) + f(_, rpl) ⇒ rpl(n) }

```

makes it appear as if the molecule `f()` carries _two_ values — its ordinary `Unit` value and the reply emitter value `rpl`.
However, `f` is emitted with the syntax `f()` — just as any other molecule with `Unit` value.
The `rpl` emitter appears _only_ in the pattern-matching expression for `f` inside a reaction.
We call `rpl` the **reply emitter** because the call to `rpl()` has a concurrent side-effect similar to emitting a non-blocking molecule.

Note that the name `rpl` is not a keyword but an arbitrary name of a pattern variable; it could have been `x` or `reply_to_f` or whatever else.

Blocking molecule emitters are values of type `B[T, R]`, while non-blocking molecule emitters have type `M[T]`.
Here `T` is the type of value that the molecule carries, and `R` (for blocking molecules) is the type of the reply value.
The reply emitter is of special type `ReplyEmitter[T, R]`.

In general, the pattern-matching expression for a blocking molecule `g` of type `B[T, R]` has the form

```scala
{ case ... + g(v, r) + ... ⇒ ... }

```

The pattern variable `v` will match a value of type `T`.
The pattern variable `r` will match the reply emitter.

The reply emitter must be matched with a simple pattern variable.
For clarity, we will usually name this pattern variable `reply` or `r`.
It is an error to use a non-variable pattern for the reply emitter.

## Example: Benchmarking the asynchronous counter

To illustrate the usage of non-blocking and blocking molecules, let us consider the task of _benchmarking_ the asynchronous counter we have previously defined.
The plan is to initialize the counter to a large value _N_, then to emit _N_ decrement molecules, and finally wait until the counter reaches the value 0.
We will use a blocking molecule to wait until this happens and thus to determine the time elapsed during the countdown.

Let us now extend the previous reaction site to implement this new functionality.
The simplest solution is to define a blocking molecule `fetch()`, which will react with the counter molecule only when the counter reaches zero.
Since the `fetch()` molecule does not need to pass any data, we will define it as type `Unit` with a `Unit` reply.

```scala
val fetch = b[Unit, Unit]

```

We can implement this reaction by using a guard in the `case` clause:

```scala
go { case fetch(_, reply) + counter(n) if n == 0  ⇒ reply() }

```

For more clarity, we can also use Scala's pattern matching facility to implement the same reaction like this:

```scala
go { case counter(0) + fetch(_, reply)  ⇒ reply() }

```

Here is the complete code:

```scala
import io.chymyst.jc._


object C3 extends App {

  // declare molecule types
  val fetch = b[Unit, Unit]
  val counter = m[Int]
  val decr = m[Unit]

  // declare reactions
  site(
    go { case counter(0) + fetch(_, reply)  ⇒ reply() },
    go { case counter(n) + decr(_) ⇒ counter(n - 1) }
  )

  // emit molecules

  val N = 10000
  val initTime = System.currentTimeMillis()
  counter(N)
  (1 to N).foreach( _ ⇒ decr() )
  fetch()
  val elapsed = System.currentTimeMillis() - initTime
  println(s"Elapsed: $elapsed ms")
}

```

Some remarks:

- We declare both reactions in one reaction site because these two reactions contend on the common input molecule `counter`.
- Blocking molecules are like functions except that they will block until their reactions can start.
If the relevant reaction never starts, — for instance, because some input molecules are missing, — a blocking molecule will block forever.
The runtime engine cannot prevent this situation, because it cannot determine whether the currently missing input molecules might become available in the future.
- The correct functionality of a chemical program may depend on the order in which blocking molecules are emitted.
If `f()` and `g()` are blocking molecules, the effect of emitting `f(); g()` is not the same as that of `g(); f()` because `f(); g()` will not emit `g()` until a reply to `f` is received.
With non-blocking molecules, the order of emission is irrelevant since the emission calls are asynchronous.
So `c(); d()` has the same effect as `d(); c()` if `c` and `d` are non-blocking emitters.
- If several reactions can consume a blocking molecule, one of these reactions will be selected arbitrarily.
- Blocking molecule names are printed with the suffix `"/B"` in the debugging output.
- Molecules with unit values can be emitted simply by calling `decr()` and `fetch()` without arguments, but they still require a pattern variable when used in the `case` construction.
For this reason, we need to write `decr(_)` and `fetch(_, reply)` in the match patterns.

### Example: waiting for a background thread

Previously, we implemented a function to start a background thread [using non-blocking molecules](chymyst01.md#start-a-process-in-the-background).
That worked, but what if we wanted to wait until the background thread's computation is finished?
We will now implement this functionality.

We will still use a non-blocking molecule `start()` for starting a computation on a background thread.
To wait for the completion of the computation, we will use a blocking molecule `wait_done()`.

Clearly, that molecule needs to react with some other molecule, which should not be present until the computation is finished.
Let us therefore define a third molecule, `done()`, which we will emit only at the end of the computation.

We implement the solution as a function `start_join` that returns a pair of new molecule emitters implementing the required logic.

```scala
def start_wait(): (M[() ⇒ Unit], B[Unit, Unit]) = {
  val start = m[() ⇒ Unit]
  val wait_for_done = b[Unit, Unit]
  val done = m[Unit]
  site(
    go { case start(f) ⇒ f(); done() },
    go { case wait_for_done(_, reply) + done(_) ⇒ reply() }
  )
}

// Usage:
val (start, wait_for_done) = start_wait()
start { () ⇒ println("Running in the background!") }
// Other code... Now wait:
wait_for_done()

```

### Exercise: waiting for a background thread, fetching its result.

Implement a chemical program that starts a separate background thread, evaluating a given function of type `() ⇒ A`.
Additionally, a blocking call should be available to wait for the background thread.
The blocking call returns the value of type `A` computed by the given function, when the evaluation in the background thread is finished.

#### Solution

We implement a function `start_wait` that returns a pair of new molecule emitters implementing the required logic.

```scala
def start_wait[A]: (M[() ⇒ A], B[Unit, A]) = {
  val start = m[() ⇒ A]
  val wait_for_done = b[Unit, A]
  val done = m[A]
  site(
    go { case start(f) ⇒ done(f()) }, // Equivalently, `val x = f(); done(x)`.
    go { case wait_for_done(_, reply) + done(x) ⇒ reply(x) }
  )
}

// Usage:
val (start, wait_for_done) = start_wait[Int]
start { () ⇒ println("Running in the background, returning 123."); 123 }
// Other code... Now wait for completion and fetch the result:
val result = wait_for_done()

```

### Example: waiting for multiple background threads

The previous exercise works but has a drawback: if we call `start()` several times with different functions,
several background computations will be started in parallel.
If we then call `wait_for_done()`, we will not know which of the results we will get.
We may get the first result computed by any of the started computations.

To avoid this problem, we could create a new pair of `start` and `wait_done` molecule for each background computation.
But this will not prevent the application code from calling `start()` several times by mistake.

So let us now implement a function that starts a separate background thread, evaluating a given function of type `() ⇒ A`,
and returning a value representing a “handle” on the started thread.
A blocking call should be able to wait for the background thread by using the “handle”,
and will return the value of type `A` computed by the thread corresponding to that “handle”.

This functionality is equivalent to standard Scala's `Future` and `Await.result()`.

#### Solution

We can use the molecule `wait_for_done()` as a “handle” on the started thread, and then
we will need to create and return a new `wait_for_done` each time.

Note that `start()` is now a function, rather than a molecule emitter call. The `start_m` emitter is now hidden from the user.

```scala
def start[A](func: () ⇒ A): B[Unit, A] = {
  val start_m = m[() ⇒ A]
  val wait_for_done = b[Unit, A]
  val done = m[A]
  site(
    go { case start_m(f) ⇒ done(f()) },
    go { case wait_for_done(_, reply) + done(x) ⇒ reply(x) }
  )
  start_m(func)
}

// Usage:
val wait_for_done = start { () ⇒ println("Running in the background, returning 123."); 123 }
// Other code... Now wait for completion and fetch the result:
val result = wait_for_done() // Returns 123.

```

## Example: Readers/Writers with blocking molecules

Previously, we implemented the Readers/Writers problem [using non-blocking molecules](chymyst02.md#example-readerswriters).

The final code looked like this:

```scala
// Code for Readers/Writers access control, with non-blocking molecules.
val read = m[M[Int]]
val write = m[(Int, M[Unit])]
val access = m[Int]
val readerFinished = m[Unit]
val n = 3 // can be a run-time parameter
site(
  go { case read(readResult) + access(k) if k < n ⇒
    access(k + 1)
    val x = readResource(); readResult(x)
    readerFinished()
  },
  go { case write((x, writeDone)) + access(0) ⇒ writeResource(x); access(0) + writeDone() },
  go { case readerFinished(_) + access(k) ⇒ access(k - 1) }
)
access(0) // Emit at the beginning.

```

Let us see what changes are necessary if we want to use blocking molecules, and what advantages that brings.

We would like to change the code so that `read()` and `write()` are blocking molecules.
With that change, reactions that emit `read()` or `write()` are going to be blocked until access is granted.
This will make the Readers/Writers functionality easier to use.
(The cost is that some threads will become blocked.)

When we change the types of the `read()` and `write()` molecules to blocking, we will have to emit replies in reactions that consume `read()` and `write()`.
We can also omit `readResult()` since the `read()` molecule will now receive a reply value.
Other than that, reactions remain essentially unchanged:

```scala
// Code for Readers/Writers access control, with blocking molecules.
val read = b[Unit, Int]
val write = b[Int, Unit]
val access = m[Int]
val finished = m[Unit]
val n = 3 // can be a run-time parameter
site(
  go { case read(_, readReply) + access(k) if k < n ⇒
    access(k + 1)
    val x = readResource(); readReply(x)
    finished()
  },
  go { case write(x, writeReply) + access(0) ⇒ writeResource(x); writeReply(); access(0) },
  go { case finished(_) + access(k) ⇒ access(k - 1) }
)
access(0) // Emit at the beginning.

```

Note the similarity between this blocking version and the previous non-blocking version.
The reply molecules play the role of the auxiliary molecules `readResult()` and `writeDone()`.
Instead of passing these auxiliary molecules on values of `read()` and `write()`,
we simply declare the molecules `read()` and `write()` as blocking molecules
and get the `writeReply` and `readReply` emitters automatically.

Also, the Reader and Writer client programming is now significantly streamlined compared to the non-blocking version:
we do not need the boilerplate previously used to mitigate the "stack ripping" problem.

```scala
// Code for Reader client reactions, with blocking molecules.
site(
  go { case startReader(_) ⇒
    val a = ???
    val b = ???
    val x = read() // This is blocked until we get access to the resource.
    continueReader(a, b, x)
  }
)

```

A side benefit is that emitting `read()` and `write()` will get unblocked only _after_ the `readResource()` and `writeResources()` operations are complete.

The price for this convenience is that the Reader thread will remain blocked until access is granted.
The non-blocking version of the code never blocks any threads, which improves parallelism.

## The unblocking transformation

By comparing two versions of the Readers/Writers code, we notice that there is a correspondence between blocking and non-blocking code styles.
A reaction that emits a blocking molecule is equivalent to two reactions, using a new auxiliary non-blocking molecule defined in the scope of the first reaction.
The reply emitter is replaced by an ordinary non-blocking molecule emitter
whose corresponding molecule triggers a reaction that computes the required continuation.

The following two code snippets illustrate the correspondence.

The initial code snippet:

```scala
// Reaction that emits a blocking molecule.
val blockingMol = b[T, R]
go { case ... ⇒
  /* start of reaction body 1, defines `a`, `b`, ... */
  val t: T = ???
  val x: R = blockingMol(t)
  /* continuation of reaction body 1, can use `x`, `a`, `b`, `t`, ... */
}

// Reaction that consumes the blocking molecule.
go { case blockingMol(t, reply) + ... ⇒
  /* start of reaction body 2, uses `t`, defines `x` */
  val x: R = ???
  reply(x) // reply emitter
  /* rest of reaction body 2 */
}

```

The same code after the unblocking transformation:

```scala
// Reaction that emits a non-blocking molecule.
val nonBlockingMol = m[(T, M[R])] // this replaces `blockingMol`
go { case ... ⇒
  /* start of reaction body 1, defines `a`, `b`, ... */
  val t: T = ???
  val auxReply = m[T] // auxiliary reply emitter
  site(
    go { case auxReply(x) ⇒
    /* continuation of reaction body 1, can use `x`, `a`, `b`, `t`, ... */
    }
  )
  nonBlockingMol((t, auxReply)) // this replaces the call `blockingMol(t)`
}

// Reaction that consumes the non-blocking molecule.
go { case nonBlockingMol((t, reply)) + ... ⇒
  /* start of reaction body 2, uses `t`, defines `x` */
  val x: R = ???
  reply(x) // ordinary, non-blocking molecule emitter
  /* rest of reaction body 2 */
}

```

This example is the simplest case where the blocking molecule is emitted in the middle of a simple list of declarations within the reaction body.
In order to transform this code into non-blocking code, the reaction body is split at the point where the blocking molecule is emitted.
The rest of the reaction body is then moved into a nested auxiliary reaction that consumes `auxReply()`.

This code transformation can be seen as an optimization: When we translate blocking code into non-blocking code, we improve efficiency of the CPU usage because fewer threads will be waiting.
For this reason, it is desirable to perform this **unblocking transformation** when possible.

If the blocking molecule is emitted inside an `if-else` or a `match-case` block, the unblocking transformation will need more work.
Sometimes it will be necessary to introduce multiple auxiliary reply molecules and multiple nested reactions, corresponding to all the possible continuations of the reaction body.
Also, the transformation may need to duplicate some parts of the reaction body's Scala code.

As an example, consider this reaction:

```scala
val a = m[Int]
val f = b[Unit, Int]
val g = b[Unit, Int]
val report = m[Int]

site(go { case a(x) ⇒ 
  val result = if (x > 0) {
    1 + f()
  } else {
    2 + g()
  }
  // use `result` here
  report(result)
})

```

The important detail is that the reply values of `f()` and `g()` are used for intermediate computations inside the `if-else` block.
In this example, the computations are simply adding 1 or 2 to the reply values, but in general there can be arbitrary further computations within each of the two clauses.
Because of this, the unblocking transformation needs two auxiliary reply molecules, and the Scala code following the `if-else` block needs to be duplicated:

```scala
val a = m[Int]
val f = m[M[Int]]
val g = m[M[Int]]
val report = m[Int]
val reply1 = m[Int]
val reply2 = m[Int]

site(go { case a(x) ⇒ 
  site(
    go { case reply1(r) ⇒
      val result = 1 + r
      // use `result` here
      report(result)
    },
    go { case reply2(r) ⇒
      val result = 2 + r
     // use `result` here
      report(result) }
  )
  if (x > 0) f(reply1) else g(reply2)
})

```

The duplicated code could be refactored into an auxiliary function or, as shown in the code snippet above, into an emitted molecule `report()`, but the duplication of the `report()` call is unavoidable.

Similarly, the unblocking transformation becomes more involved when several blocking molecules are emitted sequentially within a reaction body:

```scala
val a = m[Int]
val f = b[Unit, Int]
val g = b[Unit, Int]
val report = m[Int]

site(go { case a(x) ⇒ 
  val result = f() + g()
  // use `result` here
  report(result)
})

```

The unblocking transformation must be performed first for the `f()` and then for the `g()` call:

```scala
val a = m[Int]
val f = b[Unit, Int]
val g = b[Unit, Int]
val report = m[Int]

site(go { case a(x) ⇒ 
  val result = f() + g()
  // use `result` here
  report(result)
})

```

There are some cases where the unblocking transformation is problematic.
One such case is a blocking molecule emitted inside a loop, or more generally, within a function scope:

```scala
val f = b[Unit, Int]
def callF(): Int = {
  val x = f()
  val y = f()
  x + y
}

```

The unblocking transformation requires us to replace `val x = f()` by an auxiliary reaction such as

```scala
go { case reply(res) ⇒
  val x = res
  // continuation goes here!
}

```

However, the function `callF()` could be invoked elsewhere in the application code,
and we cannot insert a fixed continuation code into the auxiliary reaction shown above.
In such cases, it is impossible to perform the unblocking transformation automatically.
The programmer would need to redesign the program manually, - for example, replacing the function `callF()` by a molecule emitter `callF` with the corresponding reactions.

In order to ensure the possibility of the unblocking transformation for reaction bodies, `Chymyst` currently prohibits emitting blocking molecules in code contexts that are not guaranteed to be evaluated exactly once:

```scala
val c = m[Unit]
val f = b[Int, Int]
go { case c(_) ⇒ (1 to 10).map(i ⇒ f(i + 1)) }

```

`Error:(245, 8) reaction body must not emit blocking molecules inside function blocks (f(?))`
`    go { case c(_) ⇒ (1 to 10).map(i ⇒ f(i + 1)) }`

In a future version of `Chymyst`, the unblocking transformation may be performed by macros as an automatic optimization.

## The unblocking transformation and continuations

The unblocking transformation is closely related to programming in the continuation-passing style (CPS).

Let us convert to CPS a code snippet we used to illustrate the unblocking transformation.

The initial code snippet:

```scala
// Reaction that emits a blocking molecule.
val blockingMol = b[T, R]
go { case ... ⇒
  /* start of reaction body 1, defines `a`, `b`, ... */
  val t: T = ???
  val x: R = blockingMol(t)
  /* continuation of reaction body 1, can use `x`, `a`, `b`, `t`, ... */
}

// Reaction that consumes the blocking molecule.
go { case blockingMol(t, reply) + ... ⇒
  /* start of reaction body 2, uses `t`, defines `x` */
  val x: R = ???
  reply(x)
  /* rest of reaction body 2 */
}

```

The same code after CPS transformation:

```scala
// Reaction that emits a non-blocking molecule.
// The molecule now carries a continuation.
val nonBlockingMol = m[(T, R ⇒ Unit)]
go { case ... ⇒
  /* start of reaction body 1, defines `a`, `b`, ... */
  val t: T = ???
  val cont = { x : R ⇒
    /* continuation of reaction body 1, can use `x`, `a`, `b`, `t`, ... */
    }
  nonBlockingMol((t, cont))
}

// Reaction that consumes the non-blocking molecule.
go { case nonBlockingMol((t, cont)) + ... ⇒
  /* start of reaction body 2, uses `t`, defines `x` */
  val x: R = ???
  cont(x) // invoke continuation
  /* rest of reaction body 2 */
}

```

An important difference is that the continuation is invoked in the same process as reaction body 2.
In other words, the continuation is not executing concurrently with the rest of reaction body 2.
This loss of concurrency does not happen when using the unblocking transformation or with the original code that uses blocking molecules.

# Molecules and reactions in local scopes

Since molecules and reactions are local values, they are lexically scoped within the block where they are defined.
If we define molecules and reactions in the scope of an auxiliary function, or in the scope of a reaction body, these newly defined molecules and reactions will be encapsulated and protected from outside access.

We already saw one use of this feature for the unblocking transformation, where we defined a new reaction site nested within the scope of a reaction.
To further illustrate this feature of the chemical paradigm, let us implement a function that encapsulates an “asynchronous counter” and initializes it with a given value.

Our first implementation of the asynchronous counter has a drawback: The molecule `counter(n)` must be emitted by the user and remains globally visible.
If the user emits two copies of `counter()` with different values, the `counter + decr` and `counter + fetch` reactions will work unreliably, choosing between the two copies of `counter()` non-deterministically.
In order to guarantee reliable functionality, we would like to emit exactly one copy of `counter()` and then prevent the user from emitting any further copies of that molecule.

A solution is to define `counter` and its reactions within a function that returns the `decr` and `fetch` emitters to the outside scope.
The `counter` emitter _will not_ be returned to the outside scope, and so the user will not be able to emit extra copies of that molecule.

```scala
def makeCounter(initCount: Int): (M[Unit], B[Unit, Int]) = {
  val counter = m[Int]
  val decr = m[Unit]
  val fetch = m[Unit, Int]

  site(
    go { counter(n) + fetch(_, r) ⇒ counter(n); r(n)},
    go { counter(n) + decr(_) ⇒ counter(n - 1) }
  )
  // emit exactly one copy of `counter`
  counter(initCount)

  // return only these two emitters to the outside scope
  (decr, fetch)
}

```

The function scope creates the emitter for the `counter()` molecule and emits a single copy of that molecule.
Users from other scopes cannot emit another copy of `counter()` since the emitter is not visible outside the function scope.
In this way, it is guaranteed that one and only one copy of `counter()` will be emitted into the soup.

Nevertheless, the users obtain the emitters `decr` and `fetch` from the function.
So the users can emit these molecules and start the corresponding reactions.

The function `makeCounter()` can be called like this:

```scala
val (d, f) = makeCounter(10000)
d() + d() + d() // emit 3 decrement molecules
val x = f() // fetch the current value

```

Also note that each invocation of `makeCounter()` will create new, fresh emitters `counter`, `decr`, and `fetch`, because each invocation will create a fresh local scope and a new reaction site.
In this way, the user can create as many independent counters as desired.
Molecules defined in the scope of a certain invocation of `makeCounter()` will be _chemically different_ from molecules defined in other invocations,
since they will be bound to the particular reaction site defined during the same invocation of `makeCounter()`.

This example shows how we can encapsulate some molecules and yet use their reactions.
A function scope can define local reactions with several input molecules, emit some of these molecules initially, and return some (but not all) molecule emitters to the outer scope.

## Example: implementing “First Result”

The “First Result” operation solves the following problem.
Suppose we have two blocking molecules `f()` and `g()` that return a reply value.
We would like to emit both `f()` and `g()` together and wait until a reply value is received from whichever molecule unblocks sooner.
If the other molecule gets a reply value later, we will just ignore that value.

The result of this non-deterministic operation is the value of type `T` obtained from one of the molecules `f` and `g`, depending on which molecule got its reply first.

Let us now implement this operation in `Chymyst`.
We will derive the required chemistry by reasoning about the behavior of molecules.

The task is to define a blocking molecule emitter `firstReply` that will unblock when `f` or `g` unblocks, whichever happens first.
Let us assume for simplicity that both `f()` and `g()` return values of type `Int`.

It is clear that we need to emit both `f()` and `g()` concurrently.
But we cannot do this from one reaction, since `f()` will block and prevent us from emitting `g()`.
Therefore we need two different reactions, one emitting `f()` and another emitting `g()`.

These two reactions need some input molecules.
These input molecules cannot be `f` and `g` since these two molecules are given to us, their chemistry is already fixed, and so we cannot add new reactions that consume them.
Therefore, we need at least one new molecule that will be consumed to start these two reactions.
However, if we declare the two reactions as `c → f` and `c → g` and then emit two copies of `c()`, we are not guaranteed that both reactions will start.
It is possible that two copies of the first reaction or two copies of the second reaction are started instead.
In other words, there will be an _unavoidable indeterminism_ in our chemistry.

`Chymyst` will in fact detect this problem and generate an error:

```scala
val c = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
site(
    go { case c(_) ⇒ val x = f(); ??? },
    go { case c(_) ⇒ val x = g(); ??? }
)

```
`java.lang.Exception: In Site{c → ...; c → ...}: Unavoidable indeterminism:`
`reaction {c → } is shadowed by {c → }`

So, we need to define two _different_ molecules (say, `c` and `d`) as inputs for these two reactions.

```scala
val c = m[Unit]
val d = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
site(
    go { case c(_) ⇒ val x = f(); ??? },
    go { case d(_) ⇒ val x = g(); ??? }
)
c() + d()

```

After we have emitted both `c()` and `d()`, both reactions will start concurrently.
When one of them finishes and gets a reply value `x`, we would like to use that value for replying to our new blocking molecule `firstResult`.

Can we reply to `firstResult` in the same reactions? This would require us to make `firstResult` an input molecule in each of these reactions.
However, we will have only one copy of `firstResult` emitted.
Having `firstResult` as input will therefore prevent both reactions from proceeding concurrently.

Therefore, there must be some _other_ reaction that consumes `firstResult` and replies to it.
This reaction must have the form

```scala
go { case firstResult(_, reply) + ??? ⇒ reply(x) }

```

In this reaction, we somehow need to obtain the value `x` that we will reply with.
So we need a new auxiliary molecule, say `done(x)`, that will carry `x` on itself.
The reaction with `firstResult` will then have the form

```scala
go { case firstResult(_, reply) + done(x) ⇒ reply(x) }

```

Now it is clear that the value `x` should be emitted on `done(x)` in both of the `c → ...` and `d → ...` reactions.
The complete program looks like this:

```scala
val firstResult = b[Unit, Int]
val c = m[Unit]
val d = m[Unit]
val f = b[Unit, Int]
val g = b[Unit, Int]
val done = m[Int]

site(
  go { case c(_) ⇒ val x = f(); done(x) },
  go { case d(_) ⇒ val x = g(); done(x) }
)
site(
  go { case firstResult(_, r) + done(x) ⇒ r(x) }
)

c() + d()
val result = firstResult()

```

### Encapsulating chemistry in a function

The code as written works but is not encapsulated — in this code, we define new molecules and new chemistry at top level.
To remedy this, we can create a function that will return the `firstResult` emitter, given the emitters `f` and `g`.

The idea is to define new chemistry in the function's _local scope_ and return a new molecule emitter.
Let us make this function generic by using a type parameter `T` for the result value of the given blocking molecules `f` and `g`.
The code then looks like this:

```scala
def makeFirstResult[T](f: B[Unit, T], g: B[Unit, T]): B[Unit, T] = {
    // The same code as above, but now in a local scope.
    val firstResult = b[Unit, T]
    val c = m[Unit]
    val d = m[Unit]
    val f = b[Unit, T]
    val g = b[Unit, T]
    val done = m[T]

    site(
      go { case c(_) ⇒ val x = f(); done(x) },
      go { case d(_) ⇒ val x = g(); done(x) }
    )
    site(
      go { case firstResult(_, r) + done(x) ⇒ r(x) }
    )

    c() + d()

    // Return only the `firstResult` emitter:
    firstResult
}

```

The main advantage of this code is to encapsulate all the auxiliary molecule emitters within the local scope of the method `makeFirstResult()`.
Only the `firstResult` emitter is returned;
this is the only emitter that users of `makeFirstResult()` need.
The other emitters (`c`, `d`, and `done`) are invisible to the users because they are local variables in the scope of `makeFirstResult`.
Now the users of `makeFirstResult()` cannot inadvertently break the chemistry by emitting some further copies of `c`, `d`, or `done`.
This is the hallmark of a successful encapsulation.

## Example: Parallel Or

We will now consider a task called “Parallel Or”; this is somewhat similar to the “First Result” task considered above.

The goal is to create a new blocking molecule `parallelOr` from two given blocking molecules `f` and `g` that return a reply value of `Boolean` type.
We would like to emit both `f` and `g` together and wait until a reply value is received.
The `parallelOr` should reply with `true` as soon as one of the blocking molecules `f` and `g` returns `true`.
If both `f` and `g` return `false` then `parallelOr` will also return `false`.
If both molecules `f` and `g` block (or one of them returns `false` while the other blocks) then `parallelOr` will block as well.

We will now implement this operation in `Chymyst`.
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
  go { case c(_) ⇒ val x = f(); ??? },
  go { case d(_) ⇒ val y = g(); ??? }
)
c() + d()

```

Now, the Boolean values `x` and `y` could appear in any order (or not at all).
We need to implement the logic of receiving these values and computing the final result value.
This final result must be carried by some molecule, say `result()`.
We should keep track of whether we already received both `x` and `y`, or just one of them.

When we receive a `true` value, we are done.
So, we only need to keep track of the number of `false` values received.
Therefore, let us define `result()` with integer value that shows how many intermediate `false` results we already received.

The next step is to communicate the values of `x` and `y` to the reaction that updates the `result()`'s value.
For this, we can use a non-blocking molecule `done()` and write reactions like this:

```scala
site(
  go { case c(_) ⇒ val x = f(); done(x) },
  go { case d(_) ⇒ val y = g(); done(y) },
  go { case result(n) + done(x) ⇒ result(n + 1) }
)
c() + d() + result(0)

```

The effect of this chemistry is that `result()`'s value will get incremented whenever one of `f` or `g` returns.
This is not yet what we need, but we are getting closer.

What remains is to implement the required logic:
When `result` receives a `true` value, it should return `true` as a final answer, regardless of how many answers it received.
When `result` receives a `false` value twice, it should return `false` as a final answer.
Otherwise, there is no final answer yet.

We are required to deliver the final answer as a reply to the `parallelOr()` molecule.
It is clear that `parallelOr` cannot be reacting with the `result()` molecule — this would prevent the `result + done → result` reaction from running.
Therefore, `parallelOr` needs to react with _another_ auxiliary molecule, say `finalResult().
There is only one way of defining this kind of reaction,

```scala
go { case parallelOr(_, r) + finalResult(x) ⇒ r(x) }

```

Now it is clear that the problem will be solved if we emit `finalResult` only when we actually have the final answer.
This can be done from the `result + done → result` reaction, which we modify as follows:

```scala
go { case result(n) + done(x) ⇒
        if (x == true) finalResult(true)
        else if (n == 1) finalResult(false)
        else result(n + 1)
   }

```

To make the chemistry clearer, we may rewrite this reaction as three reactions with conditional pattern matching:

```scala
site(
  go { case result(1) + done(false) ⇒ finalResult(false) },
  go { case result(0) + done(false) ⇒ result(1) },
  go { case result(_) + done(true)  ⇒ finalResult(true) }
)

```

Here is the complete code for `parallelOr`, where we have separated the reactions into three independent reaction sites.

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
  go { case parallelOr(_, r) + finalResult(x) ⇒ r(x) }
)
site(
  go { case result(1) + done(false) ⇒ finalResult(false) },
  go { case result(0) + done(false) ⇒ result(1) },
  go { case result(_) + done(true)  ⇒ finalResult(true) }
)
site(
  go { case c(_) ⇒ val x = f(); done(x) },
  go { case d(_) ⇒ val y = g(); done(y) }
)

c() + d() + result(0)

```

### Exercises

1. Encapsulate the `parallelOr` operation into a function.
2. Implement `parallelOr` for _three_ blocking emitters (say, `f`, `g`, `h`).
The `parallelOr()` call should return `true` whenever one of these emitters returns `true`; it should return `false` if _all_ of them return `false`; and it should block otherwise.

## Constraints on blocking molecules

### No blocking at the end of reaction body

A reaction may not emit a blocking molecule as the last expression it computes.

```scala
val f = b[Unit, Int]
site( go { case ... ⇒ ...; f() } ) // the last expression is f()
// compile-time error: "Blocking molecules must not 
// be emitted last in a reaction"

```

This code is considered to be a programmer's design error because blocking just before the end of the reaction and throwing away the received reply value is most likely useless.
The blocking molecule `f()` should probably be replaced by a non-blocking molecule.
If, for some reason, the blocking molecule is required as the last expression,
writing `f(); ()` will make the emitter call `f()` acceptable to `Chymyst` since it is no longer the last expression computed by the reaction. 

### One reply for each blocking molecule

Each blocking molecule must receive one (and only one) reply.
It is an error if a reaction consumes a blocking molecule but does not reply.
It is also an error to reply a second time, after one reply was made.

Errors of this type are caught at compile time:

```scala
val f = b[Unit, Int]
val c = m[Int]
site( go { case f(_, r) + c(n) ⇒ c(n + 1) } ) // forgot to reply!
// compile-time error: "blocking input molecules should receive a reply
// but no unconditional reply found"

site( go { case f(_, r) + c(n) ⇒ c(n + 1); r(n); r(n) } ) // replied twice!
// compile-time error: "blocking input molecules should receive one reply
// but possibly multiple replies found"

```

The reply could depend on a run-time condition, which is impossible to evaluate at compile time.
In this case, a reaction must use an `if` expression that calls the reply emitter in _each_ branch.
It is an error if a reply is only emitted in one of the `if` branches:

```scala
val f = b[Unit, Int]
val c = m[Int]
site( go { case f(_, r) + c(n) ⇒ c(n + 1); if (n != 0) r(n) } )
// compile-time error: "blocking input molecules should receive a reply
// but no unconditional reply found"

```

A correct reaction could look like this:

```scala
val f = b[Unit, Int]
val c = m[Int]
site(
  go { case f(_, r) + c(n) ⇒
    // reply is always sent, regardless of the value of `n`
    c(n + 1); if (n != 0) r(n) else r(0)
  }
)

```

It is a compile-time error to use a reply emitter inside a loop, inside any function block, or, more generally, in any **non-linear** context.
(A context is "linear" if it is guaranteed to get evaluated exactly once.)

It is also an error to create an alias for a reply emitter or to pass it as an argument to functions.

All these restrictions are in place to ensure statically (at compile time) that a reply emitter will be called exactly once for each blocking molecule consumed by a reaction.

```scala
val f = b[Unit, Int]
site( go { case f(_, r) ⇒ val x = r; q(x) } )
// compile-time error: "Reaction body must not use reply emitters inside function blocks"

site( go { case f(_, r) ⇒ try { throw ...; r(1) } catch {...} } )
// compile-time error: "Reaction body must not use reply emitters inside function blocks"

site( go { case f(_, r) ⇒ if (r(1)) ... } ) // OK, the condition for `if` is evaluated exactly once

```

### Avoid throwing exceptions in reaction body

A reaction's body could throw an exception before emitting a reply.
In this case, compile-time analysis will not show that there is a problem
because it is not possible to detect at compile time whether a portion of Scala code throws an exception.
Nevertheless, the chemical machine will recognize at run time that the reaction body has finished without sending a reply to one or more blocking molecules.
The chemical machine will then log an error message, specifying the molecules that are still waiting for replies.

Here is an example of code that emits `f()` and waits for reply, while a reaction consuming `f()` can throw an exception before replying:

```scala
val f = b[Unit, Int]
val c = m[Int]
site( go { case f(_, r) + c(n) ⇒ c(n + 1); if (n == 0) throw new Exception("Bad value of n!"); r(n) } )
c(0)
f()

```

Running this code will print an error message to the console:

```
In Site{c + f/B → ...}: Reaction {c + f/B → ...} finished without replying
to f/B. Reported error: Bad value of n!

```

In general, a reaction can consume one or more blocking molecules and fail to reply to some of them due to an exception.
Thus, there can be one or more blocked threads still waiting for replies when this kind of situation occurs.
The runtime engine cannot know whether it is desirable to unblock these threads.

In our example, the exception will be thrown only when the reaction starts and the condition `n == 0` is evaluated to `true`.
It may not be easy to design unit tests for this condition.

Another difficulty is that exceptions thrown in concurrent threads will be invisible in calling threads except for error messages on the console.

For these reasons, it is not easy to catch errors of this type, either at compile time or at run time.

To avoid these problems, it is advisable to design reactions in such a way that each reply is guaranteed to be sent exactly once,
and that no exceptions can be thrown before sending the reply.
If a condition needs to be checked before sending a reply, it should be a simple condition that is guaranteed not to throw an exception.

## Example: map/reduce with blocking wait

In the previous chapter, we have seen the following code for the ordered map/reduce problem:

```scala
val reduceAll = m[(Array[T], M[T])]
site(
 go { case reduceAll((arr, res)) ⇒
  if (arr.length == 1) res(arr(0))
  else  {
    val (arr0, arr1) = arr.splitAt(arr.length / 2)
    val a0 = m[T]
    val a1 = m[T]
    site( go { case a0(x) + a1(y) ⇒ res(reduceB(x, y)) } )
    reduceAll((arr0, a0)) + reduceAll((arr1, a1))
  }
 }
)
// start the computation:
val result = m[T]
val array: Array[T] = ... // create the initial array
reduceAll((array, result)) // start the computation
// The result() molecule will be emitted with the final result.

```

We will now rewrite this code so that it waits until the entire computation is finished.

Presently, the molecule `result()` is emitted as the indication that the computation is done.
We can easily introduce a blocking molecule `waitResult()` with a reaction that requires the `result()` molecule as another input:

```scala
val waitResult = B[Unit, T]

go { case waitResult(_, r) + result(x) ⇒ r(x) }

```

If we now emit `waitResult()`, it will block until its reaction can start, which will happen only when the map/reduce job is done. 
At that time, the final result of the computation, `x`, will be sent via the reply emitter `r()` to the process that emitted `waitResult()`.

We can now encapsulate the code as a (blocking) function call:

```
def doReduce[T](array: Array[T], reduceB: (T, T) ⇒ T): T = {
  val result = m[T]
  val waitResult = B[Unit, T]
  
  site( go { case waitResult(_, r) + result(x) ⇒ r(x) } )
  
  val reduceAll = m[(Array[T], M[T])]
  
  site(
   go { case reduceAll((arr, res)) ⇒
    if (arr.length == 1) res(arr(0))
    else  {
      val (arr0, arr1) = arr.splitAt(arr.length / 2)
      val a0 = m[T]
      val a1 = m[T]
      site( go { case a0(x) + a1(y) ⇒ res(reduceB(x, y)) } )
      reduceAll((arr0, a0)) + reduceAll((arr1, a1))
    }
   }
  )
  reduceAll((array, result)) // start the computation
  waitResult() // wait until finished and return the value.
}

```
