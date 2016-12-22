package code.chymyst.jc

import Core._

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.time.{Millis, Span}

class CoreSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  val warmupTimeMs = 50L

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "getSha1"

  it should "compute sha1 of integer value" in {
    getSha1(123) shouldEqual "40BD001563085FC35165329EA1FF5C5ECBDBBEEF"
  }

  it should "compute sha1 of string value" in {
    getSha1("123") shouldEqual "40BD001563085FC35165329EA1FF5C5ECBDBBEEF"
  }

  it should "compute sha1 of tuple value" in {
    getSha1((123,"123")) shouldEqual "C4C7ADA9B819DFAEFE10F765BAD87ABF91A79584"
  }

  it should "compute sha1 of List value" in {
    getSha1(List(123,456)) shouldEqual "537DC011B1ED7084849573D8921BDB6EE1F87154"
  }

  it should "compute sha1 of integer tuple value" in {
    getSha1((123,456)) shouldEqual "CE528A746B9311801806D4C802FB08D1FE66DC7F"
  }

  it should "compute sha1 of case class value" in {
    case class A(b: Int, c: Int)
    getSha1(A(123,456)) shouldEqual "7E4D82CC624252B788D54BCE49D0A1380436E846"
  }

}