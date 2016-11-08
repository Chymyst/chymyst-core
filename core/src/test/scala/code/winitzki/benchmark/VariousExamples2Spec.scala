package code.winitzki.benchmark

import java.time.temporal.ChronoUnit
import java.time.LocalDateTime

import code.winitzki.jc.JProcessPool

import scala.collection.mutable
import code.winitzki.jc.JoinRun._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.annotation.tailrec
import scala.reflect.ClassTag

class VariousExamples2Spec extends FlatSpec with Matchers with TimeLimitedTests with BeforeAndAfter {

  val timeLimit = Span(30000, Millis)

  val warmupTimeMs = 50

  def elapsed(initTime: LocalDateTime): Long = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)

  def timeThis(task: => Unit): Long = {
    val initTime = LocalDateTime.now
    task
    elapsed(initTime)
  }

  def timeWithPriming(task: => Unit): Long = {
    val prime1 = timeThis{task}
    val prime2 = timeThis{task}
    val result = timeThis{task}
//    println(s"timing with priming: prime1 = $prime1, prime2 = $prime2, result = $result")
    (result + prime2 + 1)/2
  }

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  var initTimeAll = LocalDateTime.now

  before {
    initTimeAll = LocalDateTime.now
  }

  after {
    println(s"Elapsed time: ${elapsed(initTimeAll)}")
  }

  // auxiliary functions for merge-sort tests

  object amCounter {
    var c:Int = 0
    def inc: Unit = {
      synchronized {
        c += 1
      }
    }
  }

  def arrayMerge[T : Ordering : ClassTag](arr1: Array[T], arr2: Array[T]): Array[T] = {
    val id = amCounter.c
    //      amCounter.inc // avoid this for now - this is a debugging tool
    val wantToLog = false // (arr1.length > 20000 && arr1.length < 41000)
    if (wantToLog) println(s"${System.currentTimeMillis} start merging #$id")

    val result = new mutable.ArraySeq[T](arr1.length + arr2.length) // just to allocate space

    def isLess(x: T, y: T) = implicitly[Ordering[T]].compare(x,y) < 0

    // will now modify result
    @tailrec
    def mergeRec(i1 : Int, i2: Int, i: Int): Unit = {
      if (i1 == arr1.length && i2 == arr2.length) ()
      else {
        val (x, newI1, newI2) = if (i1 < arr1.length && (i2 == arr2.length || isLess(arr1(i1), arr2(i2))))
          (arr1(i1), i1+1, i2) else (arr2(i2), i1, i2+1)
        result(i) = x
        mergeRec(newI1, newI2, i+1)
      }
    }
    mergeRec(0,0,0)
    if (wantToLog) println(s"${System.currentTimeMillis} finished merging #$id")
    result.toArray
  }

  def performMergeSort[T : Ordering : ClassTag](array: Array[T], threads: Int = 8): Array[T] = {

    val finalResult = ja[Array[T]]
    val getFinalResult = js[Unit, Array[T]]

    join(
      &{ case finalResult(arr) + getFinalResult(_, r) => r(arr) }
    )

    // recursive molecule that will define the reactions at one level

    val mergesort = new JA[(Array[T], JA[Array[T]])]

    val tp = new JProcessPool(threads)
    join(
      tp{
        case mergesort((arr, resultToYield)) =>
          if (arr.length <= 1) resultToYield(arr)
          else {
            val (part1, part2) = arr.splitAt(arr.length/2)
            // "sorted1" and "sorted2" will be the sorted results from lower level
            val sorted1 = new JA[Array[T]]
            val sorted2 = new JA[Array[T]]
            join(
              tp{ case sorted1(x) + sorted2(y) =>
                resultToYield(arrayMerge(x,y)) }
            )(tp, defaultJoinPool)

            // inject lower-level mergesort
            mergesort(part1, sorted1) + mergesort(part2, sorted2)
          }
      }
    )(tp, defaultJoinPool)
    // sort our array at top level
    mergesort((array, finalResult))

    val result = getFinalResult()
    tp.shutdownNow()
    result
  }

  it should "perform a map/reduce-like computation" in {
    val n = 10

    val initTime = LocalDateTime.now

    val res = ja[List[Int]]
    val r = ja[Int]
    val d = ja[Int]
    val get = js[Unit, List[Int]]

    join(
      &{ case d(n) => r(n*2) },
      &{ case res(list) + r(s) => res(s::list) },
      &{ case get(_, reply) + res(list) => reply(list) }
    )

    (1 to n).foreach(x => d(x))
    val expectedResult = (1 to n).map(_ * 2)
    res(Nil)

    waitSome()
    get().toSet shouldEqual expectedResult.toSet

    println(s"map/reduce test with n=$n took ${elapsed(initTime)} ms")
  }

  it should "merge arrays correctly" in {
    arrayMerge(Array(1,2,5), Array(3,6)) shouldEqual Array(1,2,3,5,6)
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



  it should "sort an array using concurrent merge-sort more quickly with many threads than with one thread" in {

    val count = 100000 // 1000000
    val threads = 8 // typical thread utilization at 600%

    val arr = Array.fill[Int](count)(scala.util.Random.nextInt(count))

    val result = timeWithPriming{ performMergeSort(arr, threads)}
    println(s"concurrent merge-sort test with count=$count and $threads threads took $result ms")

    val result1 = timeWithPriming{ performMergeSort(arr, 1)}
    println(s"concurrent merge-sort test with count=$count and 1 threads took $result1 ms")
  }

  it should "run tasks on many threads much faster than on one thread" in {

    def runWork(threads: Int) = {

      def performWork(): Unit = {
        val n = 100

        (1 to n).foreach(i => (1 to i).foreach(j => (1 to j).foreach(k => math.cos(10000.0))))
      }

      val a = ja[Int]
      val never = js[Unit, Unit]
      val tp = new JProcessPool(threads)
      join(
        tp { case a(c) if c > 0 => performWork(); a(c - 1) },
        tp { case never(_, r) + a(0) => r() }
      )

      (1 to 10).foreach(_ => a(10))
      never()
    }

    val result8 = timeWithPriming{runWork(8)}
    val result1 = timeWithPriming{runWork(1)}

    println(s"with 1 thread $result1 ms, with 8 threads $result8 ms")

    (3 * result8 < result1) shouldEqual true
  }
}
