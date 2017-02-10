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

  def checkExpectedPipelined(expectedMap: Map[Molecule, Boolean]) = {
    val transformed = expectedMap.toList.map { case (t, r) => (t, t.isPipelined, r) }
    // Print detailed message.
    val difference = transformed.filterNot { case (_, x, y) => x == y }.map { case (m, actual, expected) => s"$m.isPipelined is $actual instead of $expected" }
    if (difference.nonEmpty) s"Test fails: ${difference.mkString("; ")}" else ""
  }

}
