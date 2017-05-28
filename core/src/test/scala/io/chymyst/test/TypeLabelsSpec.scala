package io.chymyst.test

import org.scalatest.Matchers

class TypeLabelsSpec extends LogSpec with Matchers {

  import io.chymyst.jc.TypeLabels.LabeledString._

  behavior of "labeled string"

  it should "tag string and then unwrap" in {
    val x = Label("abc")
    val y: Label = x
    val z: String = y
    z shouldEqual "abc"
  }

  it should "refuse to compile a method accepting Label when given a String instead" in {
    def f(x: Label): Boolean = x > "xyz"

    val x = Label("abc")
    f(x) shouldEqual false

    f(Label("zzz")) shouldEqual true

    """ f("abc") """ shouldNot compile
  }

  it should "tag and untag a list" in {
    val as = List("a", "b", "c")
    val las = Label.subst(as)
    las.isInstanceOf[List[Label]] shouldEqual true
    las.isInstanceOf[List[String]] shouldEqual true

    def f(x: List[Label]): Int = x.length

    def g(x: List[String]): Int = x.length

    " f(as) " shouldNot compile
    f(las) shouldEqual 3
    g(las) shouldEqual 3
  }

}
