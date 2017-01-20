package code.chymyst.jc

import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

class ReactionSiteSpec extends FlatSpec with Matchers with BeforeAndAfterEach {

  behavior of "reaction"

  it should "admit values by simple constant matching" in {
    val a = m[Int]

    val r = go { case a(1) => }

    r.info.toString shouldEqual "a(1) => "
    r.info.inputs.head.admitsValue(MolValue(1)) shouldEqual true
    r.info.inputs.head.admitsValue(MolValue(0)) shouldEqual false
  }

  it should "admit values by simple variable matching" in {
    val a = m[Int]

    val r = go { case a(x) => }

    r.info.toString shouldEqual "a(x) => "
    r.info.inputs.head.admitsValue(MolValue(1)) shouldEqual true
    r.info.inputs.head.admitsValue(MolValue(0)) shouldEqual true
  }

  it should "admit values by simple variable matching with conditional" in {
    val a = m[Int]

    val r = go { case a(x) if x > 0 => }

    r.info.toString shouldEqual "a(x if ?) => "
    r.info.inputs.head.admitsValue(MolValue(1)) shouldEqual true
    r.info.inputs.head.admitsValue(MolValue(0)) shouldEqual false
  }

  it should "admit values by pattern matching" in {
    val a = m[Option[Int]]

    val r = go { case a(Some(x)) => }

    r.info.toString shouldEqual "a(?x) => "
    r.info.inputs.head.admitsValue(MolValue(Some(1))) shouldEqual true
    r.info.inputs.head.admitsValue(MolValue(None)) shouldEqual false
  }

  it should "admit values by pattern matching with conditional" in {
    val a = m[Option[Int]]

    val r = go { case a(Some(x)) if x > 0 => }

    r.info.toString shouldEqual "a(?x) => "
    r.info.inputs.head.admitsValue(MolValue(Some(1))) shouldEqual true
    r.info.inputs.head.admitsValue(MolValue(Some(0))) shouldEqual false
    r.info.inputs.head.admitsValue(MolValue(None)) shouldEqual false
  }

  it should "admit values by pattern matching with some specified values" in {
    val a = m[(Int, Option[Int])]

    val r = go { case a((1, Some(x))) => }

    r.info.toString shouldEqual "a(?x) => "
    r.info.inputs.head.admitsValue(MolValue((1, Some(1)))) shouldEqual true
    r.info.inputs.head.admitsValue(MolValue((1, Some(0)))) shouldEqual true
    r.info.inputs.head.admitsValue(MolValue((0, Some(0)))) shouldEqual false
    r.info.inputs.head.admitsValue(MolValue((1, None))) shouldEqual false
  }

  it should "run reactions with cross-molecule conditionals but without cross-molecule guards" in {
    val result = withPool(new FixedPool(2)) { tp =>
      val a = m[Int]
      val f = b[Unit, Int]
      site(
        go { case a(x) + a(y) + f(_, r) if x > 0 => r(x + y) }
      )
      a(1)
      a(2)
      f() shouldEqual 3 // If this fails, a message will be printed below.
    }
    if (result.isFailure) println(s"Test failed with message: ${result.failed.get.getMessage}")
    result.isSuccess shouldEqual true
    result.isFailure shouldEqual false
  }

  it should "support concise syntax for Unit-typed molecules" in {
    val a = new M[Unit]("a")
    val f = new B[Unit, Unit]("f")
    a.name shouldEqual "a"
    f.name shouldEqual "f"
    // This should compile without any argument adaptation warnings or errors:
    "val r = go { case a(_) + f(_, r) => a() + r() + f(); val status = r.checkTimeout() }" should compile
  }
}