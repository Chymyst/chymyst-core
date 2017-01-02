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
    def visitCriticalSection(l: Lock): Unit = {
      logFile.add(LockAcquisition(l.name))
      useResource()
    }
    def leaveCriticalSection(l: Lock): Unit = {
      logFile.add(LockRelease(l.name))
      ()
    }

    val count = m[Int]
    val readerToken = m[Unit]
    val readerCount = m[Int]
    val readerExit = m[String]

    val check = b[Unit, Unit] // blocking Unit, only blocking molecule of the example.

    val writer = m[Unit]

    site(tp)(
      go { case writer(_) + readerCount(0) + count(n) if n > 0 =>
        val id = "exclusive-writer"
        visitCriticalSection(WriterLock(id))
        writer()
        count(n - 1)
        readerCount(0)
        leaveCriticalSection(WriterLock(id))
      },
      go { case count(0) + check(_, r) => r() },

      go { case readerCount(n) + readerExit(name)  =>
        readerToken()
        readerCount(n - 1)
        leaveCriticalSection(ReaderLock(name, 0)) // undefined count
      },
      go { case readerCount(n) + readerToken(_)  =>
        readerCount(n+1)
        val id = scala.util.Random.nextInt(2) match { // simulate n readers with a single reaction using 2 values
          case 0 => "A"
          case _ => "B"
        }
        visitCriticalSection(ReaderLock(id, n))
        readerExit(id)
      }

    )
    readerCount(0)
    readerToken()
    writer()
    count(supplyLineSize)

    check()
    val result = logFile.iterator().asScala.toSeq
    result.foreach(println)
  }
}