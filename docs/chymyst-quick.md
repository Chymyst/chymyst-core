<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Quick start

`Chymyst Core` implements a declarative DSL for purely functional concurrency in Scala.
The DSL is based on concepts that are unfamiliar to most readers. 

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

An asynchronous process is implemented as a computation that works with a special kind of data, called **async values**.
An async process can consume one or more input async values and may emit (zero or more) output async values.

An async process must be declared using the `site()` call and the `go { }` syntax.
Input and output async values for the async process need to be declared separately using the special syntax `m[T]` where `T` is the type of the value:

```scala
import io.chymyst.jc._

val in = m[Int] // async value of type `Int`

val result = m[String] // async value of type `String`

site(
  go { case in(x) ⇒
    // compute some new value
    val z = s"The result is $x"
    println(z) // whatever
    result(z) // emit z as the `result` async value
  }
)

in(123) // emit an async value for input
// eventually, `result()` will be emitted

```

Async processes can depend on _several_ async input values at once, and may emit several async values as output:

```scala
import io.chymyst.jc._

val in1 = m[Int] // async value 1
val in2 = m[Int] // async value 2

val result = m[Boolean] // async value of type `Boolean`

site(
  go { case in1(x) + in2(y) ⇒
    // debug
    println(s"got x=$x, y=$y")
    // compute output value
    val z : Boolean = x != y // whatever
    result(z) // emit `result` async value
    val t : Boolean = x > y // whatever
    result(t) // emit another `result` value
  }
)

in1(100)
in2(200) // emit async values for input
// eventually, two `result()` values will be emitted

```

Emitting an async value is a non-blocking operation; execution continues immediately.
Async processes that consume async data will start later, _concurrently_ with the processes that emitted their async input values.

Async values can be of any type.
For example, an async value can be of function type, which allows us to implement asynchronous _continuations_:

```scala
import io.chymyst.jc._

val in = m[Int] // async input value

val cont = m[Int ⇒ Unit] // continuation with side effect

site(
  go { case in(x) + cont(k) ⇒
    // debug
    println(s"got x=$x")
    // compute output value and continue
    val z : Int = x * x // whatever
    k(x) // invoke continuation
  }
)

in(100) // emit async value for input
cont(i ⇒ println(s"computed result = $i")) // emit the second async value for input
// eventually, the program prints `computed result = 10000`

```

## Concurrent counter

### Non-blocking read access

We implement a counter that can be incremented and whose value can be read.
Both the increment and the read operations are concurrent and non-blocking.
The read operation is implemented as an async continuation.

```scala
import io.chymyst.jc._

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

incr() // emit a unit async value
incr() // this can be called from any concurrent process
read(i ⇒ println(s"counter = $i")) // this too

```

An async value can be consumed only by one async process.
For this reason, there is no race condition when running this program,
even if several copies of the async values `incr()` and `read()` are emitted from several concurrent processes.

### Non-blocking wait until done

We now implement a counter that is incremented until some condition is met.
At that point, we would like to start another computation that uses the last obtained counter value.

```scala
import io.chymyst.jc._

val counter = m[Int]
val done = m[Int] // signal the end of counting
val next = m[Int ⇒ Unit] // continuation
val incr = m[Unit] // `increment` operation

 // the condition we are waiting for, for example:
def areWeDone(x: Int): Boolean = x > 10

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

incr() // emit a unit async value
incr() // this can be called from any concurrent process

next { x ⇒
// Continue the computation, having obtained `x`.
  println(s"counter = $x")
  // more code...
  // which has to be all within this closed scope
}

```

## Blocking wait until done

In the previous example, we used a continuation in order to wait until some condition is satisfied.
`Chymyst` implements this often-used pattern a special language feature, so that the previous code can be rewritten like this:

```scala
import io.chymyst.jc._

val counter = m[Int]
val done = m[Int] // signal the end of counting
val next = b[Unit, Int] // blocking reply channel
val incr = m[Unit] // `increment` operation

 // the condition we are waiting for, for example:
def areWeDone(x: Int): Boolean = x > 10

site(
  go { case counter(x) + incr(_) ⇒
    val newX = x + 1
    if (areWeDone(newX)) done(newX)
    else counter(newX) 
  },
  go { case done(x) + next(_, reply) ⇒
    reply(x) // emit reply with value `x`
  }
)

counter(0) // set initial value of `counter` to 0

incr() // emit a unit async value
incr() // this can be called from any concurrent process

val x = next() // block until reply is sent
// Continue the computation, having obtained `x`.
println(s"counter = $x")
// more code...
// but now we are not in a closed scope!

```

### Concurrent counter: blocking access

We can use the blocking channel feature to implement blocking access to the counter's current value.

```scala
import io.chymyst.jc._

val counter = m[Int]
val read = b[Unit, Int] // read via blocking channel

val incr = m[Unit] // `increment` operation

site(
  go { case counter(x) + incr(_) ⇒ counter(x+1) },
  go { case counter(x) + read(_, reply) ⇒
    counter(x) // emit x again as async `counter` value
    reply(x) // emit reply with value `x`
  } 
)

counter(0) // set initial value of `counter` to 0

incr() // emit unit async value
incr() // this can be done from any concurrent process
val x = read() // block until reply is sent
// continue, now can use the obtained value of `x`

```

## Parallel `map`

We now implement the parallel `map` operation using a (mutable) array.
There is no race condition because concurrent updates always mutate different elements of the array.

The concurrent counter is used to keep track of progress.
An async value `done()` is emitted when the entire array is processed.
Also, a blocking channel `waitDone()` is used to wait for the completion of the job. 

```scala
import io.chymyst.jc._

val in = m[Long] // async value

def f(x: Long): Long = ??? // some computation

val arr: Array[Long] = 
  Array.tabulate[Long](100)(x => x) // initial array

val counter = m[Int]
val incr = m[Unit] // increment counter

val done = m[Unit] // signal the end of computation
val waitDone = b[Unit, Unit] // blocking channel

site(
  go { case in(i) ⇒
    arr(i) = f(arr(i)) // transform i-th element
    incr()
  },
  go { case incr(_) + counter(n) ⇒
    if (n == arr.length) done()
    else counter(n + 1)
  },
  go { case done(_) + waitDone(_, reply) ⇒ reply() }
)
// emit initial values
counter(0)
arr.foreach(i ⇒ in(i))

waitDone() // block until done

```

# Envoi

In the rest of this book, async values are called **non-blocking molecules**, blocking channels are called **blocking molecules**, and async processes are called **reactions**.
