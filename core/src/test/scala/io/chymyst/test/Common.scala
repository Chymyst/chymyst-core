package io.chymyst.test

import java.io.File

import io.chymyst.jc._
import org.sameersingh.scalaplot.jfreegraph.JFGraphPlotter
import org.scalatest.Assertion
import org.scalatest.Matchers._

object Common {

  def globalLogHas(part: String, message: String): Assertion = {
    globalErrorLog.find(_.contains(part)).get should endWith(message)
  }

  // Note: log messages have a timestamp prepended to them, so we use `endsWith` when matching a log message.
  def logShouldHave(message: String) = {
    globalErrorLog.exists(_ endsWith message) should be(true)
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

  def showRegression(message: String, results: Seq[Double], funcX: Double => Double, funcY: Double => Double = identity): Unit = {
    // Perform regression to determine the effect of JVM warm-up.
    // Assume that the warm-up works as a0 + a1*x^(-c). Try linear regression with different values of c.
    val dataX = results.indices.map(_.toDouble)
    val dataY = results // pass with a min window
      .zipAll(results.drop(1), Double.PositiveInfinity, Double.PositiveInfinity)
      .zipAll(results.drop(2), (Double.PositiveInfinity, Double.PositiveInfinity), Double.PositiveInfinity)
      .map { case ((x, y), z) ⇒ math.min(x, math.min(y, z)) }
    val (a0, a1, a0stdev) = regressLSQ(dataX, dataY, funcX, funcY)
    val speedup = f"${(a0 + a1 * funcX(dataX.head)) / (a0 + a1 * funcX(dataX.last))}%1.2f"
    println(s"Regression (total=${results.length}) for $message: constant = ${formatNanosToMicros(a0)} ± ${formatNanosToMicros(a0stdev)}, gain = ${formatNanosToMicros(a1)}*iteration, max. speedup = $speedup")

    import org.sameersingh.scalaplot.Implicits._

    val dataTheoryY = dataX.map(i ⇒ a0 + a1 * funcX(i))
    val chart = xyChart(dataX → ((dataTheoryY, dataY)))
    val plotter = new JFGraphPlotter(chart)
    val plotdir = "logs/"
    new File(plotdir).mkdir()
    val plotfile = "benchmark." + message.replaceAll(" ", "_")
    plotter.pdf(plotdir, plotfile)
    println(s"Plot file produced in $plotdir$plotfile.pdf")
  }

  def showFullStatistics(message: String, resultsRaw: Seq[Double], factor: Double = 20.0): Unit = {
    val results = resultsRaw.sortBy(- _)
    val total = results.length
    val take = (total / factor).toInt
    val (mean, std) = meanAndStdev(results.takeRight(take))
    val headPortion = resultsRaw.take(take)
    println(s"$message: best result overall: ${formatNanosToMicros(results.last)}; last portion: ${formatNanosToMicrosWithMeanStd(mean, std)}; first portion ($take) ranges from ${formatNanosToMicros(headPortion.max)} to ${formatNanosToMicros(headPortion.min)}")
    showRegression(message, results, x ⇒ math.pow(x + total / factor, -1.0))
  }

}
