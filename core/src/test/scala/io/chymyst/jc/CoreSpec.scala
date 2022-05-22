package io.chymyst.jc

import javax.xml.bind.DatatypeConverter

import io.chymyst.jc.Core._
import io.chymyst.test.LogSpec

class CoreSpec extends LogSpec {
  private lazy val sha1Digest = getMessageDigest

  def getSha1Any(c: Any): String = getSha1(c.toString, sha1Digest)

  val warmupTimeMs = 50L

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "getSha1"

  val total = 200

  it should "compute hex string of byte array using DatatypeConverter" in {
    val md = getMessageDigest
    val bytes = md.digest("abcde".getBytes("UTF-8"))
    (1 to total).foreach { _ ⇒ DatatypeConverter.printHexBinary(bytes) }
  }

  it should "compute hex string of byte array using String.format" in {
    val md = getMessageDigest
    val bytes = md.digest("abcde".getBytes("UTF-8"))
    (1 to total).foreach { _ ⇒ bytes.map("%02X".format(_)).mkString }
  }

  it should "compute sha1 of integer value" in {
    getSha1Any(123) shouldEqual "40BD001563085FC35165329EA1FF5C5ECBDBBEEF"
  }

  it should "compute sha1 of string value" in {
    getSha1Any("123") shouldEqual "40BD001563085FC35165329EA1FF5C5ECBDBBEEF"
  }

  it should "compute sha1 of tuple value" in {
    getSha1Any((123, "123")) shouldEqual "C4C7ADA9B819DFAEFE10F765BAD87ABF91A79584"
  }

  it should "compute sha1 of List value" in {
    getSha1Any(List(123, 456)) shouldEqual "537DC011B1ED7084849573D8921BDB6EE1F87154"
  }

  it should "compute sha1 of integer tuple value" in {
    getSha1Any((123, 456)) shouldEqual "CE528A746B9311801806D4C802FB08D1FE66DC7F"
  }

  it should "compute sha1 of case class value" in {
    case class A(b: Int, c: Int)
    getSha1Any(A(123, 456)) shouldEqual "7E4D82CC624252B788D54BCE49D0A1380436E846"
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
  /*
    it should "support earlyFoldLeft for Seq" in {
      Seq[Int]().earlyFoldLeft(10)((x, n) => if (n != 0) Some(x + n) else None) shouldEqual 10
      Seq(1, 2, 3).earlyFoldLeft(0)((x, n) => if (n != 0) Some(x + n) else None) shouldEqual 6
      Seq(1, 2, 3).earlyFoldLeft(0)((x, n) => if (n == 0) Some(x + n) else None) shouldEqual 0
      Seq(1, 2, 3).earlyFoldLeft(0)((x, n) => if (n % 2 != 0) Some(x + n) else None) shouldEqual 1
    }
  */
  it should "support sortedGroupBy" in {
    Seq[Int]().orderedMapGroupBy(identity, identity) shouldEqual Seq[Int]()
    Seq(1).orderedMapGroupBy(x ⇒ x % 2, _ * 10) shouldEqual Seq((1, Seq(10)))
    Seq(1, 3, 2, 4).orderedMapGroupBy(x ⇒ x % 2, _ * 10) shouldEqual Seq((1, Seq(10, 30)), (0, Seq(20, 40)))
    Seq(1, 2, 3, 4).orderedMapGroupBy(_ < 3, identity) shouldEqual Seq((true, Seq(1, 2)), (false, Seq(3, 4)))
    Seq(1, 2, 2, 3, 3, 3, 1).orderedMapGroupBy(identity, identity) shouldEqual Seq((1, Seq(1)), (2, Seq(2, 2)), (3, Seq(3, 3, 3)), (1, Seq(1)))
  }

  behavior of "auxiliary fold ops for Array"

  it should "support findAfterMap for Array" in {
    val empty = new Array[Int](0)
    val a = Array.tabulate[Int](10)(_ + 1)
    empty.findAfterMap(x => Some(x)) shouldEqual None
    a.findAfterMap(x => if (x % 2 == 0) Some(x) else None) shouldEqual Some(2)
    a.findAfterMap(x => if (x % 2 != 0) Some(x) else None) shouldEqual Some(1)
    a.findAfterMap(x => if (x == 0) Some(x) else None) shouldEqual None
  }

  it should "support flatFoldLeft for Array" in {
    val empty = new Array[Int](0)
    val a = Array.tabulate[Int](3)(_ + 1)
    empty.flatFoldLeft(10)((x, n) => if (n != 0) Some(x + n) else None) shouldEqual Some(10)
    a.flatFoldLeft(0)((x, n) => if (n != 0) Some(x + n) else None) shouldEqual Some(6)
    a.flatFoldLeft(0)((x, n) => if (n % 2 != 0) Some(x + n) else None) shouldEqual None
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

  it should "process null resource" in {
    cleanup(null)(x ⇒ ())(x ⇒ 123).get shouldEqual 123
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

  behavior of "random shuffle"
  /*
    it should "retrieve randomly chosen elements from array" in {
      val n = 100
      val arr = Array.tabulate(n)(identity)
      println(arr.toList)
      val retrieved = (0 until n).map(i ⇒ randomElementInArray(arr, i))

      retrieved.toList shouldEqual arr.toList
      retrieved.toList should not equal (0 until n).toList
    }

  it should "shuffle an array in place" in {
    val n = 100
    val arr = Array.tabulate(n)(identity)
    arrayShuffleInPlace(arr)
    arr.toList should not equal (0 until n).toList
  }

  it should "use shuffle on a sequence" in {
    val n = 100
    val s = (0 until n).shuffle
    s should not equal (0 until n).toList
  }
 */
  behavior of "streamDiff"

  it should "not exclude elements when skip is empty" in {
    val data = (1 to 10) ++ (1 to 10)
    val skip = new MutableMultiset[Int]()
    val result = streamDiff(data.toIterator, skip)
    result.toList shouldEqual data.toList
  }

  it should "exclude elements with repetitions" in {
    val data = (1 to 10) ++ (1 to 10)
    val skip = new MutableMultiset[Int](List(1, 1, 2, 2, 3, 4))
    val expected = (5 to 10) ++ (3 to 10)
    val result = streamDiff(data.toIterator, skip)
    result.toList shouldEqual expected.toList
  }

  behavior of "reactionInfo"

  it should "give no info when running outside reactions" in {
    getReactionInfo shouldEqual NO_REACTION_INFO_STRING
    setReactionInfoOnThread(new ReactionInfo(Array(), Array(), Array(), GuardAbsent, "sha1"))
    getReactionInfo shouldEqual NO_REACTION_INFO_STRING
    clearReactionInfoOfThread()
    getReactionInfo shouldEqual NO_REACTION_INFO_STRING
  }

  it should "give reaction info inside reaction" in {
    withPool(FixedPool(2)) { tp =>
      val a = m[Int]
      val f = b[Unit, String]

      site(tp)(
        go { case a(x) + a(y) + f(_, r) if x > 0 ⇒
          val z = x + y
          r(s"Reaction {$getReactionInfo} yields $z")
        }
      )
      a(1)
      a(2)
      f() shouldEqual "Reaction {a(x if ?) + a(y) + f/B(_) → } yields 3"
    }.get

  }
}