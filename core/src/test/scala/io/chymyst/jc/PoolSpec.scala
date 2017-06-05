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
    val tp = BlockingPool(2)

    tp.poolSizeLimit should be < n
    tp.currentPoolSize shouldEqual 2

    (1 to tp.poolSizeLimit + 2).foreach (_ => tp.startedBlockingCall(false))

    tp.currentPoolSize shouldEqual tp.poolSizeLimit

    tp.shutdownNow()
  }

  behavior of "fixed thread pool"

  it should "run a task on a separate thread" in {
    val waiter = new Waiter

    val tp = FixedPool(2)

    tp.executionContext.isInstanceOf[ExecutionContext] shouldEqual true

    tp.runReaction({
      waiter.dismiss()

      try {
        Thread.sleep(10000000) // this should not time out
      } catch {
        case _: InterruptedException => ()
      }
    })

    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }
/*
  it should "interrupt a thread when shutting down" in {
    val waiter = new Waiter

    val tp = FixedPool(2)

    tp.runReaction({
      try {
        Thread.sleep(10000000) // this should not time out

      } catch {
        case _: InterruptedException => waiter.dismiss()
        case other: Exception =>
          other.printStackTrace()
          waiter {
            false shouldEqual true;
            ()
          }
      }
    })
    Thread.sleep(20)

    tp.shutdownNow()

    waiter.await()(patienceConfig, implicitly[Position])
  }
*/
  behavior of "Chymyst thread"

  it should "return empty info by default" in {
    val tp = BlockingPool()
    val thread = new ChymystThread(() ⇒ (), tp)
    thread.reactionInfo shouldEqual Core.NO_REACTION_INFO_STRING

    thread.getName shouldEqual "BlockingPool:tp,worker_thread:0"
  }

  behavior of "fixed pool"

  it should "initialize with default settings" in {
    val tp = FixedPool()
    tp.name shouldEqual "tp"
    tp.toString shouldEqual "FixedPool:tp"
    tp.parallelism shouldEqual cpuCores
    tp.executionContext.isInstanceOf[ExecutionContext] shouldEqual true
    tp.close()
  }

  it should "initialize with specified settings" in {
    val tp = FixedPool(313)
    tp.name shouldEqual "tp"
    tp.toString shouldEqual "FixedPool:tp"
    tp.parallelism shouldEqual 313
    tp.executionContext.isInstanceOf[ExecutionContext] shouldEqual true
    tp.close()
  }

  behavior of "blocking pool"

  it should "initialize with default CPU core parallelism" in {
    val tp = BlockingPool()
    tp.name shouldEqual "tp"
    tp.toString shouldEqual "BlockingPool:tp"
    tp.currentPoolSize shouldEqual cpuCores
    tp.poolSizeLimit should be > 1000
    tp.executionContext.isInstanceOf[ExecutionContext] shouldEqual true
    tp.close()
  }

  it should "initialize with specified parallelism" in {
    val tp = BlockingPool(313)
    tp.name shouldEqual "tp"
    tp.toString shouldEqual "BlockingPool:tp"
    tp.currentPoolSize shouldEqual 313
    tp.poolSizeLimit should be > 1000
    tp.executionContext.isInstanceOf[ExecutionContext] shouldEqual true
    tp.close()
  }
}
