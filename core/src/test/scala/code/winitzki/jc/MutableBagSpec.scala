package code.winitzki.jc

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}

class MutableBagSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(200, Millis)

  it should "create empty bag" in {
    val b = new MutableBag[Int, String]
    b.size shouldEqual 0
    b.getOne(1) shouldEqual None
  }

  it should "add one element to bag" in {
    val b = new MutableBag[Int, String]
    b.addToBag(1, "a")
    b.size shouldEqual 1
    b.getOne(1) shouldEqual Some("a")
  }

  it should "add two elements with the same key and the same value, them remove them both" in {
    val b = new MutableBag[Int, String]
    b.addToBag(1, "a")
    b.size shouldEqual 1
    b.addToBag(1, "a")
    b.size shouldEqual 2
    b.getOne(1) shouldEqual Some("a")
    b.removeFromBag(1, "a")
    b.size shouldEqual 1
    b.getOne(1) shouldEqual Some("a")
    b.removeFromBag(1, "a")
    b.size shouldEqual 0
  }
  it should "add two elements with the same key and different values, remove them one by one" in {
    val b = new MutableBag[Int, String]
    b.addToBag(1, "a")
    b.size shouldEqual 1
    b.addToBag(1, "b")
    b.size shouldEqual 2
    val c1 = b.getOne(1).get
    Set("a", "b").contains(c1) shouldEqual true
    b.removeFromBag(1, "a")
    val c2 = b.getOne(1)
    c2 shouldEqual Some("b")
    b.size shouldEqual 1
    b.removeFromBag(1, "b")
    b.size shouldEqual 0
  }
  it should "add two elements with different keys and different values, then remove them one by one" in {
    val b = new MutableBag[Int, String]
    b.addToBag(1, "a")
    b.size shouldEqual 1
    b.addToBag(2, "b")
    b.size shouldEqual 2
    b.getOne(1) shouldEqual Some("a")
    b.getOne(2) shouldEqual Some("b")
    b.removeFromBag(1, "a")
    b.size shouldEqual 1
    b.getOne(1) shouldEqual None
    b.getOne(2) shouldEqual Some("b")
    b.removeFromBag(2, "b")
    b.size shouldEqual 0
  }

  it should "quickly add and remove elements with the same keys and the same value" in {
    val b = new MutableBag[Int, Int]
    val n = 200000
    (1 to n).foreach { _ => b.addToBag(1, 1) }
    (1 to n).foreach { _ => b.removeFromBag(1, 1) }
  }

  it should "quickly add and remove elements with the same keys and different values" in {
    val b = new MutableBag[Int, Int]
    val n = 20000
    (1 to n).foreach { i => b.addToBag(1, i) }
    (1 to n).foreach { i => b.removeFromBag(1, i) }
  }

}
