<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Generators and coroutines

TODO

# Timers and timeouts

## Timers in the chemical machine

The basic function of a **timer** is to schedule some computation at a fixed time in the future.
In the chemical machine, any computation is a reaction, and reactions can be started in only one way — by emitting some molecules.

However, the chemical machine does not provide a means of delaying a reaction when all input molecules are present.
Doing so would be inconsistent with the semantics of chemical reactions,
because it is impossible to know or to predict when the required molecules are going to be emitted and arrive at a reaction site.

So, a “chemical timer” would be a facility for emitting a molecule after a fixed time delay.

How can we implement this in the chemical machine?
For example, what code needs to be written if a reaction needs to emit its output molecules after a 10 second delay?

This functionality can be of course implemented in a simple way if we are willing to block a thread, for example:

```scala
go { case a(x) + ... ⇒ Thread.sleep(1000); b(x) }

```

In this way, we add a `delaying` wrapper to a molecule emitter, transforming it automatically into an emitter that works with a given time delay.
This wrapper can be made universal and encapsulated into a function, 

```scala
def delaying[A](delayMs: Long): M[A] = {
  val delayed = m[(A, M[A])]
  site(go { case delayed((x, mx)) ⇒
    Thread.sleep(delayMs)
    mx(x) 
  })
  delayed
}

```

Instead of emitting a non-blocking molecule `a(123)`, we can now emit `delaying[Int](1000)((a, 123))`.

Alternatively, the wrapping can be performed by creating a new `Reaction` value, and using it while constructing reaction sites like this:

```scala
def delayingReaction[A](emitter: M[A], delayMs: Long): (M[A], Reaction) = {
  val delayed = m[A]
  val reaction = go { case delayed(x) ⇒ 
    BlockingIdle(Thread.sleep(delayMs))
    emitter(x)
  }
  (delayed, reaction)
}

// User code:
val a = m[Int]
val b = m[Int]
val (aDelayed, aDelayedR) = delayingReaction(a, 1000)
site(
  go { case a(x) + b(y) ⇒ ... aDelayed(x+y) },
  aDelayedR // Add the reaction to the reaction site.
)

```

However, in many cases we would like to avoid blocking a thread.

A more efficient approach is to allocate statically a Java timer that will provide delayed emissions for a given molecule (or for a set of molecules).
However, this timer now needs to be accessible to any reaction (at any reaction site!) that might need to schedule a delayed emission of those molecules.

Another solution is to allocate a timer automatically within each reaction site, and to make that timer accessible through the molecule emitters.
This requires special support for timers in the `Chymyst` implementation; it would not be hard to add this support if there is a compelling use case.

Without such support, we must maintain Java timers in the application code.
Constructing a delayed emitter is then no different than implementing a delay on any side effect:

```scala
import java.util.{Timer, TimerTask}
val delayTimer = new Timer() // This timer is shared by all calls to `delay()`.
def delay[A](delayMs: Long)(effect: ⇒ Unit): Unit =
  delayTimer.schedule(
    new TimerTask { override def run(): Unit = effect },
    delayMs
  )
}

// User code:
val a = m[Int]
val b = m[Int]
site(
  go { case a(x) + b(y) ⇒ delay(1000) { b(x + y) } }
)

```

## Timeouts and cancellation

In the context of the chemical machine, a timeout means that some action is taken when a given molecule is not consumed (or a given reaction does not start) within a specified time.
When a timeout happens, we would typically need to cancel the reaction that would otherwise have started.

We can use a time-delayed emitter to signal a timeout.
It remains to implement the cancellation of a reaction.

To derive the implementation for this functionality in `Chymyst`, we need to reason about what molecules are present when we need to cancel a pending reaction.
Suppose a given reaction such as 

```scala
go { case a(x) + b(y) ⇒ ... }
 
```

consumes molecules `a()` and `b()`, and we are to cancel that reaction when some molecule `c()` is emitted.
At that time, molecules `a()` and `b()` may or may not be present.
The only way to prevent the reaction from starting without changing the reaction's code is to remove `a()` or `b()` from the reaction site.
To do that, we could define an additional reaction that consumes, say, `a()` and `c()`, or `b()` and `c()`, and emits nothing:

```scala
go { case a(_) + c(_) ⇒ }
go { case b(_) + c(_) ⇒ }
 
```

These two reactions will need to be defined at the same reaction site as the original reaction.

As we emit `c()`, one of these two reactions would start if `a()` is already present but `b()` is not yet present, or vice versa.
If, however, both `a()` and `b()` are already emitted before `c()`, we are not guaranteed that we can cancel the reaction between `a()` and `b()`.

There are two other ways of cancelling a reaction, but both require modifying the reaction's code.
One way is to add a guard condition involving a mutable flag:

```scala
@volatile var enabled: Boolean = true

site(
  go { case a(x) + b(y) if enabled ⇒ ... }
)

```

In this way, we can cancel a reaction without actually removing its input molecules, by setting `enabled = false`.
We can also reset it back to true if we want to enable the reaction again.
Of course, we have no control over _when_ the re-enabled reaction will start.

Another way to implement cancellation is to put the flag on the value of, say, the molecule `a()`, and include a condition into the reaction's body:

```scala
site(
  go { case a(x) + b(y) ⇒
    if (x) do_something() else stop()
  }
)

```

This implementation would be suitable if we need to run this reaction many times with different input molecules, but stop it at some point.
To stop the reaction, we emit `a(false)`.
The function we called `stop()` would perform some cleanup or, at any rate, would not call `do_something()` as this reaction normally does.

Since the molecule `a()` is pipelined in this program, each emitted copy of `a()` will be kept in a linear queue and consumed in the order emitted.
So, we can be reasonably certain that the reaction will stop as soon as the emitted copy `a(false)` is consumed.
