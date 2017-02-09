package io.chymyst.jc

import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers, Succeeded}

import scala.concurrent.duration._

class ReactionSiteSpec extends FlatSpec with Matchers with BeforeAndAfterEach {

  behavior of "reaction"

  it should "admit values by simple constant matching" in {
    val a = m[Short]

    val r = go { case a(1) => }

    r.info.toString shouldEqual "a(1) => "
    val input = r.info.inputs.head
    input.admitsValue(MolValue(1)) shouldEqual true
    input.admitsValue(MolValue(0)) shouldEqual false

    input.valType shouldEqual 'Short
    input.isSimpleType shouldEqual true
  }

  it should "admit values by simple variable matching" in {
    val a = m[Int]

    val r = go { case a(x) => }

    r.info.toString shouldEqual "a(x) => "
    val input = r.info.inputs.head
    input.admitsValue(MolValue(1)) shouldEqual true
    input.admitsValue(MolValue(0)) shouldEqual true
  }

  it should "admit values by simple variable matching with conditional" in {
    val a = m[Int]

    val r = go { case a(x) if x > 0 => }

    r.info.toString shouldEqual "a(x if ?) => "
    val input = r.info.inputs.head
    input.admitsValue(MolValue(1)) shouldEqual true
    input.admitsValue(MolValue(0)) shouldEqual false
  }

  it should "admit values by pattern matching" in {
    val a = m[Option[Int]]

    val r = go { case a(Some(x)) => }

    r.info.toString shouldEqual "a(?x) => "
    val input = r.info.inputs.head
    input.admitsValue(MolValue(Some(1))) shouldEqual true
    input.admitsValue(MolValue(None)) shouldEqual false

    input.valType shouldEqual Symbol("Option[Int]")
    input.isSimpleType shouldEqual false
  }

  it should "admit values by pattern matching with conditional" in {
    val a = m[Option[Int]]

    val r = go { case a(Some(x)) if x > 0 => }

    r.info.toString shouldEqual "a(?x) => "
    val input = r.info.inputs.head
    input.admitsValue(MolValue(Some(1))) shouldEqual true
    input.admitsValue(MolValue(Some(0))) shouldEqual false
    input.admitsValue(MolValue(None)) shouldEqual false
  }

  it should "admit values by pattern matching with some specified values" in {
    val a = m[(Int, Option[Int])]

    val r = go { case a((1, Some(x))) => }

    r.info.toString shouldEqual "a(?x) => "
    val input = r.info.inputs.head
    input.admitsValue(MolValue((1, Some(1)))) shouldEqual true
    input.admitsValue(MolValue((1, Some(0)))) shouldEqual true
    input.admitsValue(MolValue((0, Some(0)))) shouldEqual false
    input.admitsValue(MolValue((1, None))) shouldEqual false
  }

  it should "run reactions with cross-molecule conditionals but without cross-molecule guards" in {
    withPool(new FixedPool(2)) { tp =>
      val a = m[Int]
      val f = b[Unit, Int]
      site(tp)(
        go { case a(x) + a(y) + f(_, r) if x > 0 => r(x + y) }
      )
      a(1)
      a(2)
      f() shouldEqual 3 // If this fails, a message will be printed below.
    }.get
  }

  behavior of "shrinkage algorithm"

  it should "shrink empty lists" in {
    val outputs = Nil
    val expectedShrunkOutputs = Nil
    val result = OutputEnvironment.shrink[Int](outputs)
    result shouldEqual expectedShrunkOutputs
  }

  it should "shrink if-then-else to unconditional with OtherOutputPattern" in {
    val item1: OutputEnvironment.OutputItem[Int] = (100, OtherOutputPattern, List(ChooserBlock(1, 0, 2)))
    val item2: OutputEnvironment.OutputItem[Int] = (100, OtherOutputPattern, List(ChooserBlock(1, 1, 2)))
    val outputs: OutputEnvironment.OutputList[Int] = List(item1, item2)
    val expectedShrunkOutputs = List((100, OtherOutputPattern, Nil))
    OutputEnvironment.shrink[Int](outputs) shouldEqual expectedShrunkOutputs
  }

  it should "shrink if-then-else to unconditional with ConstOutputPattern" in {
    val item1: OutputEnvironment.OutputItem[Int] = (100, ConstOutputPattern(123), List(ChooserBlock(1, 0, 2)))
    val item2: OutputEnvironment.OutputItem[Int] = (100, OtherOutputPattern, List(ChooserBlock(1, 1, 2)))
    val outputs: OutputEnvironment.OutputList[Int] = List(item1, item2)
    val expectedShrunkOutputs = List((100, OtherOutputPattern, Nil))
    OutputEnvironment.shrink[Int](outputs) shouldEqual expectedShrunkOutputs
  }

  it should "shrink if-then-else to unconditional with 2 unequal ConstOutputPattern" in {
    val item1: OutputEnvironment.OutputItem[Int] = (100, ConstOutputPattern(123), List(ChooserBlock(1, 0, 2)))
    val item2: OutputEnvironment.OutputItem[Int] = (100, ConstOutputPattern(124), List(ChooserBlock(1, 1, 2)))
    val outputs: OutputEnvironment.OutputList[Int] = List(item1, item2)
    val expectedShrunkOutputs = List((100, OtherOutputPattern, Nil))
    OutputEnvironment.shrink[Int](outputs) shouldEqual expectedShrunkOutputs
  }

  it should "shrink if-then-else to unconditional with 2 equal ConstOutputPattern" in {
    val item1: OutputEnvironment.OutputItem[Int] = (100, ConstOutputPattern(123), List(ChooserBlock(1, 0, 2)))
    val item2: OutputEnvironment.OutputItem[Int] = (100, ConstOutputPattern(123), List(ChooserBlock(1, 1, 2)))
    val outputs: OutputEnvironment.OutputList[Int] = List(item1, item2)
    val expectedShrunkOutputs = List((100, ConstOutputPattern(123), Nil))
    OutputEnvironment.shrink[Int](outputs) shouldEqual expectedShrunkOutputs
  }

  behavior of "error detection for blocking replies"

  it should "report errors when no reply received due to exception" in {
    val f = b[Unit, Unit]

    val result = withPool(new FixedPool(2)) { tp =>
      site(tp)(
        go { case f(_, r) =>
          throw new Exception("crash! ignore this exception")
          r()
        }
      )
      val thrown = intercept[Exception] {
        f()
      }
      thrown.getMessage shouldEqual "Error: In Site{f/B => ...}: Reaction {f/B(_) => } with inputs [f/B()] finished without replying to f/B. Reported error: crash! ignore this exception"
    }
    if (result.isFailure) println(s"Test failed with message: ${result.failed.get.getMessage}")
    result.get shouldEqual Succeeded
    result.isFailure shouldEqual false
  }

  it should "report errors when no reply received due to exception within timeout" in {
    val f = b[Unit, Unit]

    val result = withPool(new FixedPool(2)) { tp =>
      site(tp)(
        go { case f(_, r) =>
          throw new Exception("crash! ignore this exception")
          r()
        }
      )
      val thrown = intercept[Exception] {
        f.timeout()(1.seconds)
      }
      thrown.getMessage shouldEqual "Error: In Site{f/B => ...}: Reaction {f/B(_) => } with inputs [f/B()] finished without replying to f/B. Reported error: crash! ignore this exception"
    }
    if (result.isFailure) println(s"Test failed with message: ${result.failed.get.getMessage}")
    result.get shouldEqual Succeeded
    result.isFailure shouldEqual false
  }

}