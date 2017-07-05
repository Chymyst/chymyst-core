package io.chymyst.test

import java.io.File

import io.chymyst.jc._
import org.sameersingh.scalaplot.jfreegraph.JFGraphPlotter
import org.scalatest.Assertion
import org.scalatest.Matchers._

object Common {

  def globalLogHas(reporter: MemoryLogger, part: String, message: String): Assertion = {
    reporter.messages.find(_.contains(part)) match {
      case Some(str) ⇒ str should endWith(message)
      case None ⇒
        reporter.messages.foreach(println) shouldEqual "Test failed, see log messages above" // this fails and alerts the user
    }
  }

  // Note: log messages have a timestamp prepended to them, so we use `endsWith` when matching a log message.
  def logShouldHave(reporter: MemoryLogger, message: String): Assertion = {
    val status = reporter.messages.exists(_ endsWith message)
    if (!status) reporter.messages.foreach(println) shouldEqual "Test failed, see log messages above" // this fails and alerts the user
    else status shouldEqual true
  }

  def repeat[A](n: Int)(x: => A): Unit = (1 to n).foreach(_ => x)

  def repeat[A](n: Int, f: Int => A): Unit = (1 to n).foreach(f)

  def litmus[T](tp: Pool): (M[T], B[Unit, T]) = {
    val signal = m[T]
    val fetch = b[Unit, T]
    site(tp)(
      go { case signal(x) + fetch(_, r) ⇒ r(x) }
    )
    (signal, fetch)
  }

  def checkExpectedPipelined(expectedMap: Map[MolEmitter, Boolean]): String = {
    val transformed = expectedMap.toList.map { case (t, r) => (t, t.isPipelined, r) }
    // Print detailed message.
    val difference = transformed.filterNot { case (_, x, y) => x == y }.map { case (m, actual, expected) => s"$m.isPipelined is $actual instead of $expected" }
    if (difference.nonEmpty) s"Test fails: ${difference.mkString("; ")}" else ""
  }

  def elapsedTimeMs[T](x: ⇒ T): (T, Long) = {
    val initTime = System.currentTimeMillis()
    val result = x
    val y = System.currentTimeMillis()
    val elapsedTime = y - initTime
    (result, elapsedTime)
  }

  def elapsedTimeNs[T](x: ⇒ T): (T, Long) = {
    val initTime = System.nanoTime()
    val result = x
    val y = System.nanoTime()
    val elapsedTime = y - initTime
    (result, elapsedTime)
  }

  def elapsedTimesNs[T](x: ⇒ Any, total: Int): Seq[Double] = {
    (1 to total).map { _ ⇒
      val initTime = System.nanoTime()
      x
      val y = System.nanoTime()
      (y - initTime).toDouble
    }
  }

  def meanAndStdev(d: Seq[Double]): (Double, Double) = {
    val size = safeSize(d.size)
    val mean = d.sum / size
    val std = math.sqrt(d.map(x => x - mean).map(x => x * x).sum / size)
    (mean, std)
  }

  def formatNanosToMs(x: Double): String = f"${x / 1000000.0}%1.3f ms"

  def formatNanosToMicros(x: Double): String = f"${x / 1000.0}%1.3f µs"

  def formatNanosToMicrosWithMeanStd(mean: Double, std: Double): String = s"${formatNanosToMicros(mean)} ± ${formatNanosToMicros(std)}"

  val safeSize: Int => Double = x => if (x == 0) 1.0f else x.toDouble

  private def det(a00: Double, a01: Double, a10: Double, a11: Double): Double = a00 * a11 - a01 * a10

  private def regressLSQ(xs: Seq[Double], ys: Seq[Double], funcX: Double ⇒ Double, funcY: Double ⇒ Double): (Double, Double, Double) = {
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

  def showRegression(message: String, resultsRaw: Seq[Double]): Unit = {
    // Perform regression to determine the effect of JVM warm-up.
    // Assume that the warm-up works as a0 + a1*x^(-c). Try linear regression with different values of c.
    val total = resultsRaw.length
    val take = (total * 0.02).toInt // omit the first few % of data due to extreme variability before JVM warm-up
    val results = resultsRaw.drop(take)
    val dataX = results.indices.map(_.toDouble)
    val dataY = results // smoothing pass with a min window
      .zipAll(results.drop(1), Double.PositiveInfinity, Double.PositiveInfinity)
      .zipAll(results.drop(2), (Double.PositiveInfinity, Double.PositiveInfinity), Double.PositiveInfinity)
      .map { case ((x, y), z) ⇒ math.min(x, math.min(y, z)) }
    val (shift, (a0, a1, a0stdev)) = (0 to 100).map { i ⇒
      val shift = 0.1 + 2.5 * i
      (shift, regressLSQ(dataX, dataY, x ⇒ math.pow(x + shift, -1.0), identity))
    }.minBy(_._2._3)
    val funcX = (x: Double) ⇒ math.pow(x + shift, -1.0)
    val earlyValue = dataY.take(take).sum / take
    val lateValue = dataY.takeRight(take).sum / take
    val speedup = f"${earlyValue / lateValue}%1.2f"
    println(s"Regression (total=$total) for $message: constant = ${formatNanosToMicros(a0)} ± ${formatNanosToMicros(a0stdev)}, gain = ${formatNanosToMicros(a1)}*iteration, max. speedup = $speedup, shift = $shift")

    import org.sameersingh.scalaplot.Implicits._

    val dataXplotting = dataX.drop(take)
    val dataTheoryY = dataXplotting.map(i ⇒ a0 + a1 * funcX(i))
    val chart = xyChart(dataXplotting → ((dataTheoryY, dataY.drop(take))))
    val plotter = new JFGraphPlotter(chart)
    val plotdir = "logs/"
    new File(plotdir).mkdir()
    val plotfile = "benchmark." + message.replaceAll(" ", "_")
    plotter.pdf(plotdir, plotfile)
    println(s"Plot file produced in $plotdir$plotfile.pdf")
  }

  def showStd(message: String, results: Seq[Double], factor: Double = 20.0): Unit = {
    val total = results.length
    val take = (total / factor).toInt
    val (mean, std) = meanAndStdev(results.sortBy(-_).takeRight(take))
    val headPortion = results.take(take)
    println(s"$message: best result overall: ${formatNanosToMicros(results.min)}; best portion: ${formatNanosToMicrosWithMeanStd(mean, std)}; first portion ($take) ranges from ${formatNanosToMicros(headPortion.max)} to ${formatNanosToMicros(headPortion.min)}")

  }

  def showFullStatistics(message: String, results: Seq[Double], factor: Double = 20.0): Unit = {
    showStd(message, results, factor)
    showRegression(message, results)
  }

}
