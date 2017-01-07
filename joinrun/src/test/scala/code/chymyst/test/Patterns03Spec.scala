package code.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

import code.chymyst.jc._
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.collection.immutable.IndexedSeq
import scala.language.postfixOps

class Patterns03Spec extends FlatSpec with Matchers with BeforeAndAfterEach {

  var sp: Pool = _

  override def beforeEach(): Unit = {
    sp = new SmartPool(4)
  }

  override def afterEach(): Unit = {
    sp.shutdownNow()
  }

  behavior of "saddle points"

  it should "compute saddle points" in {
    val n = 4
    val nSquare = n*n
    val dim = 0 until n

    val sp = new SmartPool(n)

    val matrix = Array.ofDim[Int](n, n)

    type Point = (Int, Int)
    case class PointAndValue(value: Int, point: Point) extends Ordered[PointAndValue] {
      def compare(that: PointAndValue): Int = this.value compare that.value
    }

    // could be used to generate multiple distinct inputs and compare expectations with result of Chymyst.
    def getRandomArray(sparseParam: Int): Array[Int] =
      // the higher it is the more sparse our matrix will be (less likelihood that some elements are the same)
      Array.fill[Int](nSquare)(scala.util.Random.nextInt(sparseParam * nSquare))

    def arrayToMatrix(a: Array[Int], m: Array[Array[Int]]): Unit =
      for (i <- dim) { dim.foreach( j => m(i)(j) = a(i * n + j)) }

    def seqMinR(i: Int, pointsWithValues: Array[PointAndValue]): PointAndValue =
       pointsWithValues.filter { case PointAndValue(v, (r, c)) => r == i }.min

    def seqMaxC(i: Int, pointsWithValues: Array[PointAndValue]): PointAndValue =
      pointsWithValues.filter { case PointAndValue(v, (r, c)) => c == i }.max

    def getSaddlePointsSequentially(pointsWithValues: Array[PointAndValue]): IndexedSeq[PointAndValue] = {
      val minOfRows = for { i <- dim } yield seqMinR(i, pointsWithValues)
      val maxOfCols = for { i <- dim } yield seqMaxC(i, pointsWithValues)

      // now intersect minOfRows with maxOfCols using the positions we keep track of.
      minOfRows.filter(minElem => maxOfCols(minElem.point._2).point._1 == minElem.point._1)
    }

    val sample =
      Array(12, 3, 11, 21,
        14, 7, 57, 26,
        61, 37, 53, 59,
        55, 6, 12, 12)
    arrayToMatrix(sample, matrix)
    val pointsWithValues = matrix.flatten.zipWithIndex.map{ case(x: Int, y: Int) => PointAndValue(x, (y/n, y % n) )}
    // print input matrix
    for (i <- dim) { println(dim.map(j => sample(i * n + j)).mkString(" "))}

    val tasksCompleted = m[Int]
    val computeMinOrMax = m[()=>Unit] // first pass
    val verifySaddlePoint = m[()=>Unit] // second and final pass

    val foundAt = m[PointAndValue]

    val saddlePoints = m[List[PointAndValue]]

    val bitmask: Array[Array[AtomicBoolean]] = Array.fill(n, n)( new AtomicBoolean(true) ) // intentional shared memory
    val end = m[Unit]

    val done = b[Unit, List[PointAndValue]]

    sealed trait ComputeRequest
    case class MinOfRow( row: Int) extends ComputeRequest
    case class MaxOfColumn(column: Int) extends ComputeRequest

    case class LogData (c: ComputeRequest, pv: PointAndValue)
    // val logFile = new ConcurrentLinkedQueue[LogData] remove corresponding to use and trace

    // very fast test, assuming O(1) direct access to bitmask, which is shared memory that is writable.
    // This is totally thread unsafe, but assuming program correctness, 2 threads may write to bitmask at same time or concurrently
    // but by design, never at the same location, except for rejecting a saddle point (a column and row could simultaneously write
    // at bitmask(0,0) value false to reject.
    // Important!: We want 2 concurrent writes of value false when former value is true to end up with value false.
    def checkSaddle(row: Int)(): Unit = {
      val pv = seqMinR(row, pointsWithValues)
      // purposefully avoid chemistry as it's expected that copying full bitmap is prohibitive!
      if (bitmask(row)(pv.point._2).get()) foundAt(pv)
    }

    // a sequential task executed highly concurrently if threads available is large
    def minR(row: Int)(): Unit = {
      val pv = seqMinR(row, pointsWithValues) // this should decompose further into chemistry to execute in O(log n) rather than O(n)
      val k = pv.point._2
      // setting bitmask below purposefully avoids chemistry as it's expected that copying full bitmap is prohibitive!
      for (j <-  dim if j != k) { bitmask(row)(j).set(false) }
      // logFile.add(LogData(MinOfRow(row), pv))
      ()
    }

    // a sequential task executed highly concurrently if threads available is large
    def maxC(col: Int)(): Unit = {
      val pv = seqMaxC(col, pointsWithValues) // this should decompose further into chemistry to execute in O(log n) rather than O(n)
      val k = pv.point._1
      for (i <-  dim if i != k) { bitmask(i)(col).set(false)}
      // logFile.add(LogData(MaxOfColumn(col), pv))
      ()
    }
    val sequentialResults = getSaddlePointsSequentially(pointsWithValues)
    sequentialResults.foreach(y => println(s"saddle point at $y computed sequentially"))

    site(sp)(
      go { case computeMinOrMax(task) + tasksCompleted(k) => // 1st pass, identify all mins and maxs, coordinate when all done
        task()
        if (k < 2*n -1) {
          tasksCompleted(k+1)
        }
        else {
          dim.foreach{i => verifySaddlePoint(checkSaddle(i))}
          tasksCompleted(0)
        }
      },
      go { case saddlePoints(sps) + foundAt(pv)  =>
        saddlePoints(pv::sps)
      },
      go { case verifySaddlePoint(task) + tasksCompleted(k) => // 2nd pass starts only once 1st pass is complete, now reap the saddles
        task()
        if (k < n -1) tasksCompleted(k+1)
        else end()
      },

      go { case end(_) + done(_, r) + saddlePoints(sps) => r(sps) }
    )

    dim.foreach{i => computeMinOrMax(minR(i)) + computeMinOrMax(maxC(i))}
    tasksCompleted(0)
    saddlePoints(Nil)
    done.timeout(200 millis)().toList.flatten.toSet  shouldBe sequentialResults.toSet
    // val events: IndexedSeq[LogData] = logFile.iterator().asScala.toIndexedSeq
    // println("\nLogFile START"); events.foreach { case(LogData(c, pv)) => println(s"$c  $pv") }; println("LogFile END") // comment out to see what's going on.

    sp.shutdownNow()

  }
}