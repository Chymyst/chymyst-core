package io.chymyst.test

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

import io.chymyst.jc._
import org.scalatest.{BeforeAndAfterEach, Matchers}

import scala.annotation.tailrec
import scala.collection.JavaConverters.asScalaIteratorConverter

class Patterns03Spec extends LogSpec with Matchers with BeforeAndAfterEach {

  var tp: Pool = _

  override def beforeEach(): Unit = {
    tp = new BlockingPool(4)
  }

  override def afterEach(): Unit = {
    tp.shutdownNow()
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

    site(tp)(
      go { case writer(name) + readerCount(0) + count(n) if n > 0 =>
        visitCriticalSection(name)
        writer(name)
        count(n - 1)
        leaveCriticalSection(name)
        readerCount(0)
      },
      go { case count(0) + readerCount(0) + check(_, r) => r() }, // readerCount(0) condition ensures we end when all locks are released.

      go { case readerCount(n) + readerExit(name) =>
        readerCount(n - 1)
        leaveCriticalSection(name)
        waitForUserRequest() // gives a chance to writer to do some work
        reader(name)
      },
      go { case readerCount(n) + reader(name) =>
        readerCount(n + 1)
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
    val acquiredLocks = events.collect { case (event: LockAcquisition) => 1 }.sum
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

  behavior of "fork/join"

  def doForkJoin[R, T](init: T, fork: T ⇒ Either[List[T], R], aggr: (R, R) ⇒ R, zero: R, done: M[R]): Unit = {

    type Counter = SimpleFraction

    val res = m[(R, Counter)]
    val task = m[(T, Counter)]

    site(tp)(
      go { case task((t, c)) ⇒
        fork(t) match {
          case Left(ts) ⇒
            val total = ts.length
            if (total > 0)
              ts.foreach(x ⇒ task((x, c / ts.length)))
            else
              res((zero, c))
          case Right(r) ⇒ res((r, c))
        }
      },
      go { case res((x, a)) + res((y, b)) ⇒
        val c = a + b
        val r = aggr(x, y)
        if (c < 1) res((r, c))
        else done(r)
      }
    )
    // Initially, emit one `task` molecule with weight `1`.
    task((init, SimpleFraction(1)))
    // Also emit a zero aggregate value, in case we have no results at all.
    res((zero, SimpleFraction(0)))
  }

  def fork(f: File): Either[List[File], List[Long]] = {
    if (f.isDirectory) {
      Left(f.listFiles().toList)
    }
    else Right(List(f.length))
  }

  def doForkJoinOrdered[R, T](init: T, fork: T ⇒ Either[List[T], R], aggr: (R, R) ⇒ R, zero: R, all_done: M[R]): Unit = {
    val task = m[(T, M[(R, Int)])]
    val local_done = m[(R, Int)]
    site(tp)(
      go { case local_done((r, _)) ⇒ all_done(r) },
      go { case task((t, done)) ⇒
        fork(t) match {
          case Left(ts) ⇒
            val total = ts.length
            if (total > 1) {
              val res = m[(R, Int)]
              site(tp)(
                go { case res((r1, x1)) + res((r2, x2)) ⇒
                  val newR = aggr(r1, r2)
                  val newCount = x1 + x2
                  if (newCount < total) res((newR, newCount))
                  else done((newR, 1))
                }
              )
              ts.foreach(t ⇒ task((t, res)))

            } else if (total == 1) {
              ts.foreach(t ⇒ task((t, done))) // Splitting into 1 sub-tasks is a special case where we do not need to aggregate partial restuls.
            } else {
              // total = 0, no subtasks and no results, we are done
              done((zero, 1))
            }

          case Right(r) ⇒ done((r, 1))
        }
      }
    )
    task((init, local_done))
  }

  def runForkJoinTest(filePath: String, useOrdered: Boolean): List[Long] = {
    type R = List[Long]
    type T = File

    val (done, finished) = Common.litmus[R](tp)

    if (useOrdered)
      doForkJoinOrdered[R, T](new File(filePath), fork, _ ++ _, List(), done)
    else
      doForkJoin[R, T](new File(filePath), fork, _ ++ _, List(), done)
    val result = finished()
    result
  }

  it should "implement fork/join with unordered aggregation" in {
    val result = runForkJoinTest("./core/src/test/resources/fork-join-test", useOrdered = false)
    result.length shouldEqual 4
    result.count(_ == 140L) shouldEqual 4
  }

  it should "implement fork/join with ordered aggregation" in {
    val result = runForkJoinTest("./core/src/test/resources/fork-join-test", useOrdered = true)
    result.length shouldEqual 4
    result.count(_ == 140L) shouldEqual 4
  }

  it should "run fork/join with unordered aggregation on empty list of tasks" in {
    val empty = "./core/src/test/resources/fork-join-test/empty-dir"
    new File(empty).mkdirs()
    val result = runForkJoinTest(empty, useOrdered = false)
    result.length shouldEqual 0
  }

  it should "run fork/join with ordered aggregation on empty list of tasks" in {
    val result = runForkJoinTest("./core/src/test/resources/fork-join-test/empty-dir", useOrdered = true)
    result.length shouldEqual 0
  }

  it should "run fork/join with unordered aggregation on list of 1 subtasks" in {
    val result = runForkJoinTest("./core/src/test/resources/fork-join-test/non-empty-dir", useOrdered = false)
    result.length shouldEqual 1
    result.count(_ == 140L) shouldEqual 1
  }

  it should "run fork/join with ordered aggregation on list of 1 subtasks" in {
    val result = runForkJoinTest("./core/src/test/resources/fork-join-test/non-empty-dir", useOrdered = true)
    result.length shouldEqual 1
    result.count(_ == 140L) shouldEqual 1
  }

  behavior of "SimpleFraction"

  it should "create simple fractions from integers and compare with 1" in {
    val a0 = SimpleFraction(0)
    val a1 = SimpleFraction(1)
    val a2 = SimpleFraction(2)

    a0 < 2 shouldEqual true
    a0 < 1 shouldEqual true
    a0 < 0 shouldEqual false

    a1 < 2 shouldEqual true
    a1 < 1 shouldEqual false
    a1 < 0 shouldEqual false

    a2 < 2 shouldEqual false
    a2 < 1 shouldEqual false
    a2 < 0 shouldEqual false

    SimpleFraction(5, 2) < 2 shouldEqual false
    SimpleFraction(2, 5) < SimpleFraction(3, 7) shouldEqual true
  }

  it should "divide fractions by integers" in {
    SimpleFraction(1, 2) / 2 shouldEqual SimpleFraction(1, 4)
    SimpleFraction(3, 5) / 2 shouldEqual SimpleFraction(3, 10)
    SimpleFraction(3, 5) / 3 shouldEqual SimpleFraction(1, 5)
  }

  it should "add fractions" in {
    SimpleFraction(1, 2) + SimpleFraction(1, 2) shouldEqual SimpleFraction(1)
    SimpleFraction(1, 3) + SimpleFraction(1, 2) shouldEqual SimpleFraction(5, 6)
    SimpleFraction(1, 3) + SimpleFraction(2, 5) shouldEqual SimpleFraction(11, 15)
  }
}

final case class SimpleFraction(num: Int, denom: Int) {
  def <(x: Int): Boolean = this < SimpleFraction(x)

  def <(x: SimpleFraction): Boolean = num * x.denom < x.num * denom

  def +(f: SimpleFraction): SimpleFraction = {
    val SimpleFraction(n, d) = f
    val newNum = num * d + denom * n
    val newDenom = d * denom
    val newGcd = SimpleFraction.gcd(newNum, newDenom)
    SimpleFraction(newNum / newGcd, newDenom / newGcd)
  }

  def /(x: Int): SimpleFraction = {
    val newGcd = SimpleFraction.gcd(num, denom * x)
    SimpleFraction(num / newGcd, denom * x / newGcd)
  }
}

object SimpleFraction {
  def apply(x: Int): SimpleFraction = SimpleFraction(x, 1)

  @tailrec
  def gcd(x: Int, y: Int): Int = if (x == y) x
  else {
    val a = math.min(x, y)
    val b = math.max(x, y)
    if (a == 0) b
    else gcd(b % a, a)
  }
}
