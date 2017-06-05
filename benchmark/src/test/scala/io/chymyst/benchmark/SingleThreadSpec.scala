package io.chymyst.benchmark

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}
import java.util.{Timer, TimerTask}

import io.chymyst.jc.{FixedPool, withPool}
import io.chymyst.test.LogSpec

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}

class SingleThreadSpec extends LogSpec {

  behavior of "single-thread pool"

  val counter = new AtomicInteger()

  def increment(): Unit = {
    counter.incrementAndGet()
    ()
  }

  val n = 1000000

  it should "schedule tasks" in {

    counter.set(0)
    withPool(FixedPool(1)) { tp =>
      (1 to n).foreach { _ => tp.runReaction(incrementTask.run()) }
      val done = Promise[Unit]()
      tp.runReaction(doneTask(done).run())
      Await.result(done.future, Duration.Inf)
      counter.get() shouldEqual n
    }.get
  }

  def incrementTask = new TimerTask {
    override def run(): Unit = increment()
  }

  def doneTask(done: Promise[Unit]) = new TimerTask {
    override def run(): Unit = {
      done.success(())
      ()
    }
  }

  behavior of "timer"

  it should "schedule tasks" in {
    counter.set(0)
    val timer = new Timer()
    counter.get() shouldEqual 0
    (1 to n).foreach { _ => timer.schedule(incrementTask, 0) }
    val done = Promise[Unit]()
    timer.schedule(doneTask(done), 10)
    Await.result(done.future, Duration.Inf)
    counter.get() shouldEqual n
  }

  behavior of "thread executor"

  def incrementRunnable: Runnable = { () ⇒ increment() }

  it should "schedule tasks" in {
    val queue = new LinkedBlockingQueue[Runnable]
    val secondsToRecycleThread = 1L
    val executor = new ThreadPoolExecutor(1, 1, secondsToRecycleThread, TimeUnit.SECONDS, queue)
    executor.allowCoreThreadTimeOut(true)

    counter.set(0)
    counter.get() shouldEqual 0
    (1 to n).foreach { _ => executor.execute(incrementRunnable) }
    val done = Promise[Unit]()
    executor.execute({ () ⇒
      done.success(())
      ()
    })
    Await.result(done.future, Duration.Inf)
    counter.get() shouldEqual n
  }
}
