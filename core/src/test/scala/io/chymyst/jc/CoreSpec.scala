package io.chymyst.jc

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
    getSha1((123, "123")) shouldEqual "C4C7ADA9B819DFAEFE10F765BAD87ABF91A79584"
  }

  it should "compute sha1 of List value" in {
    getSha1(List(123, 456)) shouldEqual "537DC011B1ED7084849573D8921BDB6EE1F87154"
  }

  it should "compute sha1 of integer tuple value" in {
    getSha1((123, 456)) shouldEqual "CE528A746B9311801806D4C802FB08D1FE66DC7F"
  }

  it should "compute sha1 of case class value" in {
    case class A(b: Int, c: Int)
    getSha1(A(123, 456)) shouldEqual "7E4D82CC624252B788D54BCE49D0A1380436E846"
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
      _ = {
        notLazy = true
      }
      d <- if (c > 0) Right(456) else Left("no")
    } yield c + d

    q shouldEqual Left("bad")

    notLazy shouldEqual false
  }

  behavior of "auxiliary fold ops"

  it should "support findAfterMap for Seq" in {
    Seq[Int]().findAfterMap(x => Some(x)) shouldEqual None
    Seq(1, 2, 3).findAfterMap(x => if (x % 2 == 0) Some(x) else None) shouldEqual Some(2)
    Seq(1, 2, 3).findAfterMap(x => if (x % 2 != 0) Some(x) else None) shouldEqual Some(1)
    Seq(1, 2, 3).findAfterMap(x => if (x == 0) Some(x) else None) shouldEqual None
  }

  it should "support flatFoldLeft for Seq" in {
    Seq[Int]().flatFoldLeft(10)((x, n) => if (n != 0) Some(x + n) else None) shouldEqual Some(10)
    Seq(1, 2, 3).flatFoldLeft(0)((x, n) => if (n != 0) Some(x + n) else None) shouldEqual Some(6)
    Seq(1, 2, 3).flatFoldLeft(0)((x, n) => if (n % 2 != 0) Some(x + n) else None) shouldEqual None
  }

  it should "support earlyFoldLeft for Seq" in {
    Seq[Int]().earlyFoldLeft(10)((x, n) => if (n != 0) Some(x + n) else None) shouldEqual 10
    Seq(1, 2, 3).earlyFoldLeft(0)((x, n) => if (n != 0) Some(x + n) else None) shouldEqual 6
    Seq(1, 2, 3).earlyFoldLeft(0)((x, n) => if (n == 0) Some(x + n) else None) shouldEqual 0
    Seq(1, 2, 3).earlyFoldLeft(0)((x, n) => if (n % 2 != 0) Some(x + n) else None) shouldEqual 1
  }

  it should "support sortedGroupBy" in {
    Seq[Int]().sortedMapGroupBy(identity, identity) shouldEqual Seq[Int]()
    Seq(1).sortedMapGroupBy(x ⇒ x % 2, _ * 10) shouldEqual Seq((1, Seq(10)))
    Seq(1, 3, 2, 4).sortedMapGroupBy(x ⇒ x % 2, _ * 10) shouldEqual Seq((1, Seq(10, 30)), (0, Seq(20, 40)))
    Seq(1, 2, 3, 4).sortedMapGroupBy(_ < 3, identity) shouldEqual Seq((true, Seq(1, 2)), (false, Seq(3, 4)))
    Seq(1, 2, 2, 3, 3, 3, 1).sortedMapGroupBy(identity, identity) shouldEqual Seq((1, Seq(1)), (2, Seq(2, 2)), (3, Seq(3, 3, 3)), (1, Seq(1)))
  }

  behavior of "cleanup utility"

  it should "react to exception during doWork" in {
    val tryResult = cleanup {
      123
    } { _ => () } { _ => throw new Exception("ignore this exception") }
    tryResult.isFailure shouldEqual true
    tryResult.failed.get.getMessage shouldEqual "ignore this exception"
  }

  it should "react to exception during resource cleanup" in {
    val tryResult = cleanup {
      123
    } { _ => throw new Exception("failed to cleanup - ignore this exception") } { _ => throw new Exception("ignore this exception") }
    tryResult.isFailure shouldEqual true
    tryResult.failed.get.getMessage shouldEqual "ignore this exception"
  }

  behavior of "intHash"

  it should "compute correct values for arrays" in {
    intHash(Array[Int]()) shouldEqual 0
    intHash(Array(10)) shouldEqual 10

    intHash(Array(10, 20)) shouldEqual 2 * 10 + 20
    intHash(Array(10, 20, 30)) shouldEqual (10 * 3 + 20) * 3 + 30
    intHash(Array(100, 200)) should not equal intHash(Array(200, 100))
    intHash(Array(100, 200, 300)) should not equal intHash(Array(300, 200, 100))

    intHash(Array(1, 2)) should be < intHash(Array(2, 3))
    intHash(Array(0, 3)) should be < intHash(Array(1, 2))
  }

  behavior of "random element in array"

  it should "retrieve randomly chosen elements from array" in {
    val n = 100
    val arr = Array.tabulate(n)(identity)
    println(arr.toList)
    val retrieved = (0 until n).map(i ⇒ randomElementInArray(arr, i))

    retrieved.toList shouldEqual arr.toList
    retrieved.toList should not equal (0 until n).toList
  }

  it should "use shuffle on a sequence" in {
    val n = 100
    val s = (0 until n).shuffle
    s should not equal (0 until n).toList
  }

}