package code.winitzki.jc

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.Waiter
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

class JPoolSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  it should "run a task on a separate thread" in {
    val waiter = new Waiter

    val tp = new ReactionPool(2)

    tp.runClosure {
      waiter.dismiss()

      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => ()
      }
    }

    waiter.await()

    tp.shutdownNow()
  }

  it should "interrupt a thread when shutting down" in {
    val waiter = new Waiter

    val tp = new ReactionPool(2)

    tp.runClosure {
      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => waiter.dismiss()
        case other: Exception =>
          other.printStackTrace()
          waiter.dismiss()
      }
    }
    Thread.sleep(200)

    tp.shutdownNow()

    waiter.await()
  }

}
