<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Chemical machine programming: first examples

The chemical machine can be programmed to perform arbitrary concurrent computations.
However, it is not immediately obvious what molecules and reactions must be defined, say, to implement a concurrent buffered queue or a concurrent merge-sort algorithm.
Another interesting application would be a concurrent GUI interaction together with some jobs in the background.

Solving these problems via chemistry requires a certain paradigm shift.
In order to build up our chemical intuition, let us go through some more examples.

## Example: "Readers/Writers"

There is a resource that can be accessed by a number of Readers and a number of Writers.
We require that either one Writer or up to three Readers be able to access the resource concurrently.
To simplify our example, we assume that "accessing a resource" means calling a function `readResource()` for Readers and `writeResource()` for Writers.

Let us begin reasoning about this problem in the chemical machine paradigm, deriving the solution in a systematic way.

We need to have control over code that calls certain functions.
The only way a chemical machine can run any code is though reactions.
Therefore, we need a reaction that will call `readResource()` and a reaction that will call `writeResource()`.
We also need to define some input molecules that will start these reactions.
Let us call these molecules `read` and `write` respectively:

```scala
val read = m[Unit]
val write = m[Unit]
site(
  go { case read(_) => readResource(); ??? },
  go { case write(_) => writeResource(); ??? }
)

```

Processes will emit `read()` or `write()` when they need to access the resource as readers or as writers.

Now, we actually need to prevent these reactions from starting in certain circumstances.
The only way to prevent a reaction from starting is to withhold some of its input molecules.
Therefore, the two reactions we just discussed need to have _another_ input molecule.
We could do this:

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
To remedy this, we emit `access()` at the end of both reactions:

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

This implements Readers/Writers with single access.
How can we enable 3 Readers to access the resource simultaneously?
We could emit 3 copies of `access()` at the beginning of the program run.
However, this will allow up to 3 Writers to access the resource.
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

This chemistry works as required.
Any number of `read()` and `write()` molecules can be emitted, but the reactions will start only if sufficient `access()` molecules are present.

### Generalizing Readers:Writers ratio to `n`:`1`

Our solution works but has a drawback: it cannot be generalized from 3 to `n` Reader accesses, where `n` is a runtime parameter.
This is because we cannot write a reaction with `n` input molecules, where `n` is not known at compile time.

The only way to overcome this drawback is to be able to count explicitly how many Readers have been granted access at any time.
In the chemical machine, the only way to keep state is through values carried by molecules.
Therefore, we need a molecule that carries the reader count.
The easiest solution is to make `access()` carry an integer `k`, representing the number of Readers that have been granted access.
Initially we will have `k == 0`.
We will allow a `Writer` to have access only when `k == 0`, and a `Reader` to have access only when `k < 3`.

As a first try, our reactions might look like this:

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Int]
val n = 3 // can be a runtime parameter
site(
  go { case read(_) + access(k) if k < n => readResource(); access(k + 1) },
  go { case write(_) + access(0) => writeResource(); access(0) }
)
access(0) // Emit at the beginning.

```

The Writer reaction will now start only when `k == 0`, that is, no Readers are currently reading the resource.
The Reader reaction, however, does not work correctly for two reasons:
 
- It consumes the `access()` molecule for the entire duration of the `readResource()` call, preventing any other Readers from accessing the resource.
- After `readResource()` is finished, the reaction emits `access(k + 1)`, which incorrectly signals that now one more Reader is accessing the resource.

The first problem can be fixed by emitting `access(k + 1)` at the beginning of the reaction, before `readResource()` is called:

```scala
go { case read(_) + access(k) if k < n => access(k + 1); readResource() }

```

However, the second problem remains.
After `readResource()` is finished, we need to decrement the current value of `k`.

The only way of doing this in the chemical machine is to _add another reaction_ such as
 
```scala
go { case finished(_) + access(k) => access(k - 1) }
 
```

The new `finished()` molecule will be emitted at the end of the Reader reaction.
The complete working code looks like this:

```scala
val read = m[Unit]
val write = m[Unit]
val access = m[Int]
val finished = m[Unit]
val n = 3 // can be a runtime parameter
site(
  go { case read(_) + access(k) if k < n => 
    access(k + 1)
    readResource()
    finished()
  },
  go { case write(_) + access(0) => writeResource(); access(0) },
  go { case finished(_) + access(k) => access(k - 1) }
)
access(0) // Emit at the beginning.

```

As an exercise: Modify this program to allow `m` simultaneous Writers to access the resource.
One easy way of doing this would be to use negative integer values to count Writers who have been granted access. 

### Passing values between reactions

The reactions written so far don't do much useful work besides synchronizing some function calls.
We would like to modify the program so that Readers and Writers exchange values with the resource.
Let us assume that the resource contains an integer value, so that `readResource()` returns an `Int` while `writeResource()` takes an `Int` argument.

Since the only way to get values is to use molecules, let us introduce a `readResult()` molecule with an `Int` value.
This molecule will be emitted when `readResource()` is run and returns a value.

Similarly, the `write()` molecule now needs to carry an `Int` value.
The revised code looks like this:

```scala
val read = m[Unit]
val readResult = m[Int]
val write = m[Int]
val access = m[Int]
val finished = m[Unit]
val n = 3 // can be a runtime parameter
site(
  go { case read(_) + access(k) if k < n =>
    access(k + 1)
    val x = readResource(); readResult(x)
    finished()
  },
  go { case write(x) + access(0) => writeResource(x); access(0) },
  go { case finished(_) + access(k) => access(k - 1) }
)
access(0) // Emit at the beginning.

```

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

