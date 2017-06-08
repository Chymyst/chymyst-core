package io.chymyst.benchmark

import java.time.LocalDateTime
import scalaxy.streams.optimize

import io.chymyst.jc._
import io.chymyst.test.Common._
import io.chymyst.test.LogSpec

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.util.Random.nextInt

class ReactionDelaySpec extends LogSpec {

  behavior of "reaction overhead and delay times"

  it should "measure simple statistics on reaction delay" in {
    val f = b[Unit, Unit]
    val tp = BlockingPool(4)
    site(tp)(
      go { case f(_, r) => r() }
    )
    val trials = 1000
    val results = (1 to trials).map { _ =>
      val timeInit = System.nanoTime()
      f()
      (System.nanoTime() - timeInit).toDouble
    }.sortBy(-_).drop(trials / 2)
    val (mean, std) = meanAndStdev(results)
    val meanReplyDelay = mean
    println(s"Sequential test without extra delay: Mean reply delay is ${formatNanosToMs(meanReplyDelay)} ms ± ${formatNanosToMs(std)} ms out of ${results.length} trials")
    tp.shutdownNow()
  }

  it should "measure simple statistics on reaction delay with extra delay" in {
    val extraDelay = 1L
    val f = b[Unit, Unit]
    val tp = BlockingPool(4)
    site(tp)(
      go { case f(_, r) =>
        BlockingIdle {
          Thread.sleep(extraDelay)
        }
        r()
      }
    )
    val trials = 1000
    val results = (1 to trials).map { _ =>
      val timeInit = System.nanoTime()
      f()
      (System.nanoTime() - timeInit).toDouble
    }.sortBy(-_).drop(trials / 2)
    val (mean, std) = meanAndStdev(results)
    val meanReplyDelay = mean - 1000000L * extraDelay
    println(s"Sequential test with extra delay: Mean reply delay is ${formatNanosToMs(meanReplyDelay)} ms ± ${formatNanosToMs(std)} ms out of ${results.length} trials")
    tp.shutdownNow()
  }

  it should "measure simple statistics on reaction delay in parallel, with extra delay" in {
    val extraDelay = 1L
    val f = b[Unit, Unit]
    val counter = m[(Int, List[Double])]
    val all_done = b[Unit, List[Double]]
    val done = m[Double]
    val begin = m[Unit]
    val tp = BlockingPool(4)

    val trials = 800

    site(tp)(
      go { case begin(_) =>
        val timeInit = System.nanoTime()
        f()
        val timeElapsed = System.nanoTime() - timeInit
        done(timeElapsed.toDouble)
      },
      go { case all_done(_, r) + counter((0, results)) => r(results) },
      go { case counter((n, results)) + done(res) if n > 0 => counter((n - 1, res :: results)) },
      go { case f(timeOut, r) =>
        BlockingIdle {
          Thread.sleep(extraDelay)
        }
        r()
      }
    )

    counter((trials, Nil))

    (1 to trials).foreach { _ => begin() }
    val result = all_done().sortBy(-_).drop(trials / 2)
    val (mean, std) = meanAndStdev(result)
    val meanReplyDelay = mean - 1000000L * extraDelay
    println(s"Parallel test with extra delay: Mean reply delay is ${formatNanosToMs(meanReplyDelay)} ms ± ${formatNanosToMs(std)} ms out of ${result.length} trials")
    tp.shutdownNow()
  }

  it should "measure statistics on reaction scheduling for non-blocking molecules" in {
    val a = m[Long]
    val c = m[Long]
    val f = b[Unit, Long]
    val tp = BlockingPool(4)
    site(tp)(
      go { case c(x) + f(_, r) => r(x) },
      go { case a(d) =>
        val elapsed = System.nanoTime() - d
        c(elapsed)
      }
    )
    val trials = 10000
    val results = (1 to trials).map { _ =>
      val timeInit = System.nanoTime()
      a(timeInit)
      val timeAfterA = System.nanoTime() - timeInit
      val res = f()
      val timeElapsed = System.nanoTime() - timeInit
      (res, timeAfterA, timeElapsed)
    }
    tp.shutdownNow()

    // Drop first half of values due to warm-up of JVM.
    val resultsStartDelay = results.map(_._1.toDouble).sortBy(-_).drop(trials / 2)
    val resultsEmitDelay = results.map(_._2.toDouble).sortBy(-_).drop(trials / 2)
    val resultsReplyDelay = results.map(_._3.toDouble).sortBy(-_).drop(trials / 2)

    val resultsStartDelay0 = results.map(_._1.toDouble).sortBy(-_).take(trials / 20)
    val resultsEmitDelay0 = results.map(_._2.toDouble).sortBy(-_).take(trials / 20)
    val resultsReplyDelay0 = results.map(_._3.toDouble).sortBy(-_).take(trials / 20)

    val (meanReactionStartDelay, stdevReactionStartDelay) = meanAndStdev(resultsStartDelay)
    val (meanEmitDelay, stdevEmitDelay) = meanAndStdev(resultsEmitDelay)
    val (meanReplyDelay, stdevReplyDelay) = meanAndStdev(resultsReplyDelay)

    val (meanReactionStartDelay0, stdevReactionStartDelay0) = meanAndStdev(resultsStartDelay0)
    val (meanEmitDelay0, stdevEmitDelay0) = meanAndStdev(resultsEmitDelay0)
    val (meanReplyDelay0, stdevReplyDelay0) = meanAndStdev(resultsReplyDelay0)

    println(s"Sequential test of emission and reaction delay (before JVM warm-up): trials = ${resultsStartDelay0.length}, meanReactionStartDelay = ${formatNanosToMicros(meanReactionStartDelay0)} +- ${formatNanosToMicros(stdevReactionStartDelay0)}, meanEmitDelay = ${formatNanosToMicros(meanEmitDelay0)} ± ${formatNanosToMicros(stdevEmitDelay0)}, meanReplyDelay = ${formatNanosToMicros(meanReplyDelay0)} ± ${formatNanosToMicros(stdevReplyDelay0)}")
    println(s"Sequential test of emission and reaction delay (after JVM warm-up): trials = ${resultsStartDelay.length}, meanReactionStartDelay = ${formatNanosToMicros(meanReactionStartDelay)} +- ${formatNanosToMicros(stdevReactionStartDelay)}, meanEmitDelay = ${formatNanosToMicros(meanEmitDelay)} ± ${formatNanosToMicros(stdevEmitDelay)}, meanReplyDelay = ${formatNanosToMicros(meanReplyDelay)} ± ${formatNanosToMicros(stdevReplyDelay)}")

    val power = -1.0
    showRegression("reaction start delay", results.map(_._1.toDouble))
    showRegression("emit delay", results.map(_._2.toDouble))
    showRegression("reply delay", results.map(_._3.toDouble))
  }

  type Result = (Int, Int, Long, Boolean)

  case class MeasurementResult(resultTrueSize: Int, resultFalseSize: Int, timeoutDelayArraySize: Int, noTimeoutMeanShiftArraySize: Int, timeoutDelay: Double, noTimeoutDelay: Double, timeoutMeanShift: Double, noTimeoutMeanShift: Double, printout: String)

  def measureTimeoutDelays(trials: Int, maxTimeout: Int, tp: Pool): List[Result] = {
    val f = b[Long, Unit]
    val counter = m[(Int, List[Result])]
    val all_done = b[Unit, List[Result]]
    val done = m[Result]
    val begin = m[Int]

    site(tp)(
      go { case f(timeOut, r) =>
        BlockingIdle {
          Thread.sleep(timeOut)
        }
        r()
      }
    )

    site(tp)(
      go { case begin(_) =>
        val t1 = nextInt(maxTimeout)
        val t2 = nextInt(maxTimeout)
        val timeInit = System.nanoTime()
        val res = f.timeout(t2.toLong)(t1.millis).isEmpty
        val timeElapsed = System.nanoTime() - timeInit
        done((t1 * 1000, t2 * 1000, timeElapsed / 1000, res))
      },
      go { case all_done(_, r) + counter((0, results)) => r(results) },
      go { case counter((n, results)) + done(res) if n > 0 ⇒ // ignore warning "class M expects 4 patterns"
        counter((n - 1, res :: results))
      }
    )

    counter((trials, Nil))
    (1 to trials).foreach(begin)

    all_done()
  }

  def adjustNanos(x: Double): Double = x.toInt / 1000.0

  def processResults(result: List[Result]): MeasurementResult = {
    val (resultTrue, resultFalse) = result.partition(_._4)

    val resultFalseSize = resultFalse.size
    val resultTrueSize = resultTrue.size

    val timeoutDelayArray = resultTrue.map { case (t1, t2, timeElapsed, _) => timeElapsed - t2 }.filter(_ > 0)
    val timeoutDelay = adjustNanos(timeoutDelayArray.sum / safeSize(timeoutDelayArray.size))
    val noTimeoutDelay = adjustNanos(resultFalse.map { case (t1, t2, timeElapsed, _) => timeElapsed - t2 }.sum / safeSize(resultFalse.size))
    val timeoutMeanShift = adjustNanos(resultTrue.map { case (t1, t2, timeElapsed, _) => timeElapsed - t1 }.sum / safeSize(resultTrue.size))
    val noTimeoutMeanShiftArray = resultFalse.map { case (t1, t2, timeElapsed, _) => timeElapsed - t1 }.filter(_ > 0)
    val noTimeoutMeanShift = adjustNanos(noTimeoutMeanShiftArray.sum / safeSize(noTimeoutMeanShiftArray.size))

    val timeoutDelayArraySize = timeoutDelayArray.size
    val noTimeoutMeanShiftArraySize = noTimeoutMeanShiftArray.size

    val printout =
      s"""Results:   # samples      | delay     | mean shift
         |----------------------------------------------------
         | timeout     $resultTrueSize ($timeoutDelayArraySize items) | $timeoutDelay| $timeoutMeanShift
         |----------------------------------------------------
         | no timeout  $resultFalseSize ($noTimeoutMeanShiftArraySize items) | $noTimeoutDelay| $noTimeoutMeanShift
       """.stripMargin

    MeasurementResult(resultTrueSize, resultFalseSize, timeoutDelayArraySize, noTimeoutMeanShiftArraySize, timeoutDelay, noTimeoutDelay, timeoutMeanShift, noTimeoutMeanShift, printout)
  }

  it should "measure the timeout delay in parallel threads" in {
    val trials = 500
    val maxTimeout = 500

    val tp = BlockingPool(4)

    val result = processResults(measureTimeoutDelays(trials, maxTimeout, tp))

    println(s"Random timeout delay, parallel test ($trials trials, $maxTimeout max timeout):")
    println(result.printout)
    tp.shutdownNow()
  }

  it should "measure the timeout delay in single thread" in {
    val trials = 20
    val maxTimeout = 200

    val tp = FixedPool(4)

    val result = processResults(measureTimeoutDelays(trials, maxTimeout, tp))

    println(s"Random timeout delay, single-thread test ($trials trials, $maxTimeout max timeout):")
    println(result.printout)
    tp.shutdownNow()
  }

  behavior of "blocking reply statistics"

  val total = 10000

  it should "using blocking molecules" in {
    withPool(FixedPool(1)) { tp ⇒
      val f = b[Unit, Long]
      site(tp)(go { case f(_, r) ⇒ r(System.nanoTime()) })
      val results = (1 to total).map { _ ⇒
        val t = System.nanoTime()
        val x = f()
        val y = System.nanoTime()
        (y - x, x - t)
      }
      showFullStatistics("latency of reply delay using blocking molecules", results.map(_._1.toDouble), factor = 200) // about 30 ns
      showFullStatistics("latency of reaction launch using blocking molecules", results.map(_._2.toDouble), factor = 200) // about 30 ns
    }
  }

  it should "using promises" in {
    withPool(FixedPool(1)) { tp ⇒
      val f = m[Promise[Long]]
      site(tp)(go { case f(promise) ⇒ promise.success(System.nanoTime()) })
      val total = 10000
      val results = (1 to total).map { _ ⇒
        val p = Promise[Long]()
        val t = System.nanoTime()
        f(p)
        val x = Await.result(p.future, Duration.Inf)
        val y = System.nanoTime()
        (y - x, x - t)
      }
      showFullStatistics("latency of reply delay using promises", results.map(_._1.toDouble), factor = 200) // about 30 ns
      showFullStatistics("latency of reaction launch using promises", results.map(_._2.toDouble), factor = 200) // about 30 ns
    }
  }

  behavior of "general overhead"

  it should "measure CPU speed" in {
    val initTime = System.nanoTime()
    val total = 250
    val arr = 1 to total
    arr.foreach(i ⇒ arr.foreach(j ⇒ arr.foreach(k ⇒ math.cos(1.0 + (0.0 + i + j * total + k * total * total) / total / total / total / 100.0))))
    val elapsed = (System.nanoTime() - initTime) / 1000000L
    println(s"Long math.cos computation took $elapsed ms")
  }

  def longComputation(): Unit = {
    val total = 250
    val arr = 1 to total
    arr.foreach(i ⇒ arr.foreach(j ⇒ arr.foreach(k ⇒ math.cos(1.0 + (0.0 + i + j * total + k * total * total) / total / total / total / 100.0))))
  }

  def longComputationOptimized(): Unit = optimize {
    val total = 250
    val arr = 1 to total
    arr.foreach(i ⇒ arr.foreach(j ⇒ arr.foreach(k ⇒ math.cos(1.0 + (0.0 + i + j * total + k * total * total) / total / total / total / 100.0))))
  }

  it should "measure CPU speed using def function" in {
    val elapsed = elapsedTimeMs {
      longComputation()
    }._2
    println(s"Long math.cos computation using def function took $elapsed ms")
  }

  it should "measure CPU speed using optimized def function" in {
    val elapsed = elapsedTimeMs {
      longComputationOptimized()
    }._2
    println(s"Long math.cos computation using optimized def function took $elapsed ms")
  }

  behavior of "time function statistics"

  it should "measure the latency of System.currentTimeMillis using elapsedTimesNs" in {
    val total = 20000
    val results = elapsedTimesNs(System.currentTimeMillis(), total)
    showFullStatistics("latency of System.currentTimeMillis", results, factor = 200) // about 30 ns
  }

  it should "measure the latency of LocalDateTime.now using elapsedTimesNs" in {
    val total = 20000
    val results = elapsedTimesNs(LocalDateTime.now, total)
    showFullStatistics("latency of LocalDateTime.now", results, factor = 200) // about 30 ns
  }

  it should "measure the latency of System.nanoTime using elapsedTimesNs" in {
    val total = 20000
    val results = elapsedTimesNs(System.nanoTime(), total)
    showFullStatistics("latency of System.nanoTime", results, factor = 200) // about 30 ns
  }

  it should "measure the latency of elapsedTimesNs" in {
    val total = 20000
    val results = elapsedTimesNs((), total)
    showFullStatistics("latency of elapsedTimesNs", results, factor = 200) // about 30 ns
  }

  it should "get statistics on emitting non-blocking molecules" in {
    val tp = FixedPool(1)
    val c = m[Unit]
    site(tp)(go { case c(_) ⇒ })

    val total = 20000
    val results = elapsedTimesNs(c(), total)
    showFullStatistics("latency of emitting non-blocking molecule", results, factor = 100)

    tp.shutdownNow()
  }

  it should "measure JVM warmup regression for creating a new reaction" in {
    val total = 5000
    val results = elapsedTimesNs({
      val c = b[Unit, Unit]
      go { case c(_, r) ⇒ r() }
    }, total)
    showFullStatistics("creating a new reaction", results, 100)
  }

  it should "measure JVM warmup regression for creating a new reaction site" in {
    val total = 5000
    val results = elapsedTimesNs({
      val c = b[Unit, Unit]
      val d = m[Unit]
      site(go { case c(_, r) + d(_) + d(_) + d(_) ⇒ r(); d() + d() + d() })
    }, total)
    showFullStatistics("creating a new reaction site", results, 100)
  }
}
