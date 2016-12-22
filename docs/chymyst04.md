<link href="{{ site.github.url }}/tables.css" rel="stylesheet">

# Example: Concurrent map/reduce

It remains to see how we can use the chemical machine for performing various concurrent computations.
For instance, it is perhaps not evident what kind of molecules and reactions must be defined, say, to implement a concurrent buffered queue or a concurent merge-sort algorithm.
Another interesting application would be a concurrent GUI interaction together with some jobs in the background.
Solving these problems via chemistry requires a certain paradigm shift.
In order to build up our chemical intuition, let us go through some more examples.

Consider the problem of implementing a concurrent map/reduce operation.
This operation first takes an array `Array[A]` and applies a function `f : A => B` to each of the elements.
This yields an `Array[B]` of intermediate results.
After that, a “reduce”-like operation `reduceB : (B, B) => B`  is applied to that array, and the final result of type `B` is computed.

This can be implemented in sequential code like this:

```scala
val arr : Array[A] = ???

arr.map(f).reduce(reduceB)

```

Our task is to implement this as a concurrent computation.
We would like to perform all computations concurrently -- both applying `f` to each element of the array and accumulating the final result should be done at the same time when possible.

For simplicity, we will assume that the `reduceB` operation is associative and commutative (that is, the type `B` is a commutative monoid).
In that case, we are applying the `reduceB` operation to array elements in arbitrary order, which makes our task easier.

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

Since the molecule emitter inherits the functional type, we could equivalently write this as

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

Finally, we need to gather the intermediate results carried by `interm` molecules.
For this, we define the “accumulator” molecule `accum` that will carry the final result as we accumulate it by going over all the `interm` molecules.
We can also define a blocking molecule `fetch` that can be used to read the accumulated result from another process.

```scala
val accum = m[B]
val fetch = b[Unit, B]

```

At first we might write reactions for `accum` such as this one:

```scala
go { case accum(b) + interm(res) => accum( reduceB(b, res) ) },
go { case accum(b) + fetch(_, reply) => reply(b) }

```

Our plan is to emit an `accum(...)` molecule, so that this reaction will repeatedly consume every `interm(...)` molecule until all the intermediate results are processed.
Then we will emit a blocking `fetch()` molecule and obtain the final accumulated result.

However, there is a serious problem with this implementation: We will not actually find out when the work is finished.
Our idea was that the processing will stop when there are no `interm` molecules left.
However, the `interm` molecules are produced by previous reactions, which may take time.
We do not know when each `interm` molecule will be emitted: there may be prolonged periods of absence of any `interm` molecules in the soup (while some reactions are still busy evaluating `f`).
The runtime engine cannot know which reactions will eventually emit some more `interm` molecules, and so there is no way to detect whether the entire map/reduce job is finished.

It is the programmer's responsibility to organize the reactions such that the “end-of-job” situation can be detected.
The simplest way of doing this is to count how many `accum` reactions have been run.

Let us change the type of `accum` to carry a tuple `(Int, B)`.
The first element of the tuple will now represent a counter, which indicates how many intermediate results we have already processed.
Reactions with `accum` will increment the counter; the reaction with `fetch` will proceed only if the counter is equal to the length of the array.

```scala
val accum = m[(Int, B)]

go { case accum((n, b)) + interm(res) =>
    accum((n+1, reduceB(b, res) ))
  },
go { case accum((n, b)) + fetch(_, reply) if n == arr.size => reply(b) }

```

What value should we emit with `accum` initially?
When the first `interm(res)` molecule arrives, we will need to call `reduceB(x, res)` with some value `x` of type `B`.
Since we assume that `B` is a monoid, there must be a special value, say `bZero`, such that `reduceB(bZero, res)==res`.
So `bZero` is the value we need to emit on the initial `accum` molecule.

We can now emit all `carrier` molecules, a single `accum((0, bZero))` molecule, and a `fetch()` molecule.
Because of the guard condition, the reaction with `fetch()` will not run until all intermediate results have been accumulated.

Here is the complete code for this example (see also `MapReduceSpec.scala` in the unit tests).
We will apply the function `f(x) = x*x` to elements of an integer array, and then compute the sum of the resulting array of squares.

```scala
import code.winitzki.jc._

object C extends App {

  // declare the "map" and the "reduce" functions
  def f(x: Int): Int = x*x
  def reduceB(acc: Int, x: Int): Int = acc + x

  val arr = 1 to 100

  // declare molecule types
  val carrier = m[Int]
  val interm = m[Int]
  val accum = m[(Int,Int)]
  val fetch = b[Unit,Int]

  // declare the reaction for "map"
  site(
    go { case carrier(x) => val res = f(x); interm(res) }
  )

  // reactions for "reduce" must be together since they share "accum"
  site(
      go { case accum((n, b)) + interm(res) if n > 0 =>
        accum((n+1, reduceB(b, res) ))
      },
      go { case accum((0, _)) + interm(res) => accum((1, res)) },
      go { case accum((n, b)) + fetch(_, reply) if n == arr.size => reply(b) }
  )

  // emit molecules
  accum((0, 0))
  arr.foreach(i => carrier(i))
  val result = fetch()
  println(result) // prints 338350
}

```

# Molecules and reactions in local scopes

Since molecules and reactions are local values, they are lexically scoped within the block where they are defined.
If we define molecules and reactions in the scope of an auxiliary function, or even in the scope of a reaction body, these newly defined molecules and reactions will be encapsulated and protected from outside access.

To illustrate this feature of the chemical paradigm, let us implement a function that defines a “concurrent counter” and initializes it with a given value.

Our previous implementation of the concurrent counter has a drawback: The molecule `counter(n)` must be emitted by the user and remains globally visible.
If the user emits two copies of `counter` with different values, the `counter + decr` and `counter + fetch` reactions will work unreliably, choosing between the two copies of `counter` at random.
We would like to emit exactly one copy of `counter` and then prevent the user from emitting any further copies of that molecule.

A solution is to define `counter` and its reactions within a function that returns the `decr` and `fetch` molecules to the outside scope.
The `counter` emitter will not be returned to the outside scope, and so the user will not be able to emit extra copies of that molecule.

```scala
def makeCounter(initCount: Int): (M[Unit], B[Unit, Int]) = {
  val counter = m[Int]
  val decr = m[Unit]
  val fetch = m[Unit, Int]

  site(
    go { counter(n) + fetch(_, r) => counter(n) + r(n)},
    go { counter(n) + decr(_) => counter(n-1) }
  )
  // emit exactly one copy of `counter`
  counter(initCount)

  // return these two emitters to the outside scope
  (decr, fetch)
}

```

The closure captures the emitter for the `counter` molecule and emits a single copy of that molecule.
Users from other scopes cannot emit another copy of `counter` since the emitter is not visible outside the closure.
In this way, it is guaranteed that one and only one copy of `counter` will be present in the soup.

Nevertheless, the users receive the emitters `decr` and `fetch` from the closure.
So the users can emit these molecules and start their reactions (despite the fact that these molecules are also locally defined, like `counter`).

The function `makeCounter` can be called like this:

```scala
val (d, f) = makeCounter(10000)
d() + d() + d() // emit 3 decrement molecules
val x = f() // fetch the current value

```

Also note that each invocation of `makeCounter` will create new, fresh molecules `counter`, `decr`, and `fetch` inside the closure, because each invocation will create a fresh local scope.
In this way, the user can create as many independent counters as desired.

This example shows how we can encapsulate some molecules and yet use their reactions.
A closure can define local reaction with several input molecules, emit some of these molecules initially, and return some (but not all) molecule emitters to 
the 
global scope outside of the closure.

# Example: Concurrent merge-sort

Chemical laws can be recursive: a molecule can start a reaction whose reaction body defines further reactions and emits the same molecule.
Since each reaction body will have a fresh scope, new molecules and new reactions will be defined every time.
This will create a recursive configuration of reactions, such as a linked list or a tree of reactions.

We will now figure out how to use recursive molecules for implementing the merge-sort algorithm in `JoinRun`.

The initial data will be an array of type `T`, and we will therefore need a molecule to carry that array.
We will also need another molecule to carry the sorted result.

```scala
val mergesort = m[Array[T]]
val sorted = m[Array[T]]

```

The main idea of the merge-sort algorithm is to split the array in half, sort each half recursively, and then merge the two sorted halves into the resulting array.

```scala
site ( go { case mergesort(arr) =>
    if (arr.length == 1) sorted(arr) else {
      val (part1, part2) = arr.splitAt(arr.length / 2)
      // emit recursively
      mergesort(part1) + mergesort(part2)
    }
  }
)

```

We still need to take two sorted arrays and merge them.
Let us assume that an array-merging function `arrayMerge(arr1, arr2)` is already implemented.
We could then envision a reaction like this:

```scala
... go { case sorted1(arr1) + sorted2(arr2) => sorted( arrayMerge(arr1, arr2) ) }

```

Actually, we need to return the upper-level `sorted` molecule from merging the results carried by the lower-level `sorted1` and `sorted2` molecule.
In order to achieve this, we need to define the merging reaction _within the scope_ of the `mergesort` reaction:

```scala
site ( go { case mergesort(arr) =>
    if (arr.length == 1) sorted(arr) else {
      val (part1, part2) = arr.splitAt(arr.length / 2)
      // define lower-level "sorted" molecules
      val sorted1 = m[Array[T]]
      val sorted2 = m[Array[T]]
      site( go { case sorted1(arr1) + sorted2(arr2) => sorted( arrayMerge(arr1, arr2) ) } )
      // emit recursively
      mergesort(part1) + mergesort(part2)
    }
  }
)

```

This is still not right; we need to arrange the reactions such that the `sorted1`, `sorted2` molecules are emitted by the lower-level recursive emissions of `mergesort`.
The way to achieve this is to pass the emitters for the `sorted` molecules as values carried by the `mergesort` molecule.
We will then pass the lower-level `sorted` molecule emitters to the recursive calls of `mergesort`.

```scala
val mergesort = new M[(Array[T], M[Array[T]])]

site(
  go {
    case mergesort((arr, sorted)) =>
      if (arr.length <= 1) sorted(arr)
      else {
        val (part1, part2) = arr.splitAt(arr.length/2)
        // "sorted1" and "sorted2" will be the sorted results from lower level
        val sorted1 = new M[Array[T]]
        val sorted2 = new M[Array[T]]
        site(
          go { case sorted1(arr1) + sorted2(arr2) => sorted(arrayMerge(arr1, arr2)) }
        )
        // emit lower-level mergesort
        mergesort(part1, sorted1) + mergesort(part2, sorted2)
      }
  }
)
// sort our array at top level, assuming `finalResult: M[Array[T]]`
mergesort((array, finalResult))

```

The complete working example of concurrent merge-sort is in the file [MergesortSpec.scala](https://github.com/winitzki/joinrun-scala/blob/master/benchmark/src/test/scala/code/winitzki/benchmark/MergesortSpec.scala).

