<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Quick start

`Chymyst Core` implements a declarative DSL for purely functional concurrency in Scala.
The DSL is based on the "chemical machine" paradigm, which is unfamiliar to most readers. 

This chapter is for the impatient readers who want to dive straight into the code, with very few explanations.

Read the next chapter if you prefer to understand the concepts before looking at code.

## Setup

First, declare this library dependency in your `build.sbt`:

```scala
libraryDependencies += "io.chymyst" %% "chymyst-core" % "latest.integration"

```

The `Chymyst Core` DSL becomes available once you add this statement:

```scala
import io.chymyst.jc._

```

This imports all the necessary symbols such as `m`, `b`, `site`, `go` and so on.

## Async processes and async values

In the chemical machine, an asynchronous concurrent process (for short, an **async process**) is implemented as a computation that works with a special kind of data called **async values**.
An async process can consume one or more input async values and may emit (zero or more) output async values.

An async process must be declared using the `go { }` syntax.
In order to activate one or more async processes, use the `site()` call.

Async values are created out of ordinary values by calling special **emitters**.
Input and output async value emitters need to be declared separately using the special syntax `m[T]` where `T` is the type of the value:

```scala
scala> import io.chymyst.jc._
import io.chymyst.jc._

scala> val in = m[Int] // emitter for async value of type `Int`
in: io.chymyst.jc.M[Int] = in

scala> val result = m[Int] // emitter for async value of type `String`
result: io.chymyst.jc.M[Int] = result

scala> site(
     |   go { case in(x) ⇒
     |     // compute some new value using x
     |     val z = x * 2
     |     result(z) // emit z as the `result` async value
     |   },
     |   go { case result(x) ⇒ println(x) }
     | )
res0: io.chymyst.jc.WarningsAndErrors = In Site{in → ...; result → ...}: no warnings or errors

scala> // emit some async values for input
     | in(123); in(124); in(125)

scala> Thread.sleep(200) // wait for async processes
248
250
246
```

An async process can depend on _several_ async input values at once, and may emit several async values as output.
The process will start when all its input async values are available (have been emitted but not yet consumed).

```scala
scala> import io.chymyst.jc._
import io.chymyst.jc._

scala> val in1 = m[Int] // async value 1
in1: io.chymyst.jc.M[Int] = in1

scala> val in2 = m[Int] // async value 2
in2: io.chymyst.jc.M[Int] = in2

scala> val result = m[Boolean] // async value of type `Boolean`
result: io.chymyst.jc.M[Boolean] = result

scala> site(
     |   go { case in1(x) + in2(y) ⇒
     |     // debug
     |     println(s"got x = $x, y = $y")
     |     // compute output value
     |     val z : Boolean = x != y // whatever
     |     result(z) // emit `result` async value
     |     val t : Boolean = x > y // whatever
     |     result(t) // emit another `result` value
     |     println(s"emitted result($z) and result($t)")
     |   },
     |   go { case result(x) ⇒ println(s"got result = $x") }
     | )
res4: io.chymyst.jc.WarningsAndErrors = In Site{in1 + in2 → ...; result → ...}: no warnings or errors

scala> in1(100)

scala> in2(200) // emit async values for input

scala> Thread.sleep(200) // wait for async processes
got x = 100, y = 200
emitted result(true) and result(false)
got result = true
got result = false
```

Emitting an async value is a _non-blocking_ operation; execution continues immediately, without waiting for new async processes to start.
Async processes that consume the async input data will start later, _concurrently_ with the processes that emitted their async input data.

Async data can be of any type (but the type is fixed by the declared emitter type).
For example, an async value can be of function type, which allows us to implement asynchronous _continuations_:

```scala
scala> import io.chymyst.jc._
import io.chymyst.jc._

scala> val in = m[Int] // async input value
in: io.chymyst.jc.M[Int] = in

scala> val cont = m[Int ⇒ Unit] // continuation with side effect
cont: io.chymyst.jc.M[Int => Unit] = cont

scala> site(
     |   go { case in(x) + cont(k) ⇒
     |     // debug
     |     println(s"got x = $x")
     |     // compute output value and continue
     |     val z : Int = x * x // whatever
     |     k(z) // invoke continuation
     |   }
     | )
res8: io.chymyst.jc.WarningsAndErrors = In Site{cont + in → ...}: no warnings or errors

scala> in(100) // emit async value for input

scala> cont(i ⇒ println(s"computed result = $i")) // emit the second async value for input

scala> Thread.sleep(200)
got x = 100
computed result = 10000
```

New async processes and async values can be defined anywhere in the code,
including within a function scope, or within the scope of an async process.

## Example: Concurrent counter

### Non-blocking read access

We implement a counter that can be incremented and whose value can be read.
Both the increment and the read operations are concurrent and non-blocking.
The read operation is implemented as an async continuation.

```scala
scala> import io.chymyst.jc._
import io.chymyst.jc._

scala> val counter = m[Int]
counter: io.chymyst.jc.M[Int] = counter

scala> val incr = m[Unit] // `increment` operation
incr: io.chymyst.jc.M[Unit] = incr

scala> val read = m[Int ⇒ Unit] // continuation for the `read` operation
read: io.chymyst.jc.M[Int => Unit] = read

scala> site(
     |   go { case counter(x) + incr(_) ⇒ counter(x + 1) },
     |   go { case counter(x) + read(cont) ⇒
     |     counter(x) // emit x again as async `counter` value
     |     cont(x) // invoke continuation
     |   } 
     | )
res12: io.chymyst.jc.WarningsAndErrors = In Site{counter + incr → ...; counter + read → ...}: no warnings or errors

scala> counter(0) // set initial value of `counter` to 0

scala> incr() // emit a `Unit` async value

scala> incr() // this can be called from any concurrently running code

scala> read(i ⇒ println(s"counter = $i")) // this too

scala> Thread.sleep(200)
counter = 2
```

An async value can be consumed only by one async process.
For this reason, there is no race condition when running this program,
even if several copies of the async values `incr()` and `read()` are emitted from several concurrent processes.

### Non-blocking wait until done

We now implement a counter that is incremented until some condition is met.
At that point, we would like to start another computation that uses the last obtained counter value.

```scala
scala> import io.chymyst.jc._
import io.chymyst.jc._

scala> val counter = m[Int]
counter: io.chymyst.jc.M[Int] = counter

scala> val done = m[Int] // signal the end of counting
done: io.chymyst.jc.M[Int] = done

scala> val next = m[Int ⇒ Unit] // continuation
next: io.chymyst.jc.M[Int => Unit] = next

scala> val incr = m[Unit] // `increment` operation
incr: io.chymyst.jc.M[Unit] = incr

scala>  // The condition we are waiting for, for example:
     | def areWeDone(x: Int): Boolean = x > 1
areWeDone: (x: Int)Boolean

scala> site(
     |   go { case counter(x) + incr(_) ⇒
     |     val newX = x + 1
     |     if (areWeDone(newX)) done(newX)
     |     else counter(newX) 
     |   },
     |   go { case done(x) + next(cont) ⇒
     |     cont(x) // invoke continuation on the value `x`
     |   }
     | )
res19: io.chymyst.jc.WarningsAndErrors = In Site{counter + incr → ...; done + next → ...}: no warnings or errors

scala> counter(0) // set initial value of `counter` to 0

scala> incr() // Emit a `Unit` async value.

scala> incr() // This can be called from any concurrent process.

scala> next { x ⇒
     | // Continue the computation, having obtained `x`.
     |   println(s"counter = $x")
     | // more code...
     | }

scala> Thread.sleep(200)
counter = 2
```

More code can follow `println()`, but it will be constrained to the scope of the closure under `next()`.

## Blocking channels

In the previous example, we used a continuation in order to wait until some condition is satisfied.
`Chymyst` implements this often-used pattern via a special emitter called a **blocking channel**.
Using this feature, the previous code can be rewritten more concisely:

```scala
scala> import io.chymyst.jc._
import io.chymyst.jc._

scala> val counter = m[Int]
counter: io.chymyst.jc.M[Int] = counter

scala> val done = m[Int] // signal the end of counting
done: io.chymyst.jc.M[Int] = done

scala> val next = b[Unit, Int] // blocking reply channel with integer reply value
next: io.chymyst.jc.B[Unit,Int] = next/B

scala> val incr = m[Unit] // `increment` operation
incr: io.chymyst.jc.M[Unit] = incr

scala>  // the condition we are waiting for, for example:
     | def areWeDone(x: Int): Boolean = x > 1
areWeDone: (x: Int)Boolean

scala> site(
     |   go { case counter(x) + incr(_) ⇒
     |     val newX = x + 1
     |     if (areWeDone(newX)) done(newX)
     |     else counter(newX) 
     |   },
     |   go { case done(x) + next(_, reply) ⇒
     |     reply(x) // emit reply with integer value `x`
     |   }
     | )
res26: io.chymyst.jc.WarningsAndErrors = In Site{counter + incr → ...; done + next/B → ...}: no warnings or errors

scala> counter(0) // set initial value of `counter` to 0

scala> incr() + incr() // same as `incr(); incr()`

scala> val x = next() // block until reply is sent
x: Int = 2
```

More code can follow `println()`, and that code can use `x` and is no longer constrained to the scope of a closure, as before.

Blocking channels are declared using the `b[T, R]` syntax, where `T` is the type of async value they carry and `R` is the type of their **reply value**.

### Concurrent counter: blocking read access

We can use the blocking channel feature to implement blocking access to the counter's current value.

```scala
scala> import io.chymyst.jc._
import io.chymyst.jc._

scala> val counter = m[Int]
counter: io.chymyst.jc.M[Int] = counter

scala> val read = b[Unit, Int] // read via blocking channel
read: io.chymyst.jc.B[Unit,Int] = read/B

scala> val incr = m[Unit] // `increment` operation
incr: io.chymyst.jc.M[Unit] = incr

scala> site(
     |   go { case counter(x) + incr(_) ⇒ counter(x + 1) },
     |   go { case counter(x) + read(_, reply) ⇒
     |     counter(x) // emit x again as async `counter` value
     |     reply(x) // emit reply with value `x`
     |   } 
     | )
res29: io.chymyst.jc.WarningsAndErrors = In Site{counter + incr → ...; counter + read/B → ...}: no warnings or errors

scala> counter(0) // set initial value of `counter` to 0

scala> incr()

scala> incr() // these emitter calls do not block

scala> val x = read() // block until reply is sent
x: Int = 2
```

## Parallel `map`

We now implement the parallel `map` operation: apply a function to every element of a list,
and produce a list of results.

The concurrent counter is used to keep track of progress.
For simplicity, we will aggregate results into the final list in the order they are computed.
An async value `done()` is emitted when the entire list is processed.
Also, a blocking channel `waitDone()` is used to wait for the completion of the job. 

```scala
scala> import io.chymyst.jc._
import io.chymyst.jc._

scala> val start = m[Int] // async value for a list element
start: io.chymyst.jc.M[Int] = start

scala> def f(x: Int): Int = x * x // some computation
f: (x: Int)Int

scala> val total = 10
total: Int = 10

scala> val counter = m[Int]
counter: io.chymyst.jc.M[Int] = counter

scala> val incr = m[Unit]
incr: io.chymyst.jc.M[Unit] = incr

scala> val result = m[List[Int]]
result: io.chymyst.jc.M[List[Int]] = result

scala> val done = m[Unit] // signal the end of computation
done: io.chymyst.jc.M[Unit] = done

scala> val waitDone = b[Unit, List[Int]] // blocking channel
waitDone: io.chymyst.jc.B[Unit,List[Int]] = waitDone/B

scala> site(
     |   go { case start(i) + result(xs) ⇒
     |     val newXs = f(i) :: xs // compute i-th element concurrently and append
     |     result(newXs)
     |     incr()
     |   },
     |   go { case incr(_) + counter(n) ⇒
     |     val newN = n + 1
     |     if (newN == total) done()
     |     else counter(newN)
     |   },
     |   go { case done(_) + waitDone(_, reply) + result(xs) ⇒ reply(xs) }
     | )
res33: io.chymyst.jc.WarningsAndErrors = In Site{counter + incr → ...; done + result + waitDone/B → ...; result + start → ...}: no warnings or errors

scala> // emit initial values
     | (1 to total).foreach(i ⇒ start(i))

scala> counter(0)

scala> result(Nil)

scala> waitDone() // block until done, get result
res38: List[Int] = List(100, 81, 64, 49, 36, 25, 16, 9, 4, 1)
```

# Envoi

In the rest of this book, async values are called **non-blocking molecules**, blocking channels are called **blocking molecules**, and async processes are called **reactions**.
