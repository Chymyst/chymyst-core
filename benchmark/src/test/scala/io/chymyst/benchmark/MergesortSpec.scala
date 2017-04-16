package io.chymyst.benchmark

import io.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}

import scala.annotation.tailrec
import scala.collection.mutable

import Common._

class MergesortSpec extends FlatSpec with Matchers {

  // auxiliary functions for merge-sort tests

  // this object is not used now
  object amCounter {
    var c: Int = 0

    def inc(): Unit = {
      synchronized {
        c += 1
      }
    }
  }

  type Coll[T] = IndexedSeq[T]

  def arrayMerge[T: Ordering](arr1: Coll[T], arr2: Coll[T]): Coll[T] = {
    val id = amCounter.c
    //      amCounter.inc() // avoid this for now - this is a debugging tool
    val wantToLog = false // (arr1.length > 20000 && arr1.length < 41000)
    if (wantToLog) println(s"${System.currentTimeMillis} start merging #$id")

    val result = new mutable.ArraySeq[T](arr1.length + arr2.length) // just to allocate space

    def isLess(x: T, y: T) = implicitly[Ordering[T]].compare(x, y) < 0

    // will now modify result
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
    if (wantToLog) println(s"${System.currentTimeMillis} finished merging #$id")
    result.toIndexedSeq
  }

  def performMergeSort[T: Ordering](array: Coll[T], threads: Int = 8): Coll[T] = {

    val finalResult = m[Coll[T]]
    val getFinalResult = b[Unit, Coll[T]]
    val reactionPool = new FixedPool(threads)
    val sitePool = new FixedPool(threads)

    site(sitePool, sitePool)(
      go { case finalResult(arr) + getFinalResult(_, r) => r(arr) }
    )

    // recursive molecule that will define the reactions at one level lower

    val mergesort = m[(Coll[T], M[Coll[T]])]

    site(reactionPool, sitePool)(
      go { case mergesort((arr, resultToYield)) =>
        if (arr.length <= 1) resultToYield(arr)
        else {
          val (part1, part2) = arr.splitAt(arr.length / 2)
          // "sorted1" and "sorted2" will be the sorted results from the lower level
          val sorted1 = m[Coll[T]]
          val sorted2 = m[Coll[T]]
          site(reactionPool, sitePool)(
            go { case sorted1(x) + sorted2(y) =>
              resultToYield(arrayMerge(x, y))
            }
          )
          // emit `mergesort` with the lower-level `sorted` result molecules
          mergesort((part1, sorted1)) + mergesort((part2, sorted2))
        }
      }
    )
    // sort our array: emit `mergesort` at top level
    mergesort((array, finalResult))
    mergesort.setLogLevel(0)

    val result = getFinalResult()
    reactionPool.shutdownNow()
    sitePool.shutdownNow()
    result
  }

  behavior of "merge sort"

  it should "merge arrays correctly" in {
    arrayMerge(IndexedSeq(1, 2, 5), IndexedSeq(3, 6)) shouldEqual IndexedSeq(1, 2, 3, 5, 6)
  }

  it should "sort an array using concurrent merge-sort correctly with one thread" in {

    val count = 10
    val threads = 1

    val arr = Array.fill[Int](count)(scala.util.Random.nextInt(count))
    val expectedResult = arr.sorted

    performMergeSort(arr, threads) shouldEqual expectedResult
  }

  it should "sort an array using concurrent merge-sort correctly with many threads" in {

    val count = 10
    val threads = 8

    val arr = Array.fill[Int](count)(scala.util.Random.nextInt(count))
    val expectedResult = arr.sorted

    performMergeSort(arr, threads) shouldEqual expectedResult
  }

  it should "perform concurrent merge-sort without deadlock" in { // This has been a race condition in a MessageDigest global object.
    val count = 5
    (1 to 2000).foreach { i ⇒
      val arr = Array.fill[Int](count)(scala.util.Random.nextInt(count))
      performMergeSort(arr, 8)
    }
  }

  it should "sort an array using concurrent merge-sort more quickly with many threads than with one thread" in {

    val count = 20000
    // 1000000
    val threads = 8 // typical thread utilization at 600%

    val arr = Array.fill[Int](count)(scala.util.Random.nextInt(count))

    val result = timeWithPriming {
      performMergeSort(arr, threads)
      ()
    }
    println(s"concurrent merge-sort test with count=$count and $threads threads took $result ms")

    val result1 = timeWithPriming {
      performMergeSort(arr, 1)
      ()
    }
    println(s"concurrent merge-sort test with count=$count and 1 threads took $result1 ms")
  }

}
