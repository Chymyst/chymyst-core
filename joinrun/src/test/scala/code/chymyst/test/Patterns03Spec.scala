package code.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue

import code.chymyst.jc._
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.collection.JavaConverters.asScalaIteratorConverter

class Patterns03Spec extends FlatSpec with Matchers with BeforeAndAfterEach {

  var tp: Pool = _

  override def beforeEach(): Unit = {
    tp = new SmartPool(4)
  }

  override def afterEach(): Unit = {
    tp.shutdownNow()
  }

  behavior of "readersWriter"

  it should "implement n shared readers 1 exclusive writer" in {
    val supplyLineSize = 20

    sealed trait Lock { val name: String }
    case class ReaderLock(override val name: String, count: Int) extends Lock
    case class WriterLock(override val name: String) extends Lock // a singleton actually, though not enforced here

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

    def useResource(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)
    def waitForUserRequest(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)
    def visitCriticalSection(l: Lock): Unit = {
      logFile.add(LockAcquisition(l.name))
      useResource()
    }
    def leaveCriticalSection(l: Lock): Unit = {
      logFile.add(LockRelease(l.name))
      ()
    }

    val count = m[Int]
    val readerCount = m[Int]

    val check = b[Unit, Unit] // blocking Unit, only blocking molecule of the example.

    val readers = List("A", "B", "C", "D" ).toIndexedSeq
    val readerExit = m[String]
    val reader = m[String]
    val writerName = "exclusive-writer"
    val writer = m[Unit]

    site(tp)(
      go { case writer(_) + readerCount(0) + count(n) if n > 0 =>
        visitCriticalSection(WriterLock(writerName))
        writer()
        count(n - 1)
        readerCount(0)
        leaveCriticalSection(WriterLock(writerName))
        waitForUserRequest() // gives a chance to readers to do some work
      },
      go { case count(0) + readerCount(0) + check(_, r) => r() }, // readerCount(0) condition ensures we end when all locks are released.

      go { case readerCount(n) + readerExit(name)  =>
        readerCount(n - 1)
        leaveCriticalSection(ReaderLock(name, 0)) // undefined count
        waitForUserRequest() // gives a chance to writer to do some work
        reader(name)
      },
      go { case readerCount(n) + reader(name)  =>
        readerCount(n+1)
        visitCriticalSection(ReaderLock(name, n))
        readerExit(name)
      }
    )
    readerCount(0)
    readers.foreach(n => reader(n))
    writer()
    count(supplyLineSize)

    check()
    val result: IndexedSeq[LockEvent] = logFile.iterator().asScala.toIndexedSeq
    // result.foreach(println) // comment out to see what's going on.
    val resultWithIndices: IndexedSeq[(LockEvent, Int)] = result.zipWithIndex

    // each lock writer acquisition is followed by a writer release.
    resultWithIndices.foreach {
      case (event: LockAcquisition, i: Int) if event.name == writerName => resultWithIndices(i+1)._1 shouldBe LockRelease(writerName)
      case _ =>
    }
    // Number of locks being acquired is same as number being released (the other ones as there are only two types of events)
    val acquiredLocks = result.collect{case (event: LockAcquisition) => 1}.sum
    acquiredLocks * 2 shouldBe result.size

    // TODO: could finesse a test that a reader lock is never acquired twice before being released.
  }
}