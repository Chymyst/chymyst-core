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

  behavior of "monadic Either"

  it should "perform monadic operations on Either" in {
    val b: Either[String, Int] = Left("bad")
    val c: Either[String, Int] = Right(123)

    b.map(x => x + 1) shouldEqual Left("bad")
    c.map(x => x + 1) shouldEqual Right(124)
    b.flatMap(x => Right(x + 1)) shouldEqual Left("bad")
    c.flatMap(x => Right(x + 1)) shouldEqual Right(124)
    c.flatMap(x => Left("no")) shouldEqual Left("no")
  }

  it should "support monadic for blocks on Either" in {
    val a = for {
      c <- if (3 > 0) Right(123) else Left("bad")
      d <- if (c > 0) Right(456) else Left("no")
    } yield c + d
    a shouldEqual Right(123 + 456)

    var notLazy: Boolean = false

    val q = for {
      c <- if (3 < 0) Right(123) else Left("bad")
    _ = { notLazy = true}
      d <- if (c > 0) Right(456) else Left("no")
    } yield c + d

    q shouldEqual Left("bad")

    notLazy shouldEqual false
  }

  it should "support findAfterMap for Seq" in {
    Seq(1, 2, 3).findAfterMap(x => if (x % 2 == 0) Some(x) else None) shouldEqual Some(2)
    Seq(1, 2, 3).findAfterMap(x => if (x % 2 != 0) Some(x) else None) shouldEqual Some(1)
    Seq(1, 2, 3).findAfterMap(x => if (x == 0) Some(x) else None) shouldEqual None
  }

  it should "support flatFoldLeft for Seq" in {
    Seq(1, 2, 3).flatFoldLeft(0)((x, n) => if (n != 0) Some(x + n) else None) shouldEqual Some(6)
    Seq(1, 2, 3).flatFoldLeft(0)((x, n) => if (n % 2 != 0) Some(x + n) else None) shouldEqual None
  }

  it should "support earlyFoldLeft for Seq" in {
    Seq(1, 2, 3).earlyFoldLeft(0)((x, n) => if (n != 0) Some(x + n) else None) shouldEqual 6
    Seq(1, 2, 3).earlyFoldLeft(0)((x, n) => if (n == 0) Some(x + n) else None) shouldEqual 0
    Seq(1, 2, 3).earlyFoldLeft(0)((x, n) => if (n % 2 != 0) Some(x + n) else None) shouldEqual 1
  }

  behavior of "cleanup utility"

  it should "react to exception during doWork" in {
    val tryResult = cleanup{123}{_ => ()}{ _ => throw new Exception("ignore this exception")}
    tryResult.isFailure shouldEqual true
    tryResult.failed.get.getMessage shouldEqual "ignore this exception"
  }

  it should "react to exception during resource cleanup" in {
    val tryResult = cleanup{123}{_ => throw new Exception("failed to cleanup - ignore this exception")}{ _ => throw new Exception("ignore this exception")}
    tryResult.isFailure shouldEqual true
    tryResult.failed.get.getMessage shouldEqual "ignore this exception"
  }

}