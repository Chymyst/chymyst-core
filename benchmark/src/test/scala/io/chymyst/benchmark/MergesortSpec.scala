package io.chymyst.benchmark

import io.chymyst.benchmark.Common._
import io.chymyst.benchmark.MergeSort._
import io.chymyst.test.LogSpec

class MergesortSpec extends LogSpec {

  // auxiliary functions for merge-sort tests

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
      performMergeSort(arr, 4)
    }
  }

  it should "sort an array using concurrent merge-sort more quickly with many threads than with one thread" in {

    val count = 10000
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
