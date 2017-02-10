package io.chymyst.test

object Common {
  def repeat[A](n: Int)(x: => A): Unit = (1 to n).foreach(_ => x)

}