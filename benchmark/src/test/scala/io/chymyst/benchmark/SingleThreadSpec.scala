package io.chymyst.benchmark

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}
import java.util.{Timer, TimerTask}

import io.chymyst.jc.{FixedPool, withPool}
import io.chymyst.test.LogSpec
import io.chymyst.test.Common._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}

class SingleThreadSpec extends LogSpec {

  def incrementRunnable: Runnable = { () ⇒ increment() }

  behavior of "thread executor with 2 threads"

  it should "schedule tasks" in {
    val queue = new LinkedBlockingQueue[Runnable]
    val secondsToRecycleThread = 1L
    val executor = new ThreadPoolExecutor(2, 2, secondsToRecycleThread, TimeUnit.SECONDS, queue)
    executor.allowCoreThreadTimeOut(true)

    counter.set(0)
    counter.get() shouldEqual 0
    val result = elapsedTimesNs(executor.execute(incrementRunnable), n)
    showStd("lbq execute(), 2 threads", result)
    val done = Promise[Unit]()
    executor.execute({ () ⇒
      done.success(())
      ()
    })
    Await.result(done.future, Duration.Inf)
    counter.get() shouldEqual n
  }

  behavior of "single-thread pool"

  val counter = new AtomicInteger()

  def increment(): Unit = {
    counter.incrementAndGet()
    ()
  }

  val n = 1000000

  it should "schedule tasks" in {

    counter.set(0)
    withPool(FixedPool(1)) { tp ⇒
      val result = elapsedTimesNs(tp.runReaction(s"task $n", incrementTask.run()), n)
      showStd("FixedPool(1).runReaction, 2 threads", result)
      val done = Promise[Unit]()
      tp.runReaction("done", doneTask(done).run())
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
    val result = elapsedTimesNs(timer.schedule(incrementTask, 0), n)
    showStd("java.util.timer.schedule(), 2 threads", result)

    val done = Promise[Unit]()
    timer.schedule(doneTask(done), 10)
    Await.result(done.future, Duration.Inf)
    counter.get() shouldEqual n
  }

  behavior of "thread executor"

  it should "schedule tasks" in {
    val queue = new LinkedBlockingQueue[Runnable]
    val secondsToRecycleThread = 1L
    val executor = new ThreadPoolExecutor(1, 1, secondsToRecycleThread, TimeUnit.SECONDS, queue)
    executor.allowCoreThreadTimeOut(true)

    counter.set(0)
    counter.get() shouldEqual 0
    val result = elapsedTimesNs(executor.execute(incrementRunnable), n)
    showStd("lbq execute(), 1 thread", result)
    val done = Promise[Unit]()
    executor.execute({ () ⇒
      done.success(())
      ()
    })
    Await.result(done.future, Duration.Inf)
    counter.get() shouldEqual n
  }

  behavior of "ltq executor"

  it should "schedule tasks with 2 threads" in {
    val executor = new LtqExecutor(2)
    counter.set(0)
    counter.get() shouldEqual 0
    val result = elapsedTimesNs(executor.execute(incrementRunnable), n)
    showStd("ltq execute(), 2 thread", result)
    val done = Promise[Unit]()
    executor.execute({ () ⇒
      done.success(())
      ()
    })
    Await.result(done.future, Duration.Inf)
    counter.get() shouldEqual n
  }

  it should "schedule tasks" in {
    val executor = new LtqExecutor(1)
    counter.set(0)
    counter.get() shouldEqual 0
    val result = elapsedTimesNs(executor.execute(incrementRunnable), n)
    showStd("ltq execute(), 1 thread", result)
    val done = Promise[Unit]()
    executor.execute({ () ⇒
      done.success(())
      ()
    })
    Await.result(done.future, Duration.Inf)
    counter.get() shouldEqual n
  }
}
