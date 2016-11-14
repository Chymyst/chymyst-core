package code.winitzki.jc

import org.scalatest.{FlatSpec, Matchers}
import JoinRun._
import Macros._

class MacrosSpec extends FlatSpec with Matchers {

  it should "compute invocation names for molecule injectors" in {
    val a = jA[Int]

    a.toString shouldEqual "a"

    val s = jS[Map[(Boolean,Unit),Seq[Int]], Option[List[(Int,Option[Map[Int,String]])]]] // complicated type

    s.toString shouldEqual "s/S"
  }

  it should "inspect reaction body" in {
    val a = jA[Int]
    val b = jA[(Int,Int)]
    val s = jS[Unit,Int]

    val result = findInputs({ case a(x) + a(y) + b((1,z)) + s(_, r) => a(x+1) + r(x) })

    println(s"debug: got $result")

//    result shouldEqual "blah"
  }
}
