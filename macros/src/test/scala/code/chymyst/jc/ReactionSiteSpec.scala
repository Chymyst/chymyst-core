package code.chymyst.jc

import Macros.{m, go}
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
}