<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Quick start

`Chymyst Core` implements a declarative DSL for purely functional concurrency in Scala.
The DSL is based on the "chemical machine" paradigm, which is likely unfamiliar to most readers. 

This chapter is for the impatient readers who want to dive straight into the code, with very few explanations.

Read the next chapter if you prefer to understand the concepts before looking at code.

## Setup

First, declare this library dependency in your `build.sbt`:

```scala
libraryDependencies += "io.chymyst" %% "chymyst-core" % "latest.integration"

```

The `Chymyst Core` DSL becomes available once you add this statement:

```scala
scala> import io.chymyst.jc._
import io.chymyst.jc._
```

This imports all the necessary symbols such as `m`, `b`, `site`, `go` and so on.

## Async processes and async values

In the chemical machine, an asynchronous concurrent process (for short, an **async process**) is implemented as a computation that works with a special kind of data called **async values**.
An async process can consume one or more input async value and may emit (zero or more) new async values.

Async values are created out of ordinary values by calling special **async emitters**.
All async emitters must be declared before using them.
A new async emitter is created using the special syntax `m[T]`, where `T` is the type of the value:

```scala
scala> val in = m[Int] // emitter for async value of type `Int`
in: io.chymyst.jc.M[Int] = in

scala> val result = m[Int] // emitter for async value of type `String`
result: io.chymyst.jc.M[Int] = result
```

An async process must be declared using the `go { }` syntax.
In order to activate one or more async processes, use the `site()` call.

```scala
scala> site(
     |   go { case in(x) ⇒     // consume an async value `in(...)`
     |     val z = x * 2       // compute some new value using x
     |     result(z)           // emit a new async value `result(z)`
     |   },
     |   go { case result(x) ⇒ println(x) } // consume `result(...)`
     | )
res0: io.chymyst.jc.WarningsAndErrors = In Site{in → ...; result → ...}: no warnings or errors

scala> in(123); in(124); in(125)   // emit some async values for input

scala> Thread.sleep(200) // wait for async processes to start
250
248
246
```

An async process can depend on _several_ async input values at once, and may emit several async values as output.
The process will start only when _all_ its input async values are available (have been emitted but not yet consumed).

```scala
scala> val in1 = m[Int] // async value 1
in1: io.chymyst.jc.M[Int] = in1

scala> val in2 = m[Int] // async value 2
in2: io.chymyst.jc.M[Int] = in2

scala> val result = m[Boolean] // async value of type `Boolean`
result: io.chymyst.jc.M[Boolean] = result

scala> site(
     |   go { case in1(x) + in2(y) ⇒   // wait for two async values
     |     println(s"got x = $x, y = $y")  // debug
     |     val z: Boolean = x != y // compute some output value
     |     result(z) // emit `result` async value
     |     val t: Boolean = x > y // whatever
     |     result(t) // emit another `result` value
     |     println(s"emitted result($z) and result($t)")
     |   },
     |   go { case result(x) ⇒ println(s"got result = $x") }
     | )
res3: io.chymyst.jc.WarningsAndErrors = In Site{in1 + in2 → ...; result → ...}: no warnings or errors

scala> in2(20)

scala> in1(10) // emit async values for input

scala> Thread.sleep(200) // wait for async processes
got x = 10, y = 20
emitted result(true) and result(false)
got result = true
got result = false
```

Emitting an async value is a _non-blocking_ operation; execution continues immediately, without waiting for new async processes to start.
Async processes that consume the async input data will start later, _concurrently_ with the processes that emitted their async input data.

Async data can be of any type (but the type is fixed by the declared emitter type).
For example, an async value can be of function type, which allows us to implement asynchronous _continuations_:

```scala
scala> val in = m[Int] // async input value
in: io.chymyst.jc.M[Int] = in

scala> val cont = m[Int ⇒ Unit]  // continuation
cont: io.chymyst.jc.M[Int => Unit] = cont

scala> site(
     |   go { case in(x) + cont(k) ⇒
     |     println(s"got x = $x")
     |     val z : Int = x * x   // compute some output value
     |     k(z) // invoke continuation
     |   }
     | )
res7: io.chymyst.jc.WarningsAndErrors = In Site{cont + in → ...}: no warnings or errors

scala> in(100) // emit async value for input

scala> // emit the second async value for input
     | cont(i ⇒ println(s"computed result = $i"))

scala> Thread.sleep(200)
got x = 100
computed result = 10000
```

New async processes and async values can be defined anywhere in the code,
for instance, within a function scope or within the scope of an async process.

## Example: Asynchronous counter

### Non-blocking read access

We implement a counter that can be incremented and whose value can be read.
Both the increment and the read operations are asynchronous (non-blocking).
The read operation is implemented as an async continuation.

```scala
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

An async value can be consumed only by _one_ async process.
For this reason, there is no race condition when running this program,
even if several copies of the async values `incr()` and `read()` are emitted from several concurrent processes.

### Non-blocking wait until done

We now implement a counter that is incremented until some condition is met.
At that point, we would like to start another computation that uses the last obtained counter value.

```scala
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

## Blocking emitters

In the previous example, we used a continuation in order to wait until some condition is satisfied.
`Chymyst` implements this often-used pattern via special emitters called **blocking emitters**.
Using this feature, the previous code can be rewritten more concisely:

```scala
scala> val counter = m[Int]
counter: io.chymyst.jc.M[Int] = counter

scala> val done = m[Int] // signal the end of counting
done: io.chymyst.jc.M[Int] = done

scala> val next = b[Unit, Int] // blocking emitter with integer reply value
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

Blocking emitters are declared using the `b[T, R]` syntax, where `T` is the type of async value they carry and `R` is the type of their **reply value**.

### Asynchronous counter: blocking read access

We can use a blocking emiter to implement blocking access to the counter's current value.

```scala
scala> val counter = m[Int]
counter: io.chymyst.jc.M[Int] = counter

scala> val read = b[Unit, Int] // `read` is a blocking emitter
read: io.chymyst.jc.B[Unit,Int] = read/B

scala> val incr = m[Unit] // `increment` operation is asynchronous
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

We now implement a parallel `map` operation: apply a function to every element of a list,
and produce a list of results.

An asynchronous counter is used to keep track of progress.
For simplicity, we will aggregate results into the final list in the order they are computed.
An async value `done()` is emitted when the entire list is processed.
Also, a blocking emitter `waitDone` is used to wait for the completion of the job. 

```scala
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

scala> val waitDone = b[Unit, List[Int]] // blocking emitter
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

In the rest of this book, async values are called **non-blocking molecules**
and async processes are called **reactions**.
