package code.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue

import code.chymyst.jc._
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.collection.JavaConverters.asScalaIteratorConverter
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
    val n = 4 // The number of rendezvous participants needs to be known in advance, or else we don't know how long still to wait for rendezvous.
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
    //  minOfRows.foreach(y => println(s"min at ${y.point._1} is ${y.value} or $y"))
    //  maxOfCols.foreach(y => println(s"max at ${y.point._2} is ${y.value} or $y"))

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

    val barrier = b[Unit,Unit]
    val counterInit = m[Unit]
    val counter = b[Int,Unit]
    val interpret = m[()=>Unit]

    val minFoundAt = m[PointAndValue]
    val maxFoundAt = m[PointAndValue]
    val saddlePoints = m[List[PointAndValue]]

    val end = m[Unit]
    val done = b[Unit, List[PointAndValue]]

    sealed trait ComputeRequest
    case class MinOfRow( row: Int) extends ComputeRequest
    case class MaxOfColumn(column: Int) extends ComputeRequest

    case class LogData (c: ComputeRequest, pv: PointAndValue)
    val logFile = new ConcurrentLinkedQueue[LogData]

    def minR(row: Int)(): Unit = {
      val pv = seqMinR(row, pointsWithValues)
      minFoundAt(pv)
      logFile.add(LogData(MinOfRow(row), pv))
      ()
    }
    def maxC(col: Int)(): Unit = {
      val pv = seqMaxC(col, pointsWithValues)
      maxFoundAt(pv)
      logFile.add(LogData(MaxOfColumn(col), pv))
      ()
    }
    val results = getSaddlePointsSequentially(pointsWithValues)
    // results.foreach(y => println(s"saddle point at $y"))

    site(sp)(
      go { case interpret(work) => work(); barrier(); end() },
      // this reaction will be run n times because we emit n molecules `interpret` with various computation tasks

      go { case barrier(_, r) + counterInit(_) => // this reaction will consume the very first barrier molecule emitted
        counter(1)
        r()
      },
      go { case saddlePoints(sps) + minFoundAt(pv1) + maxFoundAt(pv2) if pv1 == pv2 => // the key matching happens here.
        saddlePoints(pv1::sps)
      },
      go { case barrier(_, r1) + counter(k, r2) => // the `counter` molecule holds the number (`k`) of the reactions/computations triggered by interpret
        // that have executed so far
        if (k + 1 < 2*n) { // 2*n is amount of preliminary tasks of computation (emitted originally by interpret)
          counter(k+1)
          r2()
          r1()
        }
        else {
          Thread.sleep(500.toLong) // Can we avoid this sleep? Should we?
          // now we have enough to report immediately the results!
          end() + counterInit()
        }
      },
      go { case end(_) + done(_, r) + saddlePoints(sps)  => r(sps) }
    )

    dim.foreach(i => interpret(minR(i)) + interpret(maxC(i)))
    counterInit()
    saddlePoints(Nil)
    done.timeout(1000 millis)().toList.flatten.toSet shouldBe results.toSet

    val events: IndexedSeq[LogData] = logFile.iterator().asScala.toIndexedSeq
    println("\nLogFile START"); events.foreach { case(LogData(c, pv)) => println(s"$c  $pv") }; println("LogFile END") // comment out to see what's going on.

    sp.shutdownNow()

  }
}