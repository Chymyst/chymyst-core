package code.winitzki.jc

import org.scalatest.{FlatSpec, Matchers}

import Macros.{jA, jS}

class MacrosSpec extends FlatSpec with Matchers {

  it should "compute invocation names for molecule injectors" in {
    val a = jA[Int]

    a.toString shouldEqual "a"

    val s = jS[Map[(Boolean,Unit),Seq[Int]], Option[List[(Int,Option[Map[Int,String]])]]] // complicated type

    s.toString shouldEqual "s/S"
  }

}
