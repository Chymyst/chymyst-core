<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Chemical machine programming

The chemical machine can be programmed to perform arbitrary concurrent computations.
However, it is not immediately obvious what molecules and reactions must be defined, say, to implement a concurrent buffered queue or a concurrent merge-sort algorithm.
Another application would be a concurrent GUI interaction together with some jobs in the background.

Solving these problems via chemistry requires a certain paradigm shift.
In order to build up our chemical intuition, let us go through some more examples, from simple to more complex.

## Example: “Readers/Writers”

Suppose there is a single shared resource that can be accessed by a number of **Readers** and a number of **Writers**.
The resource behavior is such that while a Writer is accessing the resource, no readers can have access; and vice versa.
Additionally, either at most _one_ Writer or at most _three_ Readers should be able to access the resource concurrently.

To make our example concrete, we consider that the resource is “being accessed” when the given functions `readResource()` and `writeResource()` are being called.

The task is to create a chemical machine program that allows any number of concurrent Readers and Writers to call their respective functions
but restricts the number of concurrent calls to at most three `readResource()` calls or at most one `writeResource()` call.
The program should also prevent a `readResource()` and a `writeResource()` to be called at the same time. 

Let us derive the solution in a systematic way by reasoning about this problem within the chemical machine paradigm.

We need to restrict code that calls certain functions.
The only way a chemical machine can run any code is though running some _reactions_.
Therefore, we need a reaction whose body calls `readResource()` and another reaction that calls `writeResource()`.

Thus, we need to define some input molecules that will start these reactions.
Let us call these molecules `read()` and `write()` respectively:

```scala
val read = m[Unit]
val write = m[Unit]
site(
  go { case read(_) ⇒ readResource(); ??? },
  go { case write(_) ⇒ writeResource(); ??? }
)

```

Processes will emit `read()` or `write()` molecules when they need to access the resource as readers or as writers.

The reactions as written so far will always start whenever `read()` or `write()` are emitted.
However, our task is to control when these reactions start.
We need to _prevent_ these reactions from starting when there are too many concurrent accesses.

Now, there are only two ways of preventing a reaction from starting:

- by withholding some of the required input molecules;
- by using a guard condition with a mutable variable, setting the value of that variable as required.

The second method requires complicated reasoning about the current values of mutable variables.
Generally, shared mutable state is contrary to the spirit of functional programming, although it may be used in certain cases for performance optimization.
Although Scala allows it, we will not use shared mutable state in our examples.
The chemical machine works best when all values are immutable.

It remains to use the first method.

In order for us to be able to provide or withhold input molecules,
the two reactions we just discussed need to have _another_ input molecule.
Let us call this additional molecule `access()` and revise the reactions accordingly:

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Unit]
site(
  go { case read(_) + access(_) ⇒ readResource(); ??? },
  go { case write(_) + access(_) ⇒ writeResource(); ??? }
)
access() // Emit at the beginning.

```

In this chemistry, a single `access()` molecule will allow one Reader or one Writer to proceed with work.
However, after the work is done, the `access()` molecule will be gone, and no reactions will start.
To remedy this, we need to emit `access()` at the end of both reactions:

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Unit]
site(
  go { case read(_) + access(_) ⇒ readResource(); access() },
  go { case write(_) + access(_) ⇒ writeResource(); access() }
)
access() // Emit at the beginning.

```

This implements Readers/Writers with _single_ exclusive access for both.
How can we enable 3 Readers to access the resource simultaneously?

We could emit 3 copies of `access()` at the beginning of the program run.
However, this will also allow up to 3 Writers to access the resource.
We would like to make it so that three Reader's accesses are equivalent to one Writer's access.

One way of doing this is simply to replace the single `access` molecule with three copies of `access` in Writer's reaction: 

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Unit]
site(
  go { case read(_) + access(_) ⇒ readResource(); access() },
  go { case write(_) + access(_) + access() + access() ⇒
    writeResource(); access() + access() + access()
  }
)
access() + access() + access() // Emit three copies at the beginning.

```

This chemistry works as required!
Any number of `read()` and `write()` molecules can be emitted, but the reactions will start only if sufficient `access()` molecules are present.

### Generalizing Readers:Writers ratio to `n`:`1`

Our solution works but has a drawback: it cannot be generalized from 3 to `n` concurrent Readers, where `n` is a run-time parameter.
This is so because our solution uses one `write()` molecule and 3 `access()` molecules as inputs for the second reaction.
In order to generalize this to `n` concurrent Readers, we would need to write a reaction with `n + 1` input molecules.
However, the input molecules for each reaction must be specified _at compile time_.
So we cannot write a reaction with `n` input molecules, where `n` is a run-time parameter.

The only way to overcome this drawback is to count explicitly how many Readers have been granted access at the present time.
The current count value must be updated every time we grant access to a Reader and every time a Reader finishes accessing the resource.

In the chemical machine, reactions are stateless, and the only way to keep and update a value is to put that value on some molecule, and to consume and emit this molecule in some reactions.
Therefore, we need a molecule that carries the current reader count.

The easiest solution is to make `access()` carry an integer `k`, representing the current number of Readers that have been granted access.
Initially we will have `k == 0`.
We will allow a new `Writer` to have access only when `k == 0`, and a new `Reader` to have access only when `k < 3`.

As a first try, our reactions might look like this:

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Int]
val n = 3 // can be a run-time parameter
site(
  go { case read(_) + access(k) if k < n ⇒
    readResource(); access(k + 1)
  },
  go { case write(_) + access(0) ⇒ writeResource(); access(0) }
)
access(0) // Emit at the beginning.

```

The Writer reaction will now start only when `k == 0`, that is, when no Readers are currently reading the resource.
This is exactly what we want.

The Reader reaction, however, does not work correctly for two reasons:
 
- It consumes the `access()` molecule for the entire duration of the `readResource()` call, preventing any other Readers from accessing the resource.
- After `readResource()` is finished, the reaction emits `access(k + 1)`, which incorrectly signals that now one more Reader is accessing the resource.

The first problem can be fixed by emitting `access(k + 1)` at the _beginning_ of the reaction, before `readResource()` is called:

```scala
go { case read(_) + access(k) if k < n ⇒
  access(k + 1); readResource()
}

```

However, the second problem still remains.
After `readResource()` is finished, we need to decrement the current value of `k` carried by `access(k)` at that time.

Since the values on molecules are immutable, the only way of doing this in the chemical machine is to _add another reaction_ that will consume `access(k)` and emit `access(k - 1)`.
To start this reaction, we obviously need another input molecule; let us call it `readerFinished()`:
 
```scala
go { case readerFinished(_) + access(k) ⇒ access(k - 1) }
 
```

The new `readerFinished()` molecule will be emitted at the end of the Reader reaction.

The complete working code now looks like this:

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Int]
val readerFinished = m[Unit]
val n = 3 // can be a run-time parameter
site(
  go { case read(_) + access(k) if k < n ⇒ 
    access(k + 1)
    readResource()
    readerFinished()
  },
  go { case write(_) + access(0) ⇒ writeResource(); access(0) },
  go { case readerFinished(_) + access(k) ⇒ access(k - 1) }
)
access(0) // Emit at the beginning.

```

### Exercise

Modify this program to allow `m` simultaneous Readers and `n` simultaneous Writers to access the resource.
One easy way of doing this would be to use negative integer values to count Writers who have been granted access. 

#### Solution

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Int]
val readerFinished = m[Unit]
val writerFinished = m[Unit]
val nReader = 4
val nWriter = 3
site(
  go { case read(_) + access(k) if k >= 0 && k < nReader ⇒ 
    access(k + 1)
    readResource()
    readerFinished()
  },
  go { case write(_) + access(k) if -k >= 0 && -k < nWriter ⇒
    access(k - 1)
    writeResource()
    writerFinished()
  },
  go { case readerFinished(_) + access(k) ⇒ access(k - 1) }
  go { case writerFinished(_) + access(k) ⇒ access(k + 1) }
)
access(0) // Emit at the beginning.

```

### Passing values between reactions

The reactions written so far don't do much useful work besides synchronizing some function calls.
How could we modify the program so that Readers and Writers exchange values with the resource?

Let us assume that the resource contains an integer value, so that `readResource()` returns an `Int` while `writeResource()` takes an `Int` argument.

Since we do not want to use any shared mutable state, the only way to pass values is to use molecules.
So let us introduce a `readResult()` molecule with an `Int` value.
This molecule will carry the value returned by `readResource()`.

Similarly, the `write()` molecule now needs to carry an `Int` value.
When the `writeResource()` operation has been run (perhaps after some waiting for concurrent access), we will emit a new molecule `writeDone()`. 

The revised code looks like this:

```scala
val read = m[Unit]
val readResult = m[Int]
val write = m[Int]
val writeDone = m[Unit]
val access = m[Int]
val readerFinished = m[Unit]
val n = 3 // can be a run-time parameter
site(
  go { case read(_) + access(k) if k < n ⇒
    access(k + 1)
    val x = readResource(); readResult(x)
    readerFinished()
  },
  go { case write(x) + access(0) ⇒ writeResource(x); access(0) + writeDone() },
  go { case readerFinished(_) + access(k) ⇒ access(k - 1) }
)
access(0) // Emit at the beginning.

```

How would we use this program?
A Reader is represented by a reaction that emits `read()`.
However, the resulting value is carried by a new molecule `readResult(x)`.
So the Reader client must implement _two_ reactions: one will emit `read()` and the other will consume `readResult(x)` and continue the computation with the obtained value `x`.

```scala
// Reactions for a Reader: example
site(
  go { case startReader(_) ⇒ initializeReader(); read() },
  go { case readResult(x) ⇒ continueReader(x) }
)

```

We see a certain awkwardness in this implementation.
Instead of writing a single reaction body for a Reader client, such as

```scala
// Single reaction for Reader: not working!
go { case startReader(_) ⇒
  val a = ???
  val b = ???
  val x = read() // This won't work since `read()` doesn't return `x`.
  continueReader(a, b, x)
}

```

we must break the code at the place of the `read()` call into two reactions:

- the first reaction will contain all code preceding the `read()` call,
- the second reaction will contain all code after the `read()` call, where the value `x` must be obtained by consuming the additional molecule `readResult(x)`.

Since the second reaction opens a new local scope, any local values (such as `a`, `b`) computed by the first reaction will be inaccessible.
For this reason, we cannot rewrite the code shown above as two reactions like this:

```scala
// Two reactions for Reader: not working!
site(
  go { case startReader(_) ⇒
    val a = ???
    val b = ???
    read() 
  },
  go { case readResult(x) ⇒
    continueReader(a, b, x)
// Where would `a` and `b` come from???
  }
)

```

The values of `a` and `b` would need to be passed to the second reaction somehow.
Since the chemical machine does not support shared mutable state, we could pass `a` and `b` as additional values along with `read()` and `readResult()`. 
However, this is a cumbersome workaround that mixes unrelated concerns.
The `read()` and `readResult()` molecules should be concerned only with the correct implementation of the concurrent access to the `readResource()` operation.
These molecules should not be carrying any additional values that are used by other parts of the program and are completely unrelated to the `readResource()` operation.

This problem — breaking the scope into parts at risk of losing access to local variables — is sometimes called [“stack ripping”](https://www.microsoft.com/en-us/research/publication/cooperative-task-management-without-manual-stack-management/).

Similarly, the code for a Writer client must implement two reactions such as

```scala
// Reactions for a Writer: example
site(
  go { case startWriter(_) ⇒ initializeWriter(); val x: Int = ???; write(x) },
  go { case writeDone(_) ⇒ continueWriter() }
)

```

The scope of the Writer client must be split at the `write()` call.

We will see in the next chapter how to avoid stack ripping by using “blocking molecules”.
For now, we can use a trick that allows the second reaction to see the local variables of the first one.
The trick is to define the second reaction _within the scope_ of the first one:

```scala
// Two reactions for Reader: almost working but not quite
site(
  go { case startReader(_) ⇒
    val a = ???
    val b = ???
    read()
    // Define the second reaction site within the scope of the first one.
    site(
      go { case readResult(x) ⇒
        continueReader(a, b, x)
// Now `a` and `b` are obtained from the outer scope.
      }
    )
  }
)

```

There is still a minor problem with this code:
the `readResult()` molecule has to be already defined when we write the reactions that consume `read()`,
and yet we need to define a local new reaction that consumes `readResult()`.
The solution is to pass the `readResult` emitter as a value on the `read()` molecule.
In other words, the `read()` molecule will carry a value of type `M[Int]`, we will emit `read(readResult)`,
and we will revise the reaction that consumes `read()` so that it emits `readResult()`.

The revised code looks like this:

```scala
// Working code for Readers/Writers access control.
val read = m[M[Int]]
val write = m[Int]
val writeDone = m[Unit]
val access = m[Int]
val readerFinished = m[Unit]
val n = 3 // can be a run-time parameter
site(
  go { case read(readResult) + access(k) if k < n ⇒
    access(k + 1)
    val x = readResource(); readResult(x)
    readerFinished()
  },
  go { case write(x) + access(0) ⇒ writeResource(x); access(0) + writeDone() },
  go { case readerFinished(_) + access(k) ⇒ access(k - 1) }
)
access(0) // Emit at the beginning.

```
Note that emitting `readResult()` requires it to be already bound to a reaction site.
So we must create the reaction site that consumes `readResult` before emitting `read(readResult)`.

Here is some skeleton code for Reader client reactions:

```scala
site(
  go { case startReader(_) ⇒
    val a = ???
    val b = ???
    val readResult = m[Int]
    // Define the second reaction site within the scope of the first one.
    site(
      go { case readResult(x) ⇒
        continueReader(a, b, x)
// Now `a` and `b` are obtained from the outer scope.
      }
    )
// Only now, after `readResult` is bound to its RS,
// we can safely emit `read()`.
    read(readResult)
  }
)

```

Now we can write the Reader and Writer reactions in a more natural style, despite stack ripping.
The price is adding some boilerplate code and passing the `readResult()` molecule as the value carried by `read()`.
As we will see in the next chapter, this boilerplate goes away if we use blocking molecules.

### Molecules and reactions in local scopes

It is perfectly admissible to define new reaction sites (and/or new molecule emitters) within the scope of an existing reaction.
Reactions defined within a local scope are treated no differently from any other reactions.
In fact, new molecule emitters, new reactions, and new reaction sites are always defined within _some_ local scope, and they can be defined within the scope of a function, a reaction, or even within the local scope of a value:

```scala
val a: M[Unit] = {
// some local emitters
  val c = m[Int]
  val a = m[Unit]
// local reaction site
  site(
    go { case c(x) + a(_) ⇒ println(x); c(x + 1) } // whatever
  )
  c(0) // Emit this just once.
  a // Return this emitter to the outside scope.
}

```

The chemistry implemented here is an asynchronous counter that prints its current value and increments it whenever `a()` is emitted.

The result of defining this chemistry within the scope of a value block is to declare a new molecule emitter `a` as a top-level value in the outer scope.

The emitters `c` and `a` with their chemistry are active but invisible outside the scope of their block.
Only the emitter `a` is returned as the result value of the block.
So, the emitter `a` is now accessible as the value `a` in the outer scope.

This trick gives us the ability to hide some emitters and to encapsulate their chemistry, making it safe to use by outside code.
In this example, the correct function of the counter depends on having a _single_ copy of `c()` at the reaction site.
If the user were to emit (by mistake) any further copies of `c()`, the incrementing functionality would become unpredictable
since the reaction `c + a → ...` could consume any of the available copies of `c()`,
and the user has no control over the resulting indeterminism.

Encapsulating the chemistry in a block scope prevents this error from happening.
Indeed, the inner scope emits exactly one copy of `c(0)`.
In the outer scope, the code has access to the emitter `a` and can emit molecules `a()` at will, but cannot emit any new copies of `c()`
because the emitter `c` is hidden within the inner scope.
Thus it is guaranteed that the encapsulated reactions involving `c()` will run correctly.

## Example: Concurrent map/reduce

Consider the problem of implementing a concurrent map/reduce operation.
This operation first takes an array of type `Array[A]` and applies a function `f : A ⇒ B` to each element of the array.
This yields an `Array[T]` of intermediate results.
After that, a “reduce”-like operation `reduceB : (B, B) ⇒ B`  is applied to that array, and the final result of type `B` is computed.

This can be implemented in sequential code like this:

```scala
val arr : Array[A] = ???
arr.map(f).reduce(reduceB)

```

Our task is to implement all these computations concurrently — both the application of `f` to each element of the array and the accumulation of the final result.

Let us assume that the `reduceB` operation is associative and commutative and has a zero element (i.e. that the type `B` is a commutative monoid).
In that case, we may apply the `reduceB` operation to array elements in arbitrary order, which makes our task easier.

Implementing the map/reduce operation does not actually require the full power of concurrency: a [bulk synchronous processing](https://en.wikipedia.org/wiki/Bulk_synchronous_parallel) framework such as Hadoop or Spark will do the job.
Our goal is to come up with a chemical approach to concurrent map/reduce for tutorial purposes.

Since we would like to apply the function `f` concurrently to values of type `A`, we need to put all these values on separate copies of some “carrier” molecule.

```scala
val carrier = m[A]

```

We will emit a copy of the `carrier` molecule for each element of the initial array:

```scala
val arr : Array[A] = ???
arr.foreach(i ⇒ carrier(i))

```

Since the molecule emitter inherits the function type `A ⇒ Unit`, we could equivalently write this as

```scala
val arr : Array[A] = ???
arr.foreach(carrier)

```

As we apply `f` to each element, we will carry the intermediate results on molecules of another sort:

```scala
val interm = m[T]

```

Therefore, we need a reaction of this shape:

```scala
go { case carrier(x) ⇒ val res = f(x); interm(res) }

```

Finally, we need to gather the intermediate results carried by `interm()` molecules.
For this, we define the “accumulator” molecule `accum()` that will carry the final result accumulated by going over all the `interm()` molecules.

```scala
val accum = m[T]

```

When all intermediate results are collected, we would like to print the final result.
At first we might write reactions for `accum` like this:

```scala
// Non-working code!
go { case accum(b) + interm(res) ⇒ accum( reduceB(b, res) ) },
go { case accum(b) ⇒ println(b) }

```

Our plan is to emit an `accum()` molecule, so that this reaction will repeatedly consume every `interm()` molecule until all the intermediate results are processed.
When there are no more `interm()` molecules, we will print the final accumulated result.

However, there is a serious problem with this implementation: We will not actually find out when the work is finished!
Our idea was that the processing will stop when there are no `interm()` molecules left.
However, the `interm()` molecules are produced by previous reactions, which may take time.
We do not know when each `interm()` molecule will be emitted:
There may be prolonged periods of absence of any `interm()` molecules at the reaction site, while some reactions are still busy evaluating `f()`.
The second reaction can start at any time — even when some `interm()` molecules are going to be emitted very soon. 
The runtime engine cannot know whether any reaction is going to eventually emit some more `interm()` molecules, and so the present program is unable to determine whether the entire map/reduce job is finished.
The chemical machine will sometimes run the second reaction too early.

It is the programmer's responsibility to organize the chemistry such that the “end-of-job” situation can be detected.
The simplest way of doing this is to _count_ how many `interm()` molecules have been consumed.

Let us change the type of `accum()` to carry a tuple `(Int, B)`.
The first element of the tuple will now represent a counter, which indicates how many intermediate results we have already processed.
Reactions with `accum()` will increment the counter; the reaction with `fetch()` will proceed only if the counter is equal to the length of the array.

```scala
val accum = m[(Int, B)]

go { case accum((n, b)) + interm(res) ⇒
  accum( (n + 1, reduceB(b, res)) )
},
go { case accum((n, b)) if n == arr.length ⇒ println(b) }

```

What value should we emit with `accum()` initially?
When the first `interm(res)` molecule arrives, we will need to call `reduceB(x, res)` with some value `x` of type `B`.
Since we assume that `B` is a monoid, there must be a special value, say `bZero`, such that `reduceB(bZero, res) == res`.
So `bZero` is the value we need to emit on the initial `accum()` molecule.

We can now emit all `carrier` molecules and a single `accum((0, bZero))` molecule.
Because of the guard condition, the reaction with `println()` will not run until all intermediate results have been accumulated.

Here is the complete code for this example.
We will apply the function `f(x) = x * x` to elements of an integer array and then compute the sum of the resulting array of squares.

```scala
import io.chymyst.jc._

object C1 extends App {

  // Declare the "map" and the "reduce" functions.
  def f(x: Int): Int = x * x
  def reduceB(acc: Int, x: Int): Int = acc + x

  val arr = 1 to 100

  // Declare molecule emitters.
  val carrier = m[Int]
  val interm = m[Int]
  val accum = m[(Int,Int)]

  // Declare the reaction for the "map" step.
  site(
    go { case carrier(x) ⇒ val res = f(x); interm(res) }
  )

  // The two reactions for the "reduce" step must be together
  // in a single reaction site, since they both consume `accum`.
  site(
      go { case accum((n, b)) + interm(res) ⇒ accum( (n + 1, reduceB(b, res)) ) },
      go { case accum((n, b)) if n == arr.length ⇒ println(b) }
  )

  // Emit initial molecules.
  accum((0, 0))
  arr.foreach(i ⇒ carrier(i))
 // This prints `338350`.
}

```

### Achieving parallelism

The code in the previous subsection works correctly but has an important drawback:
Since there is always at most one copy of the `accum()` molecule present, the reaction `accum + interm → ... reduceB() ...` cannot run concurrently with other instances of itself.
In other words, at most one call to `reduceB()` will run at any given time!
We would like to have some parallelism, so that multiple calls to `reduceB()` may run at once.

Since the `reduceB()` operation is commutative and associative, we may reduce intermediate results in any order.
The easiest way of implementing this in the chemical machine would be to write a reaction such as

```scala
go { case interm(x) + interm(y) ⇒ interm(reduceB(x, y)) }

```

As `interm()` molecules are emitted, this reaction could run between any available pairs of `interm()` molecules.
When running on a multi-core CPU, the chemical machine should be able to schedule many such reactions concurrently and optimize the CPU load.

This code, however, is not yet correct.
When all `interm()` molecule pairs have reacted, the single `interm()` molecule will remain inert at the reaction site, since no other molecules can react with it.
To fix this problem, we need a means of tracking progress and detecting when the entire computation is finished. 

In our previous solution, we kept track of progress by using a counter `n` on the `accum()` molecule.
Let us therefore add a counter also to the `interm()` molecule.
The presence of an `interm((n, x))` molecule indicates that the partial reduce of a set of `n` numbers was already completed, with the result value `x`.

The reaction is rewritten like this:

```scala
site(
  go { case interm((n1, x1)) + interm((n2, x2)) ⇒
    interm((n1 + n2, reduceB(x1, x2))) 
  },
  go { case interm((n, x)) if n == arr.length ⇒ println(x) }
)

```

This will work correctly if we initially emit `interm((1, x))`, indicating that the value `x` is a result of a partial reduce of a single-number set.
Here is the complete sample code:

```scala
import io.chymyst.jc._

object C2 extends App {

  // Declare the "map" and the "reduce" functions.
  def f(x: Int): Int = x * x
  def reduceB(acc: Int, x: Int): Int = acc + x

  val arr = 1 to 100

  // Declare molecule emitters.
  val carrier = m[Int]
  val interm = m[(Int, Int)]

  // Declare the reaction for the "map" step.
  site(
    go { case carrier(x) ⇒ val res = f(x); interm((1, res)) }
  )

  // Declare the reaction for the "reduce" step,
  // keeping track of the number of partially reduced items.
  site(
    go { case interm((n1, x1)) + interm((n2, x2)) ⇒
      val x = reduceB(x1, x2)
      val n = n1 + n2
      if (n == arr.length) println(x) else interm((n, x))
    }
  )

  // Emit initial molecules.
  arr.foreach(i ⇒ carrier(i))
  // This prints `338350`.
}

```

### Exercise

Modify the concurrent map/reduce program for the case when the `reduceB()` computation is itself asynchronous.

Assume that the computation of `reduceB` is defined as a reaction at another reaction site, with code such as

```scala
val inputsB = m[(Int, Int)]
val resultB = m[Int]

site(
  go { case inputsB((x, y)) ⇒
  val z = reduceB(x, y) // long computation
  resultB(z) 
  }
)

```

Adapt the code in `object C2` from the previous section to use the molecules `inputsB()` and `resultB()`, instead of calling `reduceB()` directly.

### Ordered map/reduce

Typically, the reduce operation is associative, but it may or may not be commutative.
A simple example of an associative but non-commutative operation on integers is [Budden's function](http://www.jstor.org/stable/3613855),

```scala
def addBudden(x: Int, y: Int) = if (x % 2 == 0) x + y else x - y 

```

If the `reduceB()` operation is non-commutative, we may not apply the reduce operation to just _any_ pair of partial results.
The map/reduce code in the previous subsection will select pairs in arbitrary order and will most likely fail to compute the correct final value for non-commutative reduce operations.

For instance, suppose we have an array `x1, x2, ..., x10` of intermediate results that we need to reduce with a non-commutative `reduceB()` operation.
We may reduce `x4` with `x3` or with `x5`, but not with `x6` or any other element.
Also, we need to reduce elements in the correct order, e.g. `reduceB(x3, x4)` but not `reduceB(x4, x3)`.

Once we have computed the new intermediate result `reduceB(x3, x4)`, we may reduce that with `x5`, with `x2`, or with the result of `reduceB(x1, x2)` or `reduceB(x5, x6, x7)` — but not with, say, `x6` or with `reduceB(x6, x7)`.

What we need to do is to restrict the `reduceB()` operation so that it runs only on _consecutive_ partial results.
How can we modify the chemistry to support these restrictions?

We need to assure that the reaction `interm + interm → interm` only consumes molecules that represent consecutive intermediate results, but does not start for any other pairs of input molecules.

The chemical machine has only two ways of preventing a reaction from starting:

1. by withholding some input molecules required for that reaction
2. by specifying guard conditions on input molecule values

If we were to use the first method, we would need to model all the allowed reactions with special auxiliary input molecules.
We would have to define a new input molecule for each possible intermediate result: first, for each element of the initial array; then for each possible reduction between two consecutive elements; then for each possible reduction result between those, and so on.
While this is certainly possible to implement, it would require is to define _O_(_n_<sup>2</sup>) different molecules and _O_(_n_<sup>3</sup>) different reactions,
where _n_ is the number of the initial `interm()` molecules before first `reduceB()` is called.  
This means a very large number of possible molecules and reactions for the scheduler to choose from:
we will need _O_(_n_<sup>3</sup>) operations just to define the chemistry for this code, which will then run only _O_(_n_) reduce steps.
The code will run unacceptably slowly if implemented in this way.

The solution with the second method is to add a guard condition to the reaction `interm + interm → interm`, so that it will only run between consecutive intermediate results.
To identify such intermediate results, we need to put an ordering label on the `interm()` molecule values, which will allow us to write a reaction like this:

```scala
go { case interm((l1, x1)) + interm((l2, x2))
 if isConsecutive???(l1, l2) ⇒
  interm((???, reduceB(x1, x2))) 
}

```

Note that `interm()` previously carried a counter value, which we need to keep track of the progress of the computation.
The ordering label we denoted by `l1` and `l2` must be set in addition to that counter.

Let us decide that the value of the ordering label should be equal to the smallest index that has been reduced.
Thus, the `interm()` molecule must carry a triple such as `interm((l, n, x))`, representing an intermediate result `x` that was computed after reducing `n` numbers.
For example, `reduceB(reduceB(x5, x6), x7)` would be represented by `interm((5, 3, x))`. 

With this representation, we can easily check whether two intermediate results are consecutive: the condition is `l1 + n1 == l2`.
In this way, we combine the functions of the counter and the ordering label.

The “reduce” reaction can be now written as

```scala
go { case interm((l1, n1, x1)) + interm((l2, n2, x2)) 
  if l1 + n1 == l2 ⇒ 
  interm((l1, n1 + n2, reduceB(x1, x2))) 
}

```

Other code remains unchanged, except for emitting the initial `interm()` molecules during the “map” step:

```scala
go { case carrier(i) ⇒ val res = f(i); interm((i, 1, res)) }

```

The performance of this code is significantly slower than that of the commutative map/reduce,
because the chemical machine must go through many copies of the `interm()` molecule before selecting the ones that can react together.
To improve performance, we can allow the two `interm()` molecules to react in any order:

```scala
go { case interm((l1, n1, x1)) + interm((l2, n2, x2)) 
  if l1 + n1 == l2 || l2 + n2 == l1 ⇒
   if (l1 + n1 == l2)
    interm((l1, n1 + n2, reduceB(x1, x2)))
   else
    interm((l2, n1 + n2, reduceB(x2, x1)))
}

```

This optimization is completely mechanical: it consists of permuting the order of repeated molecules before applying the guard condition.
The chemical machine could perform this code transformation automatically for all such reactions.
As of version 0.2.0, `Chymyst` does not implement this optimization.

### Improving performance

The solution we derived in the previous subsection uses a single reaction with repeated input molecules and guard conditions.
This type of reactions typically causes slow performance of the chemical machine.
Contention on repeated input molecules with cross-molecule guard conditions will force the chemical machine to enumerate many possible combinations of input molecules before scheduling a new reaction. 
To improve the performance, we need to avoid such reactions.

For simplicity, we will now focus only on the “reduce” part of “map/reduce”.
We will assume that the data consists of an array of initial values to which the `reduceB()` operation must be applied.
The operation is assumed to be associative but non-commutative.

Consider the order in which the `reduceB` operation is to be applied to the elements of the initial array. 
In the previous solutions, we allowed _any_ two consecutive values to be reduced at any time.
This requires a complicated chemistry and, as a consequence, yields slow performance.
Let us instead restrict the set of possible `reduceB()` operations:
We will only permit the merging of elements 0 with 1, then 2 with 3, and so on;
then we will repeat the same merging procedure recursively, effectively building a binary tree of `reduceB` operations.

To make this construction easier, let us begin with a hard-coded binary tree of reactions for the special case where we have exactly 8 intermediate results.
All initial values need to be carried by molecules that we will denote by `a0()`, `a1()`, and so on.
At the first step, we would like to permit merging `a0` with `a1`, `a2` with `a3`, `a4` with `a5`, and `a6` with `a7` (but no other pairs).
After this merging, we expect to obtain four intermediate results: `a01`, `a23`, `a45`, and `a67`.

This is represented by the following chemistry:

```scala
site(
  go { case a0(x) + a1(y) ⇒ a01(reduceB(x,y)) }, 
  go { case a2(x) + a3(y) ⇒ a23(reduceB(x,y)) }, 
  go { case a4(x) + a5(y) ⇒ a45(reduceB(x,y)) }, 
  go { case a6(x) + a7(y) ⇒ a67(reduceB(x,y)) } 
)

```

We will now permit merging of `a01` with `a23` and `a45` with `a67` (but no other `reduceB` operations).
That will yield the two intermediate results, `a03` and `a47`:

```scala
site(
  go { case a01(x) + a23(y) ⇒ a03(reduceB(x,y)) }, 
  go { case a45(x) + a67(y) ⇒ a47(reduceB(x,y)) } 
)

```

It remains to merge `a03` and `a47`, obtaining the final result `a07`:

```scala
site(
  go { case a03(x) + a67(y) ⇒ a07(reduceB(x,y)) } 
)

```

Note that we could define all these reactions in separate reaction sites because
there is no contention on the input molecules, and thus _all_ `reduceB()` reactions can run concurrently.
The performance of this reaction structure will be much better than that of the reactions with repeated molecules and guard conditions. 

What remains is for us to be able to define this kind of reaction structure dynamically, at run time.

Note that all necessary reactions are almost identical and differ only in the molecules they consume and produce.
We begin by defining an auxiliary function that creates one such reaction and new molecule emitters for it:

```scala
def reduceOne[T](res: M[T]): (M[T], M[T]) = {
  val a0 = m[T]
  val a1 = m[T]
  site( go { case a0(x) + a1(x) ⇒ res(reduceB(x,y)) } )
  (a0, a1)
}

```

The argument of this function is the result molecule emitter `res` that needs to be defined in advance.

We can now refactor our example 8-value chemistry by using this function.
We have to start from the top result molecule `a07()` and descend towards the bottom:

```scala
val a07 = m[T]
val (a03, a47) = reduceOne(a07)

val (a01, a23) = reduceOne(a03)
val (a45, a67) = reduceOne(a47)

val (a0, a1) = reduceOne(a01)
val (a2, a3) = reduceOne(a23)
val (a4, a5) = reduceOne(a45)
val (a6, a7) = reduceOne(a67)

a0(...) + a1(...) + ... // emit all initial values on these molecules

```

Once we emit `a0()`, `a1()`, etc., the code will eventually emit `a07()` with the final result.

So far, this is entirely equivalent to hand-coded reactions for 8 initial values.
It remains to transform the code to allow us to specify the number of initial values (8) as a run-time parameter. 
Recursion is the obvious solution here.
Let us refactor the above code using an auxiliary recursive function that takes an array `arr` of initial values as argument.
The auxiliary function will also emit the initial values once we reach the bottom level of the tree where the required emitter will be available as the parameter `res`:

```scala
def reduceAll[T](arr: Array[T], res: M[T]) =
  if (arr.length == 1) res(arr(0)) // Emit initial values.
  else  {
    val (arr0, arr1) = arr.splitAt(arr.length / 2)
    val (a0, a1) = reduceOne(res)
    reduceAll(arr0, a0)
    reduceAll(arr1, a1)
 }

```

What we have done is a simple refactoring of code in terms of a recursive function.
This refactoring would be the same in any programming language and is not specific to chemical machine programming.

Let us now inline the call to `reduceOne()` and rewrite the code as a self-contained function:

```scala
def reduceAll[T](arr: Array[T], res: M[T]) =
  if (arr.length == 1) res(arr(0))
  else  {
    val (arr0, arr1) = arr.splitAt(arr.length / 2)
    val a0 = m[T]
    val a1 = m[T]

    site( go { case a0(x) + a1(y) ⇒ res(reduceB(x, y)) } )

    reduceAll(arr0, a0)
    reduceAll(arr1, a1)
 }

```

Note that the chemistry involving new molecules `a0()` and `a1()` is encapsulated within the scope of the function `reduceAll()`.
The new molecules cannot be emitted outside that scope.

This solution works but has a defect: the function `reduceAll()` is not tail-recursive.
To remedy this, we can refactor the body of the function `reduceAll()` into a _reaction_.

We declare `reduceAll` as a molecule emitter with value type `(Array[T], M[T])`.
Instead of a recursive function, we obtain non-recursive code that can be thought of as a “chain reaction”:

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

### Nested reactions

What exactly happens when a new reaction is defined within the scope of an existing reaction?
Each time the “parent” reaction `reduceAll() ⇒ ... ` is run, a _new_ pair of molecule emitters `a0` and `a1` is created.
Then we call the `site()` function with a reaction that consumes the molecules `a0()` and `a1()`.
The `site()` call will create a _new_ reaction site
and bind the new molecules `a0()` and `a1()` to that reaction site.

The new reaction site and the new molecule emitters `a0` and `a1` will be visible only within the scope of the parent reaction's body.
In this way, the chemical machine works seamlessly with Scala's mechanism of local scopes.
It guarantees that no other code could disturb the intended functionality of the reactions encapsulated within the scope of `reduceAll()`. 

Since the `reduceAll()` reaction emits its own input molecules until the array is fully split into individual elements,
it will run many times to define the new reactions we previously denoted by `r01`, `r23`, `r45`, `r67`, `r03`, `r47`, and `r07`.
Thus, emitting the initial molecule `reduceAll((arr, res))` will create a tree-like structure of chain reactions at run time,
reproducing the tree-like computation structure that we previously hand-coded.

### Exercise:<sup>*</sup> concurrent ternary search 

The task is to search through a sorted array `arr: Array[Int]` for a given value `x`.
The result must be of type `Option[Int]`, indicating the index at which `x` is present in the array, or `None` if not found.

The well-known binary search will divide the array in two parts, determine the part where `x` might be present, and run the search recursively in that part.
This algorithm cannot be parallelized.
However, if we divide the array into _three_ parts, we can check concurrently whether `x` is below the first or the second division point.
This is the “ternary search” algorithm.

Implement the chemistry that performs the ternary search, as a recursive reaction that generates the necessary tree of computations.

## Example: Concurrent merge-sort

As we have just seen in the previous section, chemical programs can implement recursion:
A molecule can start a reaction whose reaction body defines further reactions and emits the same molecule, which will start another copy of the same reaction, etc.
One can visualize this situation as a “chain reaction” (of course, proper precautions are taken so that the computations eventually terminate).

Since each reaction body will have a fresh local scope, the chain reaction will define _chemically new molecules and new reactions_ each time.
This will create a recursive configuration of reactions, such as a linked list or a tree.

We will now figure out how to use chain reactions for implementing the well-known “merge sort” algorithm in `Chymyst`.

The initial data will be an array of type `T`, and we will therefore need a molecule to carry that array.
We will also need another molecule, `sorted()`, to carry the sorted result.

```scala
val mergesort = m[Array[T]]
val sorted = m[Array[T]]

```

The main idea of the merge-sort algorithm is to split the array in half, sort each half recursively, and then merge the two sorted halves into the resulting array.

```scala
site(
  go { case mergesort(arr) ⇒
    if (arr.length == 1)
      sorted(arr) // all done, trivially
    else {
      val (part1, part2) = arr.splitAt(arr.length / 2)
      // emit recursively
      mergesort(part1) + mergesort(part2)
      ???
    }
  }
)

```

We still need to merge pairs of sorted arrays.
Let us assume that an array-merging function `arrayMerge(arr1, arr2)` is already implemented.
We could then envision a reaction like this:

```scala
go { case sorted1(arr1) + sorted2(arr2) ⇒
  sorted( arrayMerge(arr1, arr2) ) 
}

```

Actually, we need to return the _upper-level_ `sorted` molecule after merging the results carried by the lower-level `sorted1` and `sorted2` molecules.
In order to achieve this, we can define the merging reaction within the scope of the `mergesort` reaction:

```scala
site(
  go { case mergesort(arr) ⇒
    if (arr.length == 1)
      sorted(arr) // all done, trivially
    else {
      val (part1, part2) = arr.splitAt(arr.length / 2)
      // define lower-level "sorted" molecules
      val sorted1 = m[Array[T]]
      val sorted2 = m[Array[T]]
      site(
        go { case sorted1(arr1) + sorted2(arr2) ⇒
         sorted( arrayMerge(arr1, arr2) ) // all done, merged
        } 
      )
      // emit recursively
      mergesort(part1) + mergesort(part2)
    }
  }
)

```

This is still not quite right; we need to arrange the reactions such that the `sorted1()` and `sorted2()` molecules are emitted by the lower-level recursive emissions of `mergesort`.
The way to achieve this is to pass the emitters for the upper-level `sorted` molecules on values carried by the `mergesort()` molecule.
Let us make the `mergesort()` molecule carry both the array and the upper-level `sorted` emitter.
We will then be able to pass the lower-level `sorted` emitters to the recursive calls of `mergesort()`.

```scala
val mergesort = new M[(Array[T], M[Array[T]])]

site(
  go {
    case mergesort((arr, sorted)) ⇒
      if (arr.length <= 1)
        sorted(arr) // all done, trivially
      else {
        val (part1, part2) = arr.splitAt(arr.length/2)
        // `sorted1` and `sorted2` will be the sorted results from lower level
        val sorted1 = new M[Array[T]]
        val sorted2 = new M[Array[T]]
        site(
          go { case sorted1(arr1) + sorted2(arr2) ⇒
            sorted(arrayMerge(arr1, arr2)) // all done, merged 
          }
        )
        // emit lower-level mergesort
        mergesort(part1, sorted1) + mergesort(part2, sorted2)
      }
  }
)
// sort our array at top level, assuming `finalResult: M[Array[T]]`
mergesort((array, finalResult))

```

The complete working example of the concurrent merge-sort is in the file [`MergesortSpec.scala`](https://github.com/Chymyst/chymyst-core/blob/master/benchmark/src/test/scala/io/chymyst/benchmark/MergesortSpec.scala).

