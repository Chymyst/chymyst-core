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

  behavior of "readersWriter"

  it should "implement n shared readers 1 exclusive writer" in {
    val supplyLineSize = 25 // make it high enough to try to provoke race conditions, but not so high that sleeps make the test run too slow.

    sealed trait LockEvent {
      val name: String
      def toString: String
    }
    case class LockAcquisition(override val name: String) extends LockEvent {
      override def toString: String = s"$name enters critical section"
    }
    case class LockRelease(override val name: String) extends LockEvent {
      override def toString: String = s"$name leaves critical section"
    }
    val logFile = new ConcurrentLinkedQueue[LockEvent]

    def useResource(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 4.0 + 1.0).toLong)
    def waitForUserRequest(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 4.0 + 1.0).toLong)
    def visitCriticalSection(name: String): Unit = {
      logFile.add(LockAcquisition(name))
      useResource()
    }
    def leaveCriticalSection(name: String): Unit = {
      logFile.add(LockRelease(name))
      ()
    }

    val count = m[Int]
    val readerCount = m[Int]

    val check = b[Unit, Unit] // blocking Unit, only blocking molecule of the example.

    val readers = "ABCDEFGH".toCharArray.map(_.toString).toVector // vector of letters as Strings.
    // Making readers a large collection introduces lots of sleeps since we count number of writer locks for simulation and the more readers we have
    // the more total locks and total sleeps simulation will have.

    val readerExit = m[String]
    val reader = m[String]
    val writer = m[String]

    site(sp)(
      go { case writer(name) + readerCount(0) + count(n) if n > 0 =>
        visitCriticalSection(name)
        writer(name)
        count(n - 1)
        readerCount(0)
        leaveCriticalSection(name)
      },
      go { case count(0) + readerCount(0) + check(_, r) => r() }, // readerCount(0) condition ensures we end when all locks are released.

      go { case readerCount(n) + readerExit(name)  =>
        readerCount(n - 1)
        leaveCriticalSection(name)
        waitForUserRequest() // gives a chance to writer to do some work
        reader(name)
      },
      go { case readerCount(n) + reader(name)  =>
        readerCount(n+1)
        visitCriticalSection(name)
        readerExit(name)
      }
    )
    readerCount(0)
    readers.foreach(n => reader(n))
    val writerName = "exclusive-writer"
    writer(writerName)
    count(supplyLineSize)

    check()

    val events: IndexedSeq[LockEvent] = logFile.iterator().asScala.toIndexedSeq
    // events.foreach(println) // comment out to see what's going on.
    val eventsWithIndices: IndexedSeq[(LockEvent, Int)] = events.zipWithIndex

    case class LockEventPair(lock: LockAcquisition, unlock: LockRelease) {
      // this validates that there is never double locking by same lock with assumption that all events
      // pertain to the same lock and consequently that the position of the event increases by 1 all the time
      // (no holes). So events should contain [(..., 0), (..., 1), ..., (..., k), (..., k+1), ...]
      def validateLockUsage(events: IndexedSeq[(LockEvent, Int)]): Unit = {
        events.foreach {
          case (event: LockAcquisition, i: Int) =>
            i + 1 should be < events.size // for additional safety when accessing i+1 below and avoid unplanned exceptions on errors
            events(i + 1)._1 shouldBe LockRelease(event.name)
          case _ => // don't care about LockRelease as it's handled above
        }
      }
    }

    // 1) Number of locks being acquired is same as number being released (the other ones as there are only two types of events)
    val acquiredLocks = events.collect{case (event: LockAcquisition) => 1}.sum
    acquiredLocks * 2 shouldBe events.size

    val (writersByName, readersPart) = eventsWithIndices.partition(_._1.name == writerName) // binary split by predicate

    // 2) Each lock writer acquisition is followed by a writer release before acquiring a new writer (ignoring for now interference of readers)
    // we'll reindex the collection by discarding original indices and introducing new ones with zipWithIndex, intentionally discarding spots
    // occupied by readers. This satisfies the limited functionality and strong assumption of validateLockUsage.
    LockEventPair(LockAcquisition(writerName), LockRelease(writerName)).validateLockUsage(writersByName.map(_._1).zipWithIndex)

    // 3) Similarly, a reader lock is never acquired twice before being released.
    val readersByName = readersPart.groupBy(_._1.name).mapValues(x => x.map(_._1)) // general split into map, dropping the original zipIndex (._2)
    readersByName.foreach {
      case ((name, eventsByName)) =>
        LockEventPair(LockAcquisition(name), LockRelease(name)).validateLockUsage(eventsByName.zipWithIndex)
      // add a new index specific to each new reader collection to facilitate comparison of consecutive lock events.
    }
    // 4) no read lock acquisition while a writer has a lock (we cannot use validateLockUsage here as it's too limiting for this purpose,
    // so we don't remap the indices and keep them as is using eventsWithIndices)
    writersByName.foreach {
      case (event: LockAcquisition, i: Int) =>
        i + 1 should be < events.size // for additional safety when accessing i+1 below and avoid unplanned exceptions on errors
        eventsWithIndices(i + 1)._1 shouldBe LockRelease(event.name)
      case _ => // don't care about LockRelease as it's handled above
    }

  }

  it should "compute saddle points" in {
    val n = 4 // The number of rendezvous participants needs to be known in advance, or else we don't know how long still to wait for rendezvous.

    val dim = 0 until n

    val sp = new SmartPool(n)

    val matrix = Array.ofDim[Int](n, n)
    val output = Array.ofDim[Int](n, n)
    for (i <- dim; j <- dim) { output(i)(j) = 0 }

    type Point = (Int, Int)
    case class PointAndValue(value: Int, point: Point) extends Ordered[PointAndValue] {
      def compare(that: PointAndValue): Int = this.value compare that.value
    }

    // could be used to generate multiple distinct inputs and compare expectations with result of Chymyst.
    def getRandomArray(sparseParam: Int, rows: Int): Array[Int] = {
      // the higher it is the more sparse our matrix will be (less likelihood that some elements are the same)
      val dimension = rows * rows
      Array.fill[Int](dimension)(scala.util.Random.nextInt(sparseParam * dimension))
    }
    def arrayToMatrix(rows: Int, a: Array[Int], m: Array[Array[Int]]): Unit = {
      val r = 0 until rows
      for (i <- r; j <- r)
      { m(i)(j) = a(i * rows + j) }
    }

    def seqMinR(i: Int, ran: Range, pointsWithValues: Array[PointAndValue]): PointAndValue =
       pointsWithValues.filter { case PointAndValue(v, (r, c)) => r == i }.min

    def seqMaxC(i: Int, ran: Range, pointsWithValues: Array[PointAndValue]): PointAndValue =
      pointsWithValues.filter { case PointAndValue(v, (r, c)) => c == i }.max


    def getSaddlePointsSequentially(ran: Range, pointsWithValues: Array[PointAndValue]): IndexedSeq[PointAndValue] = {
      val minOfRows = for { i <- ran } yield seqMinR(i, ran, pointsWithValues)
      val maxOfCols = for { i <- ran } yield seqMaxC(i, ran, pointsWithValues)
      minOfRows.foreach(y => println(s"min at ${y.point._1} is ${y.value} or $y"))
      maxOfCols.foreach(y => println(s"max at ${y.point._2} is ${y.value} or $y"))

      // now intersect minOfRows with maxOfCols using the positions we keep track of.
      minOfRows.filter(minElem => maxOfCols(minElem.point._2).point._1 == minElem.point._1)
    }

    val sample =
      Array(12, 3, 11, 21,
        14, 7, 57, 26,
        61, 37, 53, 59,
        55, 6, 12, 12)
    arrayToMatrix(n, sample, matrix)
    val pointsWithValues = matrix.flatten.zipWithIndex.map{ case(x: Int, y: Int) => PointAndValue(x, (y/n, y % n) )}

    matrix.foreach(x => x.foreach(println))

    val barrier = b[Unit,Unit]
    val counterInit = m[Unit]
    val counter = b[Int,Unit]
    val interpret = m[()=>Unit]
    val end = m[Unit]
    val done = b[Unit, Unit]

    sealed trait ComputeRequest
    case class MinOfRow( row: Int) extends ComputeRequest
    case class MaxOfColumn(column: Int) extends ComputeRequest

    val logFile = new ConcurrentLinkedQueue[(ComputeRequest, PointAndValue)]

    def minC(row: Int)(): Unit = {
      val pv = seqMinR(row, dim, pointsWithValues)
      // should emit molecule to increment by 1 at pv (if both min max then it'll be 2)
      logFile.add((MinOfRow(row), pv))
      ()
    }
    def maxC(col: Int)(): Unit = {
      val pv = seqMaxC(col, dim, pointsWithValues)
      logFile.add((MaxOfColumn(col), pv))
      // should emit molecule to increment by 1 at pv (if both min max then it'll be 2)
      ()
    }
    val results = getSaddlePointsSequentially(dim, pointsWithValues)
    results.foreach(y => println(s"saddle point at $y"))

    site(sp)(
      go { case interpret(work) => work(); barrier(); end() }, // this reaction will be run n times because we emit n molecules `interpret` with various
      // computation tasks
      go { case barrier(_, r) + counterInit(_) => // this reaction will consume the very first barrier molecule emitted
        counter(1)
        r() // one reaction has reached the rendezvous point
      },
      go { case barrier(_, r1) + counter(k, r2) => // the `counter` molecule holds the number (k) of the reactions that have reached the rendezvous before
        // this reaction started.
        if (k + 1 < 2*n) { // 2*n is amount of preliminary tasks of computation (interpret)
          counter(k+1)
          r2()
          r1() // `r2()` must be here. Doing `r2()` before emitting `counter(k+1)` would have unblocked some reactions and allowed them to
          // proceed beyond the rendezvous point without waiting for all others.
        }
        else {
          println(s"mins-maxs executed through ${2*n} reactions")
          // now we should have enough to report immediately the results!
          // We could have n*n blocking molecules representing the n*n saddle candidates, have them carry a value of 0, a min and a max increment by 1
          // and ultimately a value of 2 represents a saddle. Here we can unblock all these n*n molecules at once...!?
          end() + counterInit()
        }
      },
      go { case end(_) + done(_, r) => r() }
    )

    dim.foreach(i => interpret(minC(i)) + interpret(maxC(i)))
    counterInit()
    done.timeout(1000 millis)() shouldEqual Some(())

    val events: IndexedSeq[(ComputeRequest, PointAndValue)] = logFile.iterator().asScala.toIndexedSeq
    println("\nLogFile START"); events.foreach { case(c, pv) => println(s"$c  $pv") }; println("LogFile END") // comment out to see what's going on.

    sp.shutdownNow()

  }
}