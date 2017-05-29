package io.chymyst.benchmark

import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import io.chymyst.jc._
import io.chymyst.test.LogSpec
import org.sameersingh.scalaplot.jfreegraph.JFGraphPlotter
import io.chymyst.test.Common._
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.util.Random.nextInt

class ReactionDelaySpec extends LogSpec {

  behavior of "reaction overhead and delay times"

  it should "measure simple statistics on reaction delay" in {
    val f = b[Unit, Unit]
    val tp = new BlockingPool(4)
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
    val tp = new BlockingPool(4)
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
    val tp = new BlockingPool(4)

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
    val tp = new BlockingPool(4)
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

    val offset = 250.0
    val power = -1.0
    showRegression("reaction start delay", results.map(_._1 / 1000.0), x ⇒ math.pow(x + offset, power))
    showRegression("emit delay", results.map(_._2 / 1000.0), x ⇒ math.pow(x + offset, power))
    showRegression("reply delay", results.map(_._3 / 1000.0), x ⇒ math.pow(x + offset, power))
  }

  def showRegression(message: String, results: Seq[Double], funcX: Double => Double, funcY: Double => Double = identity): Unit = {
    // Perform regression to determine the effect of JVM warm-up.
    // Assume that the warm-up works as a0 + a1*x^(-c). Try linear regression with different values of c.
    val dataX = results.indices.map(_.toDouble)
    val dataY = results // pass with a min window
      .zipAll(results.drop(1), Double.PositiveInfinity, Double.PositiveInfinity)
      .zipAll(results.drop(2), (Double.PositiveInfinity, Double.PositiveInfinity), Double.PositiveInfinity)
      .map { case ((x, y), z) ⇒ math.min(x, math.min(y, z)) }
    val (a0, a1, a0stdev) = regressLSQ(dataX, dataY, funcX, funcY)
    val speedup = f"${(a0 + a1 * funcX(dataX.head)) / (a0 + a1*funcX(dataX.last))}%1.2f"
    println(s"Regression results for $message: constant = ${formatMicros(a0)} ± ${formatMicros(a0stdev)}, gain = ${formatMicros(a1)}*iteration, max. speedup = $speedup")

    import org.sameersingh.scalaplot.Implicits._

    val dataTheoryY = dataX.map(i ⇒ a0 + a1 * funcX(i))
    val chart = xyChart(dataX → ((dataTheoryY, dataY)))
    val plotter = new JFGraphPlotter(chart)
    val plotdir = "logs/"
    new File(plotdir).mkdir()
    val plotfile = "benchmark " + message.replaceAll(" ", "_")
    plotter.pdf(plotdir, plotfile)
    println(s"Plot file produced in $plotdir$plotfile.pdf")
  }

  def det(a00: Double, a01: Double, a10: Double, a11: Double): Double = a00 * a11 - a01 * a10

  def regressLSQ(xs: Seq[Double], ys: Seq[Double], funcX: Double ⇒ Double, funcY: Double ⇒ Double): (Double, Double, Double) = {
    val n = xs.length
    val sumX = xs.map(funcX).sum
    val sumXX = xs.map(funcX).map(x ⇒ x * x).sum
    val sumY = ys.map(funcY).sum
    val sumXY = xs.zip(ys).map { case (x, y) ⇒ funcX(x) * funcY(y) }.sum
    val detS = det(n.toDouble, sumX, sumX, sumXX)
    val a0 = det(sumY, sumX, sumXY, sumXX) / detS
    val a1 = det(n.toDouble, sumY, sumX, sumXY) / detS
    val eps = math.sqrt(xs.zip(ys).map { case (x, y) ⇒ math.pow(a0 + a1 * funcX(x) - funcY(y), 2) }.sum) / n
    (a0, a1, eps)
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

    val tp = new BlockingPool(4)
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
      (System.nanoTime() - initTime) / 1000L
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
    println(s"Emitting non-blocking molecules using two while loops: emission time is ${(overheadAverage - overheadAverageWithoutPayload) / 1000L} µs per molecule, overhead ${overheadAverageWithoutPayload / 1000L} µs")
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
    println(s"Creating a new reaction using two while loops: emission time is ${(overheadAverage - overheadAverageWithoutPayload) / 1000L} µs per reaction, overhead ${overheadAverageWithoutPayload / 1000L} µs")

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
    println(s"Creating a new reaction site using two while loops: emission time is ${(overheadAverage - overheadAverageWithoutPayload) / 1000L} µs per site, overhead ${overheadAverageWithoutPayload / 1000L} µs")
    tp.shutdownNow()
  }

}
