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
// import io.chymyst.jc._
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
scala> val in = m[Int] // async value emitter of type `Int`
in: io.chymyst.jc.M[Int] = in

scala> val result = m[String] // async value of type `String`
result: io.chymyst.jc.M[String] = result

scala> site(
     |   go { case in(x) ⇒
     |     // compute some new value
     |     val z = s"The result is $x"
     |     println(z) // whatever
     |     result(z) // emit z as the `result` async value
     |   }
     | )
res0: io.chymyst.jc.WarningsAndErrors = WarningsAndErrors(List(),List(),Site{in → ...})

scala> in(123) // emit an async value for input

scala> // eventually, `result()` will be emitted
     | 
```

An async process can depend on _several_ async input values at once, and may emit several async values as output.
The process will start when all its input async values are available (have been emitted but not yet consumed).

```scala
     | val in1 = m[Int] // async value 1
The result is 123
in1: io.chymyst.jc.M[Int] = in1

scala> val in2 = m[Int] // async value 2
in2: io.chymyst.jc.M[Int] = in2

scala> val result = m[Boolean] // async value of type `Boolean`
result: io.chymyst.jc.M[Boolean] = result

scala> site(
     |   go { case in1(x) + in2(y) ⇒
     |     // debug
     |     println(s"got x=$x, y=$y")
     |     // compute output value
     |     val z : Boolean = x != y // whatever
     |     result(z) // emit `result` async value
     |     val t : Boolean = x > y // whatever
     |     result(t) // emit another `result` value
     |   }
     | )
res4: io.chymyst.jc.WarningsAndErrors = WarningsAndErrors(List(),List(),Site{in1 + in2 → ...})

scala> in1(100)

scala> in2(200) // emit async values for input

scala> // eventually, two `result()` values will be emitted
got x=100, y=200
     | 
```

Emitting an async value is a non-blocking operation; execution continues immediately.
Async processes that consume the async input data will start later, _concurrently_ with the processes that emitted their async input data.

Async data can be of any type (but the type is fixed).
For example, an async value can be of function type, which allows us to implement asynchronous _continuations_:

```scala
     | import io.chymyst.jc._
import io.chymyst.jc._

scala> val in = m[Int] // async input value
in: io.chymyst.jc.M[Int] = in

scala> val cont = m[Int ⇒ Unit] // continuation with side effect
cont: io.chymyst.jc.M[Int => Unit] = cont

scala> site(
     |   go { case in(x) + cont(k) ⇒
     |     // debug
     |     println(s"got x=$x")
     |     // compute output value and continue
     |     val z : Int = x * x // whatever
     |     k(z) // invoke continuation
     |   }
     | )
res9: io.chymyst.jc.WarningsAndErrors = WarningsAndErrors(List(),List(),Site{cont + in → ...})

scala> in(100) // emit async value for input

scala> cont(i ⇒ println(s"computed result = $i")) // emit the second async value for input
got x=100
computed result = 10000

scala> // eventually, the program prints `computed result = 10000`
     | 
```

New async processes and async values can be defined anywhere in the code,
including within a function scope, or within the scope of an async process.

## Example: Concurrent counter

### Non-blocking read access

We implement a counter that can be incremented and whose value can be read.
Both the increment and the read operations are concurrent and non-blocking.
The read operation is implemented as an async continuation.

```scala
     | import io.chymyst.jc._
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
res14: io.chymyst.jc.WarningsAndErrors = WarningsAndErrors(List(),List(),Site{counter + incr → ...; counter + read → ...})

scala> counter(0) // set initial value of `counter` to 0

scala> incr() // emit a unit async value

scala> incr() // this can be called from any concurrent process

scala> read(i ⇒ println(s"counter = $i")) // this too
```

An async value can be consumed only by one async process.
For this reason, there is no race condition when running this program,
even if several copies of the async values `incr()` and `read()` are emitted from several concurrent processes.
counter = 2

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
     | def areWeDone(x: Int): Boolean = x > 10
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
res20: io.chymyst.jc.WarningsAndErrors = WarningsAndErrors(List(),List(),Site{counter + incr → ...; done + next → ...})

scala> counter(0) // set initial value of `counter` to 0

scala> incr() // Emit a unit async value.

scala> incr() // This can be called from any concurrent process.

scala> next { x ⇒
     | // Continue the computation, having obtained `x`.
     |   println(s"counter = $x")
     |   // more code...
     |   // which has to be all within this function scope
     | }
```

## Blocking channels

In the previous example, we used a continuation in order to wait until some condition is satisfied.
`Chymyst` implements this often-used pattern as a special language feature called a **blocking channel**.
Using this feature, the previous code can be rewritten more concisely:

```scala
scala> import io.chymyst.jc._
import io.chymyst.jc._

scala> val counter = m[Int]
counter: io.chymyst.jc.M[Int] = counter

scala> val done = m[Int] // signal the end of counting
done: io.chymyst.jc.M[Int] = done

scala> val next = b[Unit, Int] // blocking reply channel
next: io.chymyst.jc.B[Unit,Int] = next/B

scala> val incr = m[Unit] // `increment` operation
incr: io.chymyst.jc.M[Unit] = incr

scala>  // the condition we are waiting for, for example:
     | def areWeDone(x: Int): Boolean = x > 10
areWeDone: (x: Int)Boolean

scala> site(
     |   go { case counter(x) + incr(_) ⇒
     |     val newX = x + 1
     |     if (areWeDone(newX)) done(newX)
     |     else counter(newX) 
     |   },
     |   go { case done(x) + next(_, reply) ⇒
     |     reply(x) // emit reply with value `x`
     |   }
     | )
res26: io.chymyst.jc.WarningsAndErrors = WarningsAndErrors(List(),List(),Site{counter + incr → ...; done + next/B → ...})

scala> counter(0) // set initial value of `counter` to 0

scala> incr() // emit a unit async value

scala> incr() // this can be called from any concurrent process

scala> val x = next() // block until reply is sent
