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

TODO: expand

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

