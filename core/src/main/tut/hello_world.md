A first `Chymyst` program:

```tut:book
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


Emit some more async values:

```tut:book
incr()

var x: Int = 0

read(i => x = i)

x

```