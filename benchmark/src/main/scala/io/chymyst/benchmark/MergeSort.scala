package io.chymyst.benchmark

// Make all imports explicit, just to see what is the entire set of required imports.
// Do not optimize imports in this file!
import io.chymyst.jc.{+, FixedPool, M, m, B, b, go, Reaction, ReactionInfo, InputMoleculeInfo, AllMatchersAreTrivial, OutputMoleculeInfo, site, EmitMultiple}
import io.chymyst.jc.ConsoleErrorsAndWarningsReporter

import scala.annotation.tailrec
import scala.collection.mutable

object MergeSort {
  type Coll[T] = IndexedSeq[T]

  def arrayMerge[T: Ordering](arr1: Coll[T], arr2: Coll[T]): Coll[T] = {
    val result = new mutable.ArraySeq[T](arr1.length + arr2.length) // just to allocate space

    def isLess(x: T, y: T) = implicitly[Ordering[T]].compare(x, y) < 0

    // Will now modify the `result` array in place.
    @tailrec
    def mergeRec(i1: Int, i2: Int, i: Int): Unit = {
      if (i1 == arr1.length && i2 == arr2.length) ()
      else {
        val (x, newI1, newI2) = if (i1 < arr1.length && (i2 == arr2.length || isLess(arr1(i1), arr2(i2))))
          (arr1(i1), i1 + 1, i2) else (arr2(i2), i1, i2 + 1)
        result(i) = x
        mergeRec(newI1, newI2, i + 1)
      }
    }

    mergeRec(0, 0, 0)
    result.toIndexedSeq
  }

  def performMergeSort[T: Ordering](array: Coll[T], threads: Int = 8): Coll[T] = {

    val finalResult = m[Coll[T]]
    val getFinalResult = b[Unit, Coll[T]]
    val reactionPool = FixedPool(threads)

    val pool2 = FixedPool(threads)

    site(pool2)(
      go { case finalResult(arr) + getFinalResult(_, r) => r(arr) }
    )

    // The `mergesort()` molecule will start the chain reactions at one level lower.

    val mergesort = m[(Coll[T], M[Coll[T]])]

    site(reactionPool)(
      go { case mergesort((arr, resultToYield)) =>
        if (arr.length <= 1) resultToYield(arr)
        else {
          val (part1, part2) = arr.splitAt(arr.length / 2)
          // The `sorted1()` and `sorted2()` molecules will carry the sorted results from the lower level.
          val sorted1 = m[Coll[T]]
          val sorted2 = m[Coll[T]]
          site(reactionPool)(
            go { case sorted1(x) + sorted2(y) =>
              resultToYield(arrayMerge(x, y))
            }
          )
          // emit `mergesort` with the lower-level `sorted` result molecules
          mergesort((part1, sorted1)) + mergesort((part2, sorted2))
        }
      }
    )
    // Sort our array: emit `mergesort()` at top level.
    mergesort((array, finalResult))

    val result = getFinalResult()
    reactionPool.shutdownNow()
    pool2.shutdownNow()
    result
  }

}
