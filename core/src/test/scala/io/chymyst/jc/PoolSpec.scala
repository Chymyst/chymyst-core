package io.chymyst.jc

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.{PatienceConfig, Waiter}
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}
import org.scalactic.source.Position

class PoolSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val emptyReactionInfo = new ChymystThreadInfo()

  val timeLimit = Span(1500, Millis)

  val patienceConfig = PatienceConfig(timeout = Span(500, Millis))

  behavior of "smart pool"

  it should "refuse to increase the thread pool beyond its limit" in {
    val n = 1065
    val tp = new SmartPool(2)

    tp.maxPoolSize should be < n
    tp.currentPoolSize shouldEqual 2

    (1 to tp.maxPoolSize + 2).foreach (_ => tp.startedBlockingCall())

    tp.currentPoolSize shouldEqual tp.maxPoolSize

    tp.shutdownNow()
  }

  behavior of "fixed thread pool"

  it should "run a task on a separate thread" in {
    val waiter = new Waiter

    val tp = new FixedPool(2)

    tp.runClosure({
      waiter.dismiss()

      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => ()
      }
    }, emptyReactionInfo)

    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

  it should "interrupt a thread when shutting down" in {
    val waiter = new Waiter

    val tp = new FixedPool(2)

    tp.runClosure({
      try {
        Thread.sleep(10000000)  // this should not time out

      } catch {
        case e: InterruptedException => waiter.dismiss()
        case other: Exception =>
          other.printStackTrace()
          waiter { false shouldEqual true ; () }
      }
    }, emptyReactionInfo)
    Thread.sleep(20)

    tp.shutdownNow()

    waiter.await()(patienceConfig, implicitly[Position])
  }

  behavior of "smart thread"

  it should "run tasks on ordinary threads" in {
    var x = 0
    new RunnableWithInfo({x = 1}, emptyReactionInfo).run()

    x shouldEqual 1

  }

  it should "run tasks on smart threads and store info" in {
    val waiter = new Waiter

    var x = 0
    val runnable = new RunnableWithInfo({
      x = 1; waiter.dismiss()
    }, emptyReactionInfo)

    runnable.toString shouldEqual emptyReactionInfo.toString

    val smartThread = new ThreadWithInfo(runnable)
    smartThread.chymystInfo shouldEqual None // too early now, the runnable has not yet started
    smartThread.start()

    waiter.await()(patienceConfig, implicitly[Position])

    x shouldEqual 1
    smartThread.chymystInfo shouldEqual Some(emptyReactionInfo)
  }

}
