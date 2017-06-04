package io.chymyst.jc

import io.chymyst.test.LogSpec
import org.scalactic.source.Position
import org.scalatest.concurrent.Waiters.{PatienceConfig, Waiter}
import org.scalatest.time.{Millis, Span}

import scala.concurrent.ExecutionContext

class PoolSpec extends LogSpec {

  val patienceConfig = PatienceConfig(timeout = Span(500, Millis))

  behavior of "BlockingPool"

  it should "refuse to increase the thread pool beyond its limit" in {
    val n = 1065
    val tp = new BlockingPool(2)

    tp.maxPoolSize should be < n
    tp.currentPoolSize shouldEqual 2

    (1 to tp.maxPoolSize + 2).foreach (_ => tp.startedBlockingCall(false))

    tp.currentPoolSize shouldEqual tp.maxPoolSize

    tp.shutdownNow()
  }

  behavior of "fixed thread pool"

  it should "run a task on a separate thread" in {
    val waiter = new Waiter

    val tp = new FixedPool(2)

    tp.executionContext.isInstanceOf[ExecutionContext] shouldEqual true

    tp.runReaction({
      waiter.dismiss()

      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case _: InterruptedException => ()
      }
    })

    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

  it should "interrupt a thread when shutting down" in {
    val waiter = new Waiter

    val tp = new FixedPool(2)

    tp.runReaction({
      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case _: InterruptedException => waiter.dismiss()
        case other: Exception =>
          other.printStackTrace()
          waiter { false shouldEqual true ; () }
      }
    })
    Thread.sleep(20)

    tp.shutdownNow()

    waiter.await()(patienceConfig, implicitly[Position])
  }

  behavior of "Chymyst thread"

  it should "return empty info by default" in {
    val tp = new BlockingPool()
    val thread = new ChymystThread(() ⇒ (), tp)
    thread.reactionInfo shouldEqual Core.NO_REACTION_INFO_STRING
  }

  behavior of "blocking pool"

  it should "initialize with default CPU core parallelism" in {
    val tp = new BlockingPool()

    tp.currentPoolSize shouldEqual cpuCores
    tp.executionContext.isInstanceOf[ExecutionContext] shouldEqual true
    tp.close()
  }

}
