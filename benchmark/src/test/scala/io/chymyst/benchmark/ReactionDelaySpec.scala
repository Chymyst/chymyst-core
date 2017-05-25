package io.chymyst.benchmark

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import io.chymyst.jc._
import io.chymyst.test.LogSpec
import org.scalatest.Matchers

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._
import scala.util.Random.nextInt

class ReactionDelaySpec extends LogSpec with Matchers {

  val safeSize: Int => Double = x => if (x == 0) 1.0f else x.toDouble

  behavior of "reaction overhead and delay times"

  it should "measure simple statistics on reaction delay" in {
    val f = b[Unit, Unit]
    val tp = new SmartPool(4)
    site(tp)(
      go { case f(_, r) =>
        BlockingIdle {
          Thread.sleep(1)
        }
        r()
      } // reply immediately
    )
    val trials = 200
    val timeInit = System.nanoTime()
    val results = (1 to trials).map { _ =>
      val timeInit = System.nanoTime()
      f()
      System.nanoTime() - timeInit
    }
    val timeElapsed = (System.nanoTime() - timeInit) / 1000000L
    val meanReplyDelay = results.sum / safeSize(results.size) / 1000000L - 1
    println(s"Sequential test: Mean reply delay is $meanReplyDelay ms out of $trials trials; the test took $timeElapsed ms")
    tp.shutdownNow()
  }

  def getStats(d: Seq[Double]): (Double, Double) = {
    val size = safeSize(d.size)
    val mean = d.sum / size
    val std = math.sqrt(d.map(x => x - mean).map(x => x * x).sum / size)
    (mean, std)
  }

  def formatNanos(x: Double): String = f"${x / 1000000}%1.3f"

  it should "measure statistics on reaction scheduling for non-blocking molecules" in {
    val a = m[Long]
    val c = m[Long]
    val f = b[Unit, Long]
    val tp = new SmartPool(4)
    site(tp)(
      go { case c(x) + f(_, r) => r(x) },
      go { case a(d) =>
        val elapsed = System.nanoTime() - d
        c(elapsed)
      }
    )
    val trials = 10000
    val timeInit = System.nanoTime()
    val resultsRaw = (1 to trials).map { _ =>
      val timeInit = System.nanoTime()
      a(timeInit)
      val timeAfterA = System.nanoTime() - timeInit
      val res = f()
      val timeElapsed = System.nanoTime() - timeInit
      (res, timeAfterA, timeElapsed)
    }
    val timeElapsed = (System.nanoTime() - timeInit) / 1000000L

    val results = resultsRaw.drop(resultsRaw.size / 2) // Drop first half of values due to warm-up of JVM.

    val (meanReactionStartDelay, stdevReactionStartDelay) = getStats(results.map(_._1.toDouble))
    val (meanEmitDelay, stdevEmitDelay) = getStats(results.map(_._2.toDouble))
    val (meanReplyDelay, stdevReplyDelay) = getStats(results.map(_._3.toDouble))

    println(s"Sequential test of emission and reaction delay: trials = ${results.size}, meanReactionStartDelay = ${formatNanos(meanReactionStartDelay)} ms +- ${formatNanos(stdevReactionStartDelay)} ms, meanEmitDelay = ${formatNanos(meanEmitDelay)} ms +- ${formatNanos(stdevEmitDelay)} ms, meanReplyDelay = ${formatNanos(meanReplyDelay)} ms +- ${formatNanos(stdevReplyDelay)} ms; the test took $timeElapsed ms")

    tp.shutdownNow()
  }

  it should "measure simple statistics on reaction delay in parallel" in {
    val f = b[Unit, Unit]
    val counter = m[(Int, List[Long])]
    val all_done = b[Unit, List[Long]]
    val done = m[Long]
    val begin = m[Unit]
    val tp = new SmartPool(4)

    val trials = 200

    site(tp)(
      go { case begin(_) =>
        val timeInit = System.nanoTime()
        f()
        val timeElapsed = System.nanoTime() - timeInit
        done(timeElapsed)
      },
      go { case all_done(_, r) + counter((0, results)) => r(results) },
      go { case counter((n, results)) + done(res) if n > 0 => counter((n - 1, res :: results)) },
      go { case f(timeOut, r) =>
        BlockingIdle {
          Thread.sleep(1)
        }
        r()
      }
    )

    counter((trials, Nil))
    (1 to trials).foreach(_ => begin())

    val timeInit = System.nanoTime()
    (1 to trials).foreach { _ => begin() }
    val result = all_done()
    val meanReplyDelay = result.sum / safeSize(result.size) / 1000000L - 1
    val timeElapsed = (System.nanoTime() - timeInit) / 1000000L
    println(s"Parallel test: Mean reply delay is $meanReplyDelay ms out of $trials trials; the test took $timeElapsed ms")
    tp.shutdownNow()
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

    val tp = new SmartPool(4)
    val timeInit = LocalDateTime.now

    val result = processResults(measureTimeoutDelays(trials, maxTimeout, tp))

    val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Random timeout delay, parallel test ($trials trials, $maxTimeout max timeout) took $timeElapsed ms:")
    println(result.printout)
    tp.shutdownNow()
  }

  it should "measure the timeout delay in single thread" in {
    val trials = 20
    val maxTimeout = 200

    val tp = new FixedPool(4)
    val timeInit = LocalDateTime.now

    val result = processResults(measureTimeoutDelays(trials, maxTimeout, tp))

    val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Random timeout delay, single-thread test ($trials trials, $maxTimeout max timeout) took $timeElapsed ms:")
    println(result.printout)
    tp.shutdownNow()
  }

  behavior of "blocking reply via promise"

  val total = 10000

  it should "measure the reply delay using blocking molecules" in {
    val tp = new FixedPool(1)

    val f = b[Unit, Long]

    site(tp)(go { case f(_, r) ⇒ r(System.nanoTime()) })

    val res = (1 to 10).map { _ ⇒
      val results = (1 to total).map { _ ⇒
        val t = System.nanoTime()
        val r = f()
        (System.nanoTime() - r, r - t)
      }
      val resDelay = results.map(_._1)
      val resLaunch = results.map(_._2)
      val aveDelay = resDelay.sum / resDelay.length
      val aveLaunch = resLaunch.sum / resLaunch.length
      println(s"Average reply delay with blocking molecules: $aveDelay ns; average launch time: $aveLaunch ns")
      (aveDelay, aveLaunch)
    }.drop(2)
    println(s"Reply delay with blocking molecules: after ${res.length} tries, average is ${res.map(_._1).sum / res.length}, average launch time is ${res.map(_._2).sum / res.length}")
    tp.shutdownNow()
  }

  it should "measure the reply delay using promises" in {
    val tp = new FixedPool(1)

    val f = m[Promise[Long]]

    site(tp)(go { case f(promise) ⇒ promise.success(System.nanoTime()) })
    val drop = 10
    val res = (1 to 20 + drop).map { _ ⇒
      val results = (1 to total).map { _ ⇒
        val t = System.nanoTime()
        val p = Promise[Long]()
        f(p)
        val r = Await.result(p.future, Duration.Inf)
        (System.nanoTime() - r, r - t)
      }
      val resDelay = results.map(_._1)
      val resLaunch = results.map(_._2)
      val aveDelay = resDelay.sum / resDelay.length
      val aveLaunch = resLaunch.sum / resLaunch.length
      println(s"Average reply delay with blocking molecules: $aveDelay ns; average launch time: $aveLaunch ns")
      (aveDelay, aveLaunch)
    }.drop(drop)
    println(s"Reply delay with promises: after ${res.length} tries, average is ${res.map(_._1).sum / res.length}, average launch time is ${res.map(_._2).sum / res.length}")
    tp.shutdownNow()
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

  it should "measure emitting non-blocking molecules" in {
    val tp = new FixedPool(1)
    val c = m[Unit]
    site(tp)(go { case c(_) ⇒ })
    val total = 10000
    val drop = 20
    val iterations = 40
    val result = (1 to drop + iterations).map { _ ⇒
      val initTime = System.nanoTime()
      (1 to total).foreach { _ ⇒
        c()
        0
      }
      (System.nanoTime() - initTime) / 1000L
    }.drop(drop).sum / iterations
    val resultWithoutPayload = (1 to drop + iterations).map { _ ⇒
      val initTime = System.nanoTime()
      (1 to total).foreach { _ ⇒
//        c()
        0
      }
      (System.nanoTime() - initTime) / 1000L
    }.drop(drop).sum / iterations

    println(s"Emitting non-blocking molecules: emission time is ${result - resultWithoutPayload} µs per molecule, overhead $resultWithoutPayload µs")
    tp.shutdownNow()
  }

  it should "measure emitting non-blocking molecules using one while loop" in {
    val tp = new FixedPool(1)
    val c = m[Unit]
    site(tp)(go { case c(_) ⇒ })
    val total = 10000
    val drop = 20
    val iterations = 40

    val results = (1 to drop + iterations).map { _ ⇒
      var i = 0
      val initTime = System.nanoTime()
      while (i < total) {
        c()
        i += 1
      }
      (System.nanoTime() - initTime)/ 1000L
    }.drop(drop)
    val overheadAverage = results.sum / results.size
    println(s"Emitting non-blocking molecules using one while loop: emission time is $overheadAverage µs per molecule")
    tp.shutdownNow()
  }

  it should "measure emitting non-blocking molecules using two while loops" in {
    val tp = new FixedPool(1)
    val c = m[Unit]
    site(tp)(go { case c(_) ⇒ })
    val total = 10000
    val drop = 20
    val iterations = 40

    val overheadAverage = {
      var resultsSum = 0L

      var counter = 0
      while (counter < drop + iterations) {
        var i = 0
        val initTime = System.nanoTime()
        while (i < total) {
          c()
          i += 1
        }

        val elapsed = System.nanoTime() - initTime
        if (counter > drop) resultsSum += elapsed

        counter += 1
      }
      resultsSum / iterations
    }

    val overheadAverageWithoutPayload = {
      var resultsSum = 0L

      var counter = 0
      while (counter < drop + iterations) {
        var i = 0
        val initTime = System.nanoTime()
        while (i < total) {
//          c()
          i += 1
        }

        val elapsed = System.nanoTime() - initTime
        if (counter > drop) resultsSum += elapsed

        counter += 1
      }
      resultsSum / iterations
    }
    println(s"Emitting non-blocking molecules using two while loops: emission time is ${(overheadAverage - overheadAverageWithoutPayload)/1000L} µs per molecule, overhead ${overheadAverageWithoutPayload/1000L} µs")
    tp.shutdownNow()
  }

  it should "measure creating a new reaction" in {
    val total = 1000
    val drop = 20
    val iterations = 40

    val overheadAverage = {
      var resultsSum = 0L

      var counter = 0
      while (counter < drop + iterations) {
        var i = 0
        val initTime = System.nanoTime()
        while (i < total) {
          val c = b[Unit, Unit]
          go { case c(_, r) ⇒ r() }
          i += 1
        }

        val elapsed = System.nanoTime() - initTime
        if (counter > drop) resultsSum += elapsed

        counter += 1
      }
      resultsSum / iterations
    }

    val overheadAverageWithoutPayload = {
      var resultsSum = 0L

      var counter = 0
      while (counter < drop + iterations) {
        var i = 0
        val initTime = System.nanoTime()
        while (i < total) {
          i += 1
        }

        val elapsed = System.nanoTime() - initTime
        if (counter > drop) resultsSum += elapsed

        counter += 1
      }
      resultsSum / iterations
    }
    println(s"Creating a new reaction using two while loops: emission time is ${(overheadAverage - overheadAverageWithoutPayload)/1000L} µs per reaction, overhead ${overheadAverageWithoutPayload/1000L} µs")

  }

  it should "measure creating a new reaction site" in {
    val tp = new FixedPool(1)
    val total = 1000
    val drop = 20
    val iterations = 40

    val overheadAverage = {
      var resultsSum = 0L

      var counter = 0
      while (counter < drop + iterations) {
        var i = 0
        val initTime = System.nanoTime()
        while (i < total) {
          val c = b[Unit, Unit]
          site(tp)(go { case c(_, r) ⇒ r() })
          i += 1
        }

        val elapsed = System.nanoTime() - initTime
        if (counter > drop) resultsSum += elapsed

        counter += 1
      }
      resultsSum / iterations
    }

    val overheadAverageWithoutPayload = {
      var resultsSum = 0L

      var counter = 0
      while (counter < drop + iterations) {
        var i = 0
        val initTime = System.nanoTime()
        while (i < total) {
          i += 1
        }

        val elapsed = System.nanoTime() - initTime
        if (counter > drop) resultsSum += elapsed

        counter += 1
      }
      resultsSum / iterations
    }
    println(s"Creating a new reaction site using two while loops: emission time is ${(overheadAverage - overheadAverageWithoutPayload)/1000L} µs per site, overhead ${overheadAverageWithoutPayload/1000L} µs")
    tp.shutdownNow()
  }

}
