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

```tut
import io.chymyst.jc._

```

This imports all the necessary symbols such as `m`, `b`, `site`, `go` and so on.

## Async processes and async values

In the chemical machine, an asynchronous concurrent process (for short, an **async process**) is implemented as a computation that works with a special kind of data called **async values**.
An async process can consume one or more input async value and may emit (zero or more) new async values.

Async values are created out of ordinary values by calling special **async emitters**.
All async emitters must be declared before using them.
A new async emitter is created using the special syntax `m[T]`, where `T` is the type of the value:

```tut
val in = m[Int] // emitter for async value of type `Int`

val result = m[Int] // emitter for async value of type `String`

```

An async process must be declared using the `go { }` syntax.
In order to activate one or more async processes, use the `site()` call.

```tut
site(
  go { case in(x) ⇒     // consume an async value `in(...)`
    val z = x * 2       // compute some new value using x
    result(z)           // emit a new async value `result(z)`
  },
  go { case result(x) ⇒ println(x) } // consume `result(...)`
)
in(123); in(124); in(125)   // emit some async values for input
Thread.sleep(200) // wait for async processes to start

```

An async process can depend on _several_ async input values at once, and may emit several async values as output.
The process will start only when _all_ its input async values are available (have been emitted but not yet consumed).

```tut
val in1 = m[Int] // async value 1
val in2 = m[Int] // async value 2

val result = m[Boolean] // async value of type `Boolean`

site(
  go { case in1(x) + in2(y) ⇒   // wait for two async values
    println(s"got x = $x, y = $y")  // debug
    val z: Boolean = x != y // compute some output value
    result(z) // emit `result` async value
    val t: Boolean = x > y // whatever
    result(t) // emit another `result` value
    println(s"emitted result($z) and result($t)")
  },
  go { case result(x) ⇒ println(s"got result = $x") }
)
in2(20)
in1(10) // emit async values for input
Thread.sleep(200) // wait for async processes

```

Emitting an async value is a _non-blocking_ operation; execution continues immediately, without waiting for new async processes to start.
Async processes that consume the async input data will start later, _concurrently_ with the processes that emitted their async input data.

Async data can be of any type (but the type is fixed by the declared emitter type).
For example, an async value can be of function type, which allows us to implement asynchronous _continuations_:

```tut
val in = m[Int] // async input value

val cont = m[Int ⇒ Unit]  // continuation

site(
  go { case in(x) + cont(k) ⇒
    println(s"got x = $x")
    val z : Int = x * x   // compute some output value
    k(z) // invoke continuation
  }
)

in(100) // emit async value for input
// emit the second async value for input
cont(i ⇒ println(s"computed result = $i"))
Thread.sleep(200)

```

New async processes and async values can be defined anywhere in the code,
for instance, within a function scope or within the scope of an async process.

## Example: Asynchronous counter

### Non-blocking read access

We implement a counter that can be incremented and whose value can be read.
Both the increment and the read operations are asynchronous (non-blocking).
The read operation is implemented as an async continuation.

```tut
val counter = m[Int]
val incr = m[Unit] // `increment` operation
val read = m[Int ⇒ Unit] // continuation for the `read` operation

site(
  go { case counter(x) + incr(_) ⇒ counter(x + 1) },
  go { case counter(x) + read(cont) ⇒
    counter(x) // emit x again as async `counter` value
    cont(x) // invoke continuation
  } 
)
counter(0) // set initial value of `counter` to 0
incr() // emit a `Unit` async value
incr() // this can be called from any concurrently running code
read(i ⇒ println(s"counter = $i")) // this too
Thread.sleep(200)

```

An async value can be consumed only by _one_ async process.
For this reason, there is no race condition when running this program,
even if several copies of the async values `incr()` and `read()` are emitted from several concurrent processes.

### Non-blocking wait until done

We now implement a counter that is incremented until some condition is met.
At that point, we would like to start another computation that uses the last obtained counter value.

```tut
val counter = m[Int]
val done = m[Int] // signal the end of counting
val next = m[Int ⇒ Unit] // continuation
val incr = m[Unit] // `increment` operation

 // The condition we are waiting for, for example:
def areWeDone(x: Int): Boolean = x > 1

site(
  go { case counter(x) + incr(_) ⇒
    val newX = x + 1
    if (areWeDone(newX)) done(newX)
    else counter(newX) 
  },
  go { case done(x) + next(cont) ⇒
    cont(x) // invoke continuation on the value `x`
  }
)
counter(0) // set initial value of `counter` to 0

incr() // Emit a `Unit` async value.
incr() // This can be called from any concurrent process.

next { x ⇒
// Continue the computation, having obtained `x`.
  println(s"counter = $x")
// more code...
}
Thread.sleep(200)

```

More code can follow `println()`, but it will be constrained to the scope of the closure under `next()`.

## Blocking emitters

In the previous example, we used a continuation in order to wait until some condition is satisfied.
`Chymyst` implements this often-used pattern via special emitters called **blocking emitters**.
Using this feature, the previous code can be rewritten more concisely:

```tut
val counter = m[Int]
val done = m[Int] // signal the end of counting
val next = b[Unit, Int] // blocking emitter with integer reply value
val incr = m[Unit] // `increment` operation

 // the condition we are waiting for, for example:
def areWeDone(x: Int): Boolean = x > 1

site(
  go { case counter(x) + incr(_) ⇒
    val newX = x + 1
    if (areWeDone(newX)) done(newX)
    else counter(newX) 
  },
  go { case done(x) + next(_, reply) ⇒
    reply(x) // emit reply with integer value `x`
  }
)

counter(0) // set initial value of `counter` to 0

incr() + incr() // same as `incr(); incr()`

val x = next() // block until reply is sent

```

More code can follow `println()`, and that code can use `x` and is no longer constrained to the scope of a closure, as before.

Blocking emitters are declared using the `b[T, R]` syntax, where `T` is the type of async value they carry and `R` is the type of their **reply value**.

### Asynchronous counter: blocking read access

We can use a blocking emiter to implement blocking access to the counter's current value.

```tut
val counter = m[Int]
val read = b[Unit, Int] // `read` is a blocking emitter

val incr = m[Unit] // `increment` operation is asynchronous

site(
  go { case counter(x) + incr(_) ⇒ counter(x + 1) },
  go { case counter(x) + read(_, reply) ⇒
    counter(x) // emit x again as async `counter` value
    reply(x) // emit reply with value `x`
  } 
)

counter(0) // set initial value of `counter` to 0

incr()
incr() // these emitter calls do not block
val x = read() // block until reply is sent

```

## Parallel `map`

We now implement a parallel `map` operation: apply a function to every element of a list,
and produce a list of results.

An asynchronous counter is used to keep track of progress.
For simplicity, we will aggregate results into the final list in the order they are computed.
An async value `done()` is emitted when the entire list is processed.
Also, a blocking emitter `waitDone` is used to wait for the completion of the job. 

```tut
val start = m[Int] // async value for a list element

def f(x: Int): Int = x * x // some computation

val total = 10

val counter = m[Int]
val incr = m[Unit]

val result = m[List[Int]]

val done = m[Unit] // signal the end of computation
val waitDone = b[Unit, List[Int]] // blocking emitter

site(
  go { case start(i) + result(xs) ⇒
    val newXs = f(i) :: xs // compute i-th element concurrently and append
    result(newXs)
    incr()
  },
  go { case incr(_) + counter(n) ⇒
    val newN = n + 1
    if (newN == total) done()
    else counter(newN)
  },
  go { case done(_) + waitDone(_, reply) + result(xs) ⇒ reply(xs) }
)
// emit initial values
(1 to total).foreach(i ⇒ start(i))
counter(0)
result(Nil)

waitDone() // block until done, get result

```

# Envoi

In the rest of this book, async values are called **non-blocking molecules**
and async processes are called **reactions**.
