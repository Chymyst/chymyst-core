<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Chemical machine programming: first examples

The chemical machine can be programmed to perform arbitrary concurrent computations.
However, it is not immediately obvious what molecules and reactions must be defined, say, to implement a concurrent buffered queue or a concurrent merge-sort algorithm.
Another interesting application would be a concurrent GUI interaction together with some jobs in the background.

Solving these problems via chemistry requires a certain paradigm shift.
In order to build up our chemical intuition, let us go through some more examples.

## Example: "Readers/Writers"

Suppose there is a shared resource that can be accessed by a number of **Readers** and a number of **Writers**.
We require that either one Writer or up to three Readers be able to access the resource concurrently.

To simplify our example, we assume that "accessing a resource" means calling `readResource()` for Readers and `writeResource()` for Writers.
The task is to create a chemical machine program that allows any number of concurrent Readers and Writers to call their respective functions but restricts the number of concurrent calls to at most three `readResource()` calls or at most one `writeResource()` call, but not both. 

Let us begin reasoning about this problem in the chemical machine paradigm, deriving the solution in a systematic way.

We need to have control over code that calls certain functions.
The only way a chemical machine can run any code is though running some reactions.
Therefore, we need a reaction whose body calls `readResource()` and another reaction that calls `writeResource()`.

We also need to define some input molecules that will start these reactions.
Let us call these molecules `read()` and `write()` respectively:

```scala
val read = m[Unit]
val write = m[Unit]
site(
  go { case read(_) => readResource(); ??? },
  go { case write(_) => writeResource(); ??? }
)

```

Processes will emit `read()` or `write()` molecules when they need to access the resource as readers or as writers.

The reactions as written so far will always start whenever `read()` or `write()` are emitted.
Actually, we need to _prevent_ these reactions from starting in certain circumstances (to prevent too many concurrent accesses).

The only way to prevent a reaction from starting is to withhold some of its input molecules.
Therefore, the two reactions we just discussed need to have _another_ input molecule.
Let us call this additional molecule `access()` and revise the reactions:

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Unit]
site(
  go { case read(_) + access(_) => readResource(); ??? },
  go { case write(_) + access(_) => writeResource(); ??? }
)
access() // Emit at the beginning.

```

In this chemistry, a single `access()` molecule will allow one Reader or one Writer to proceed with its work.
However, after the work is done, the `access()` molecule will be gone, and no reactions will start.
To remedy this, we need to emit `access()` at the end of both reactions:

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Unit]
site(
  go { case read(_) + access(_) => readResource(); access() },
  go { case write(_) + access(_) => writeResource(); access() }
)
access() // Emit at the beginning.

```

This implements Readers/Writers with single access for both.
How can we enable 3 Readers to access the resource simultaneously?

We could emit 3 copies of `access()` at the beginning of the program run.
However, this will also allow up to 3 Writers to access the resource.
We would like to make it so that three Reader's accesses are equivalent to one Writer's access.

One way of doing this is literally to replace the single `access` molecule with three copies of `access` in Writer's reaction: 

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Unit]
site(
  go { case read(_) + access(_) => readResource(); access() },
  go { case write(_) + access(_) + access() + access() =>
    writeResource(); access() + access() + access()
  }
)
access() + access() + access() // Emit at the beginning.

```

This chemistry works as required!
Any number of `read()` and `write()` molecules can be emitted, but the reactions will start only if sufficient `access()` molecules are present.

### Generalizing Readers:Writers ratio to `n`:`1`

Our solution works but has a drawback: it cannot be generalized from 3 to `n` concurrent Readers, where `n` is a run-time parameter.
This is because our solution uses one `write()` molecule and 3 `access()` molecules as inputs for the second reaction.
In order to generalize this to `n` concurrent Readers, we would need to write a reaction with `n + 1` input molecules.
However, the sets of input molecules for all reactions must be known at compile time.
So we cannot write a reaction with `n` input molecules, where `n` is a run-time parameter.

The only way to overcome this drawback is to count explicitly how many Readers have been granted access at the present time.
The current count value must be updated every time we grant access to a Reader and every time a Reader finishes accessing the resource.

In the chemical machine, the only way to keep and update a value is to put that value on some molecule.
Therefore, we need a molecule that carries the current reader count.

The easiest solution is to make `access()` carry an integer `k`, representing the current number of Readers that have been granted access.
Initially we will have `k == 0`.
We will allow a `Writer` to have access only when `k == 0`, and a `Reader` to have access only when `k < 3`.

As a first try, our reactions might look like this:

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Int]
val n = 3 // can be a run-time parameter
site(
  go { case read(_) + access(k) if k < n => readResource(); access(k + 1) },
  go { case write(_) + access(0) => writeResource(); access(0) }
)
access(0) // Emit at the beginning.

```

The Writer reaction will now start only when `k == 0`, that is, when no Readers are currently reading the resource.
This is exactly what we want.

The Reader reaction, however, does not work correctly for two reasons:
 
- It consumes the `access()` molecule for the entire duration of the `readResource()` call, preventing any other Readers from accessing the resource.
- After `readResource()` is finished, the reaction emits `access(k + 1)`, which incorrectly signals that now one more Reader is accessing the resource.

The first problem can be fixed by emitting `access(k + 1)` at the beginning of the reaction, before `readResource()` is called:

```scala
go { case read(_) + access(k) if k < n => access(k + 1); readResource() }

```

However, the second problem remains.
After `readResource()` is finished, we need to decrement the current value of `k` carried by `access(k)` at that time.

Since the values on molecules are immutable, the only way of doing this in the chemical machine is to _add another reaction_ that will consume `access(k)` and emit `access(k - 1)`.
To start this reaction, we clearly need another molecule, say `readerFinished()`:
 
```scala
go { case finished(_) + access(k) => access(k - 1) }
 
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
  go { case read(_) + access(k) if k < n => 
    access(k + 1)
    readResource()
    readerFinished()
  },
  go { case write(_) + access(0) => writeResource(); access(0) },
  go { case readerFinished(_) + access(k) => access(k - 1) }
)
access(0) // Emit at the beginning.

```

Exercise: Modify this program to allow `n` simultaneous Readers and `m` simultaneous Writers to access the resource.
One easy way of doing this would be to use negative integer values to count Writers who have been granted access. 

### Passing values between reactions

The reactions written so far don't do much useful work besides synchronizing some function calls.
We would like to modify the program so that Readers and Writers exchange values with the resource.
Let us assume that the resource contains an integer value, so that `readResource()` returns an `Int` while `writeResource()` takes an `Int` argument.

Since the only way to pass values is to use molecules, let us introduce a `readResult()` molecule with an `Int` value.
This molecule will be emitted when `readResource()` is run and returns a value.

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
  go { case read(_) + access(k) if k < n =>
    access(k + 1)
    val x = readResource(); readResult(x)
    readerFinished()
  },
  go { case write(x) + access(0) => writeResource(x); access(0) + writeDone() },
  go { case readerFinished(_) + access(k) => access(k - 1) }
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
  go { case startReader(_) => initializeReader(); read() },
  go { case readResult(x) => continueReader(x) }
)

```

We see a certain inconvenience in this implementation.
Instead of writing a single reaction body for a Reader client,

```scala
// Single reaction for Reader: not working!
go { case startReader(_) =>
  val a = ???
  val b = ???
  val x = read() // This won't work since `read()` doesn't return `x`
  continueReader(a, b, x)
}

```

we must break the code at the place of the `read()` call into two reactions:
the first reaction will contain all code preceding the `read()` call,
and the second reaction will contain all code after the `read()` call, where the value `x` must be obtained by consuming the additional molecule `readResult(x)`.
Since the second reaction opens a new local scope, any local values (such as `a`, `b`) computed by the first reaction will be inaccessible.
For this reason, we cannot rewrite the code shown above as two reactions like this:

```scala
// Two reactions for Reader: not working!
site(
  go { case startReader(_) =>
    val a = ???
    val b = ???
    read() 
  },
  go { case readResult(x) =>
    continueReader(a, b, x) // Where do `a` and `b` come from???
  }
)

```

The values of `a` and `b` would need to be passed to the second reaction somehow.
Since the chemical machine does not support mutable global state, we could try to pass `a` and `b` as additional values along with `read()` and `readResult()`. 
However, this is a cumbersome workaround that mixes unrelated concerns.
The `read()` and `readResult()` molecules should be concerned only with the correct implementation of the concurrent access to the `readResource()` operation.
These molecules should not be carrying some arbitrary additional values that are used by other parts of the program and are completely unrelated to the `readResource()` operation.

This problem -- breaking the code into parts at risk of losing access to local variables -- is sometimes called ["stack ripping"](https://www.microsoft.com/en-us/research/publication/cooperative-task-management-without-manual-stack-management/).

Similarly, the code for a Writer client must implement two reactions such as

```scala
// Reactions for a Writer: example
site(
  go { case startWriter(_) => initializeWriter(); val x: Int = ???; write(x) },
  go { case writeDone(_) => continueWriter() }
)

```

Again, the scope of the Writer client must be cut at the `write()` call.

We will see in the next chapter how to avoid stack ripping by using "blocking molecules".
For now, we can use a trick that allows the second reaction to see the local variables of the first one.
The trick is to define the second reaction _within the scope_ of the first one:

```scala
// Two reactions for Reader: almost working but not quite
site(
  go { case startReader(_) =>
    val a = ???
    val b = ???
    read()
    // Define the second reaction site within the scope of the first one.
    site(
      go { case readResult(x) =>
        continueReader(a, b, x) // Now `a` and `b` are obtained from outer scope.
      }
    )
  }
)

```

There is still a minor problem with this code:
the `readResult()` molecule has to be already defined when we write the reactions that consume `read()`,
and yet we need to define a local new reaction that consume `readResult()`.
The solution is to pass the `readResult` emitter as a value on the `read()` molecule.
In other words, the `read()` molecule will carry a value of type `M[Int]`, and its reaction needs to be revised to emit the molecule whose emitter `read()` carries.

Since now `read()` carries `readResult` as its value, and since `readResult()` will be emitted by reactions, we need to bind `readResult` to some reaction site before we send `readResult` to be emitted.
For this reason, we must emit `read(readResult)` only _after_ we define the reaction site to which the `readResult` emitter is bound.

The complete code looks like this:

```scala
// Code for Readers/Writers access control.
val read = m[M[Int]]
val write = m[Int]
val writeDone = m[Unit]
val access = m[Int]
val readerFinished = m[Unit]
val n = 3 // can be a run-time parameter
site(
  go { case read(readResult) + access(k) if k < n =>
    access(k + 1)
    val x = readResource(); readResult(x)
    readerFinished()
  },
  go { case write(x) + access(0) => writeResource(x); access(0) + writeDone() },
  go { case readerFinished(_) + access(k) => access(k - 1) }
)
access(0) // Emit at the beginning.

```

```scala
// Code for Reader client reactions.
site(
  go { case startReader(_) =>
    val a = ???
    val b = ???
    val readResult = m[Int]
    // Define the second reaction site within the scope of the first one.
    site(
      go { case readResult(x) =>
        continueReader(a, b, x) // Now `a` and `b` are obtained from outer scope.
      }
    )
    // Only now, after `readResult` is bound, we may emit `read()`.
    read(readResult)
  }
)

```

Now we can write the Reader and Writer reactions in a more natural style, despite stack ripping.
The price is adding some boilerplate code and passing the `readResult()` molecule as the value carried by `read()`.

### Molecules and reactions in local scopes

It is perfectly admissible to define a new reaction site (and/or new molecule emitters) within the scope of an existing reaction.
Reactions defined within a local scope are treated no differently from any other reactions.
New molecule emitters, new reactions, and new reaction sites are always defined within _some_ local scope, and they can be defined within the scope of a function, a reaction, or even within the local scope of a value:

```scala
val a: M[Unit] = {
  val c = m[Int] // whatever
  val q = m[Unit]
  site(
    go { case c(x) + q(_) => println(x); c(x + 1) } // whatever
  )
  c(0) // Emit this just once.
  q // Return the emitter `q` to outside scope.
}

```

The chemistry implemented here is a "counter" that prints its current value and increments it, whenever `q()` is emitted.

The result of defining this chemistry within the scope of a block is to create a new molecule emitter `a`.
The emitters `c` and `q` are invisible outside that scope; however, the emitter `q` is returned as the result value of the block, and so the emitter `q` is now accessible as the value of `a`.

This trick gives us the ability to hide some emitters and in this way to encapsulate and protect the chemistry from tampering.
The correct function of the chemistry depends on having a single copy of `c()`.
Indeed, the inner scope emits exactly one copy of `c(0)`.
In the outer scope, the code has access to the `a` emitter and can emit molecules `a()` at will, but cannot emit any new copies of `c()`.
Thus it is guaranteed that the encapsulated reactions involving `c()` will run correctly.

## Example: Concurrent map/reduce

Consider the problem of implementing a concurrent map/reduce operation.
This operation first takes an array of type `Array[A]` and applies a function `f : A => B` to each element of the array.
This yields an `Array[B]` of intermediate results.
After that, a “reduce”-like operation `reduceB : (B, B) => B`  is applied to that array, and the final result of type `B` is computed.

This can be implemented in sequential code like this:

```scala
val arr : Array[A] = ???
arr.map(f).reduce(reduceB)

```

Our task is to implement all these computations concurrently -- both the application of `f` to each element of the array and the accumulation of the final result.

For simplicity, we will assume that the `reduceB` operation is associative and commutative and has a zero element (i.e. that the type `B` is a commutative monoid).
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
arr.foreach(i => carrier(i))

```

Since the molecule emitter inherits the function type `A => Unit`, we could equivalently write this as

```scala
val arr : Array[A] = ???
arr.foreach(carrier)

```

As we apply `f` to each element, we will carry the intermediate results on molecules of another sort:

```scala
val interm = m[B]

```

Therefore, we need a reaction of this shape:

```scala
go { case carrier(x) => val res = f(x); interm(res) }

```

Finally, we need to gather the intermediate results carried by `interm()` molecules.
For this, we define the “accumulator” molecule `accum()` that will carry the final result as we accumulate it by going over all the `interm()` molecules.

```scala
val accum = m[B]
val fetch = b[Unit, B]

```

When all intermediate results are collected, we would like to print the final result.
At first we might write reactions for `accum` like this:

```scala
go { case accum(b) + interm(res) => accum( reduceB(b, res) ) },
go { case accum(b) => println(b) }

```

Our plan is to emit an `accum()` molecule, so that this reaction will repeatedly consume every `interm()` molecule until all the intermediate results are processed.
When there are no more `interm()` molecules, we will print the final accumulated result.

However, there is a serious problem with this implementation: We will not actually find out when the work is finished.
Our idea was that the processing will stop when there are no `interm()` molecules left.
However, the `interm()` molecules are produced by previous reactions, which may take time.
We do not know when each `interm()` molecule will be emitted: there may be prolonged periods of absence of any `interm()` molecules in the soup (while some reactions are still busy evaluating `f`).
The second reaction can start at any time - even when some `interm()` molecules are going to be emitted very soon. 
The runtime engine cannot know whether any reaction is going to eventually emit some more `interm()` molecules, and so the present program is unable to determine whether the entire map/reduce job is finished.

It is the programmer's responsibility to organize the chemistry such that the “end-of-job” situation can be detected.
The simplest way of doing this is to _count_ how many `interm()` molecules have been consumed.

Let us change the type of `accum()` to carry a tuple `(Int, B)`.
The first element of the tuple will now represent a counter, which indicates how many intermediate results we have already processed.
Reactions with `accum()` will increment the counter; the reaction with `fetch()` will proceed only if the counter is equal to the length of the array.

```scala
val accum = m[(Int, B)]

go { case accum((n, b)) + interm(res) => accum( (n + 1, reduceB(b, res)) ) },
go { case accum((n, b)) if n == arr.size => println(b) }

```

What value should we emit with `accum()` initially?
When the first `interm(res)` molecule arrives, we will need to call `reduceB(x, res)` with some value `x` of type `B`.
Since we assume that `B` is a monoid, there must be a special value, say `bZero`, such that `reduceB(bZero, res) == res`.
So `bZero` is the value we need to emit on the initial `accum()` molecule.

We can now emit all `carrier` molecules and a single `accum((0, bZero))` molecule.
Because of the guard condition, the reaction with `println()` will not run until all intermediate results have been accumulated.

Here is the complete code for this example (see also `MapReduceSpec.scala` in the unit tests).
We will apply the function `f(x) = x * x` to elements of an integer array, and then compute the sum of the resulting array of squares.

```scala
import io.chymyst.jc._

object C extends App {

  // declare the "map" and the "reduce" functions
  def f(x: Int): Int = x * x
  def reduceB(acc: Int, x: Int): Int = acc + x

  val arr = 1 to 100

  // declare molecule types
  val carrier = m[Int]
  val interm = m[Int]
  val accum = m[(Int,Int)]

  // declare the reaction for the "map" step
  site(
    go { case carrier(x) => val res = f(x); interm(res) }
  )

  // The two reactions for the "reduce" step must be together since they both consume `accum`.
  site(
      go { case accum((n, b)) + interm(res) => accum( (n + 1, reduceB(b, res)) ) },
      go { case accum((n, b)) if n == arr.size => println(b) }
  )

  // emit molecules
  accum((0, 0))
  arr.foreach(i => carrier(i))
 // prints 338350
}

```

## Example: Concurrent merge-sort

Chemical laws can be recursive: a molecule can start a reaction whose reaction body defines further reactions and emits the same molecule.
Since each reaction body will have a fresh scope, new molecules and new reactions will be defined every time.
This will create a recursive configuration of reactions, such as a linked list or a tree.

We will now figure out how to use recursive molecules for implementing the merge-sort algorithm in `Chymyst`.

The initial data will be an array of type `T`, and we will therefore need a molecule to carry that array.
We will also need another molecule, `sorted()`, to carry the sorted result.

```scala
val mergesort = m[Array[T]]
val sorted = m[Array[T]]

```

The main idea of the merge-sort algorithm is to split the array in half, sort each half recursively, and then merge the two sorted halves into the resulting array.

```scala
site(
  go { case mergesort(arr) =>
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
go { case sorted1(arr1) + sorted2(arr2) =>
  sorted( arrayMerge(arr1, arr2) ) 
}

```

Actually, we need to return the _upper-level_ `sorted` molecule after merging the results carried by the lower-level `sorted1` and `sorted2` molecules.
In order to achieve this, we can define the merging reaction within the scope of the `mergesort` reaction:

```scala
site(
  go { case mergesort(arr) =>
    if (arr.length == 1)
      sorted(arr) // all done, trivially
    else {
      val (part1, part2) = arr.splitAt(arr.length / 2)
      // define lower-level "sorted" molecules
      val sorted1 = m[Array[T]]
      val sorted2 = m[Array[T]]
      site(
        go { case sorted1(arr1) + sorted2(arr2) =>
         sorted( arrayMerge(arr1, arr2) ) // all done, merged
        } 
      )
      // emit recursively
      mergesort(part1) + mergesort(part2)
    }
  }
)

```

This is still not quite right; we need to arrange the reactions such that the `sorted1`, `sorted2` molecules are emitted by the lower-level recursive emissions of `mergesort`.
The way to achieve this is to pass the emitters for the upper-level `sorted` molecules on values carried by the `mergesort` molecule.
Let's make the `mergesort` molecule carry both the array and the upper-level `sorted` emitter.
We will then be able to pass the lower-level `sorted` emitters to the recursive calls of `mergesort`.

```scala
val mergesort = new M[(Array[T], M[Array[T]])]

site(
  go {
    case mergesort((arr, sorted)) =>
      if (arr.length <= 1)
        sorted(arr) // all done, trivially
      else {
        val (part1, part2) = arr.splitAt(arr.length/2)
        // "sorted1" and "sorted2" will be the sorted results from lower level
        val sorted1 = new M[Array[T]]
        val sorted2 = new M[Array[T]]
        site(
          go { case sorted1(arr1) + sorted2(arr2) =>
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

The complete working example of the concurrent merge-sort is in the file [`MergesortSpec.scala`](https://github.com/Chymyst/joinrun-scala/blob/master/benchmark/src/test/scala/io/chymyst/benchmark/MergesortSpec.scala).

