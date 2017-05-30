package io.chymyst.test

import io.chymyst.jc.TypeLabels._

class TypeLabelsSpec extends LogSpec {

  import io.chymyst.jc.TypeLabels.LabeledString._

  behavior of "labeled X"

  it should "tag an integer and then untag" in {
    val UserId = makeLabeledX[Int]
    type UserId = UserId.T
    val x: UserId = UserId(123)
    val y: UserId = x
    val z: Int = y
    z shouldEqual 123
  }

  it should "tag a list" in {
    val UserName = makeLabeledX[String]
    type UserName = UserName.T
    val as = List("a", "b", "c")
    val las = UserName.subst(as)
    las.isInstanceOf[List[UserName]] shouldEqual true
    las.isInstanceOf[List[String]] shouldEqual true

    def f(x: List[UserName]): Int = x.length

    def g(x: List[String]): Int = x.length

    " f(as) " shouldNot compile
    f(las) shouldEqual 3
    g(las) shouldEqual 3

  }

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
