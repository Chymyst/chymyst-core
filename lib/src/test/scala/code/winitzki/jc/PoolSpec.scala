package code.winitzki.jc

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.{PatienceConfig,Waiter}
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}
import org.scalactic.source.Position

class PoolSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(1500, Millis)

  val patienceConfig = PatienceConfig(timeout = Span(500, Millis))

  behavior of "fixed thread pool"

  it should "run a task on a separate thread" in {
    val waiter = new Waiter

    val tp = new FixedPool(2)

    tp.runClosure {
      waiter.dismiss()

      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => ()
      }
    }

    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

  it should "interrupt a thread when shutting down" in {
    val waiter = new Waiter

    val tp = new FixedPool(2)

    tp.runClosure {
      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => waiter.dismiss()
        case other: Exception =>
          other.printStackTrace()
          waiter { false shouldEqual true }
      }
    }
    Thread.sleep(20)

    tp.shutdownNow()

    waiter.await()(patienceConfig, implicitly[Position])
  }

  behavior of "cached thread pool"

  it should "run a task on a separate thread" in {
    val waiter = new Waiter

    val tp = new CachedPool(2)

    tp.runClosure {
      waiter.dismiss()

      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => ()
      }
    }

    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

  it should "interrupt a thread when shutting down" in {
    val waiter = new Waiter

    val tp = new CachedPool(2)

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
    Thread.sleep(20)

    tp.shutdownNow()

    waiter.await()(patienceConfig, implicitly[Position])
  }

}
