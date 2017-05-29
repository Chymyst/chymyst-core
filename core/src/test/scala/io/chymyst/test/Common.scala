package io.chymyst.test

import io.chymyst.jc._

object Common {
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

  def checkExpectedPipelined(expectedMap: Map[Molecule, Boolean]): String = {
    val transformed = expectedMap.toList.map { case (t, r) => (t, t.isPipelined, r) }
    // Print detailed message.
    val difference = transformed.filterNot { case (_, x, y) => x == y }.map { case (m, actual, expected) => s"$m.isPipelined is $actual instead of $expected" }
    if (difference.nonEmpty) s"Test fails: ${difference.mkString("; ")}" else ""
  }

  def elapsedTimeMs[T](x: ⇒ T): (T, Long) = {
    val initTime = System.currentTimeMillis()
    val result = x
    val elapsedTime = System.currentTimeMillis() - initTime
    (result, elapsedTime)
  }

  def meanAndStdev(d: Seq[Double]): (Double, Double) = {
    val size = safeSize(d.size)
    val mean = d.sum / size
    val std = math.sqrt(d.map(x => x - mean).map(x => x * x).sum / size)
    (mean, std)
  }

  def formatNanosToMs(x: Double): String = f"${x / 1000000.0}%1.3f"

  def formatNanosToMicros(x: Double): String = f"${x / 1000.0}%1.3f µs"

  def formatMicros(x: Double): String = f"$x%1.3f µs"

  val safeSize: Int => Double = x => if (x == 0) 1.0f else x.toDouble
}
