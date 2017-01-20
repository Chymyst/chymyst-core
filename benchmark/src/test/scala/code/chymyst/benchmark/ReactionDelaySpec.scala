package code.chymyst.benchmark

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import code.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.util.Random.nextInt

class ReactionDelaySpec extends FlatSpec with Matchers {

  val safeSize: Int => Double = x => if (x==0) 1.0f else x.toDouble

  behavior of "reaction overhead and delay times"

  it should "measure simple statistics on reaction delay" in {
    val f = b[Unit,Unit]
    val tp = new SmartPool(4)
    site(tp)(
      go { case f(_, r) => BlockingIdle{Thread.sleep(1)}; r() } // reply immediately
    )
    val trials = 200
    val timeInit = LocalDateTime.now
    val results = (1 to trials).map { _ =>
      val timeInit = LocalDateTime.now
      f()
      val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MICROS)
      timeElapsed
    }
    val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MILLIS)
    val meanReplyDelay = results.sum / safeSize(results.size) / 1000 - 1
    println(s"Sequential test: Mean reply delay is $meanReplyDelay ms out of $trials trials; the test took $timeElapsed ms")
    tp.shutdownNow()
  }

  def getStats(d: Seq[Double]): (Double, Double) = {
    val size = safeSize(d.size)
    val mean = d.sum / size
    val std = math.sqrt( d.map(x => x - mean).map(x => x * x).sum / size )
    (mean, std)
  }

  def formatNanos(x: Double): String = f"${x/1000000}%1.3f"

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
    val timeInit = LocalDateTime.now
    val resultsRaw = (1 to trials).map { _ =>
      val timeInit = System.nanoTime()
      a(timeInit)
      val timeAfterA = System.nanoTime() - timeInit
      val res = f()
      val timeElapsed = System.nanoTime() - timeInit
      (res, timeAfterA, timeElapsed)
    }
    val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MILLIS)

    val results = resultsRaw.drop(resultsRaw.size/2) // Drop first half of values due to warm-up of JVM.

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
        val timeInit = LocalDateTime.now
        f()
        val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MICROS)
        done(timeElapsed)
      },
      go { case all_done(_, r) + counter((0, results)) => r(results) },
      go { case counter( (n, results) ) + done(res) if n > 0 => counter( (n-1, res :: results) ) },
      go { case f(timeOut, r) => BlockingIdle{Thread.sleep(1)}; r() }
    )

    counter((trials, Nil))
    (1 to trials).foreach(_ => begin())

    val timeInit = LocalDateTime.now
    (1 to trials).foreach { _ => begin() }
    val result = all_done()
    val meanReplyDelay = result.sum / safeSize(result.size) / 1000 - 1
    val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MILLIS)
    println(s"Parallel test: Mean reply delay is $meanReplyDelay ms out of $trials trials; the test took $timeElapsed ms")
    tp.shutdownNow()
  }

  type Result = (Int, Int, Long, Boolean)

  case class MeasurementResult(resultTrueSize: Int, resultFalseSize: Int, timeoutDelayArraySize: Int, noTimeoutMeanShiftArraySize: Int, timeoutDelay: Double, noTimeoutDelay: Double, timeoutMeanShift: Double, noTimeoutMeanShift: Double, printout: String)

  def measureTimeoutDelays(trials: Int, maxTimeout: Int, tp: Pool): List[(Int, Int, Long, Boolean)] = {
    val f = b[Long, Unit]
    val counter = m[(Int, List[Result])]
    val all_done = b[Unit, List[Result]]
    val done = m[Result]
    val begin = m[Int]

    site(tp)(
      go { case begin(_) =>
        val t1 = nextInt(maxTimeout)
        val t2 = nextInt(maxTimeout)
        val timeInit = LocalDateTime.now
        val res = f.timeout(t2.toLong)(t1.millis).isEmpty
        val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MILLIS)
        done((t1, t2, timeElapsed, res))
      },
      go { case all_done(_, r) + counter((0, results)) => r(results) },
      go { case counter( (n, results) ) + done(res) if n > 0 => counter( (n-1, res :: results) ) },
      go { case f(timeOut, r) => BlockingIdle{Thread.sleep(timeOut)}; r() }
    )

    counter((trials, Nil))
    (1 to trials).foreach(begin)

    all_done()
  }

  def processResults(result: List[Result]): MeasurementResult = {
    val (resultTrue, resultFalse) = result.partition(_._4)

    val resultFalseSize = resultFalse.size
    val resultTrueSize = resultTrue.size

    val timeoutDelayArray = resultTrue.map{ case (t1, t2, timeElapsed, _) => timeElapsed - t2 }.filter(_ > 0)
    val timeoutDelay = timeoutDelayArray.sum / safeSize(timeoutDelayArray.size)
    val noTimeoutDelay = resultFalse.map{ case (t1, t2, timeElapsed, _) => timeElapsed - t2 }.sum / safeSize(resultFalse.size)
    val timeoutMeanShift = resultTrue.map{ case (t1, t2, timeElapsed, _) => timeElapsed - t1 }.sum / safeSize(resultTrue.size)
    val noTimeoutMeanShiftArray = resultFalse.map{ case (t1, t2, timeElapsed, _) => timeElapsed - t1 }.filter(_ > 0)
    val noTimeoutMeanShift = noTimeoutMeanShiftArray.sum / safeSize(noTimeoutMeanShiftArray.size)

    val timeoutDelayArraySize = timeoutDelayArray.size
    val noTimeoutMeanShiftArraySize = noTimeoutMeanShiftArray.size

    val printout = s"""Results:   # samples      | delay     | mean shift
                      |----------------------------------------------------
                      | timeout     $resultTrueSize ($timeoutDelayArraySize items) | $timeoutDelay | $timeoutMeanShift
                      |----------------------------------------------------
                      | no timeout  $resultFalseSize ($noTimeoutMeanShiftArraySize items) | $noTimeoutDelay | $noTimeoutMeanShift
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

}