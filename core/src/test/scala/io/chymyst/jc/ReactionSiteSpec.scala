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

    val x = 10

    val result = withPool(new FixedPool(2)) { tp =>
      site(tp)(
        go { case f(_, r) =>
          if (x > 0) throw new Exception("crash! ignore this exception")
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

    val x = 10

    withPool(new FixedPool(2)) { tp =>
      site(tp)(
        go { case f(_, r) =>
          if (x > 0) throw new Exception("crash! ignore this exception")
          r()
        }
      )
      val thrown = intercept[Exception] {
        f.timeout()(1.seconds)
      }
      thrown.getMessage shouldEqual "Error: In Site{f/B => ...}: Reaction {f/B(_) => } with inputs [f/B()] finished without replying to f/B. Reported error: crash! ignore this exception"
    }.get
  }

  behavior of "pipeline molecule detection"

  it should "pipeline all molecules for reactions without conditions" in {
    val a = m[Unit]
    val c1 = m[Unit]
    val c2 = m[Unit]
    val c3 = m[Int]
    val c4 = m[(Int, Int)]

    val r2 = go { case c2(_) + c2(_) + c2(_) + c3(_) => }
    val r4 = go { case c3(x) + c4((y, z)) => }
    site(
      go { case a(_) => },
      go { case c1(_) + c1(_) => },
      r2,
      go { case c3(x) + c3(y) => },
      r4
    )

    r2.info.independentInputMolecules shouldEqual Set(0, 1, 2, 3)
    r4.info.independentInputMolecules shouldEqual Set(0, 1)
    r4.info.inputs.map(_.flag.isIrrefutable) shouldEqual List(true, true)
    Seq(a, c1, c2, c3, c4).map(_.isPipelined) shouldEqual Seq.fill(5)(true)
  }

  it should "work correctly for reaction with a simple condition and two inputs" in {
    val a = m[Int]
    val c = m[Int]
    val d = m[Option[Int]]
    val e = m[(Int, Int)]
    site(
      go { case a(x) + c(y) if x > 0 => }
    )
    site(
      go { case d(Some(x)) + e((_, 2)) => }
    )
    checkExpectedPipelined(Map(a -> true, c -> true, d -> true, e -> true))
  }

  it should "work correctly for reactions with complicated conditions" in {
    val a = m[Int]
    val c = m[(Int, Int)]
    val d = m[Int]
    val e = m[Int]
    val f = m[Int]
    val g = m[(Int, Int)]

    site(
      go { case a(x) if x > 0 => },
      go { case a(x) + c((y, z)) if x > 0 && y > z => }, // same condition for all reactions; separable guard; so both a and c are pipelined

      go { case d(x) + e(_) => },
      go { case d(x) + f(y) if y > 0 => }, // d, e, f should be pipelined

      go { case g((y, z)) if y > z => } // g is pipelined

    )
    checkExpectedPipelined(Map(e -> true, f -> true, g -> true, c -> true, a -> true, d -> true))
  }

  it should "prevent pipelining for reactions with cross-molecule guards" in {
    val a = m[Int]
    val c = m[(Int, Int)]
    val d = m[Int]
    val e = m[Int]
    val f = m[Int]
    val g = m[Int]
    val h = m[(Int, Int)]
    val j = m[Int]
    val k = m[Int]

    site(
      go { case a(x) + c((y, z)) if y > z && z > 0 && x > 0 => }, // no cross-molecule guard here

      go { case d(x) + e(y) + e(z) if x > 0 && y > 0 => }, // d should be pipelined, but not e since the repeated molecule has a cross-condition

      go { case f(x) + g(y) if x > 0 && y > x => }, // f, g are not pipelined due to cross-molecule guard

      go { case h((y, z)) + j(t) + k(x) if t > 0 && z > x => } // cross-molecule guard prevents both h and k from being pipelined, but not j
    )
    checkExpectedPipelined(Map(a -> true, c -> true, d -> true, e -> false, f -> false, g -> false, h -> false, j -> true, k -> false))
  }

  def checkExpectedPipelined(expectedMap: Map[Molecule, Boolean]) = {
    val transformed = expectedMap.toList.map { case (t, r) => (t, t.isPipelined, r) }
    // Print detailed message.
    val difference = transformed.filterNot { case (_, x, y) => x == y }.map { case (m, actual, expected) => s"$m.isPipelined is $actual instead of $expected" }
    if (difference.nonEmpty) println(s"Test fails: ${difference.mkString("; ")}")
    val (left, right) = (transformed.map(_._2), transformed.map(_._3))
    left shouldEqual right
  }

  it should "not pipeline reactions with non-factorizable conditions" in {
    val c1 = m[Int]
    val c3 = m[Int]
    val c4 = m[Int]
    val c5 = m[Int]

    site(
      go { case c1(x) if x > 0 => }, // c1 is pipelined:
      go { case c1(x) + c1(y) if x < 0 => }, // simple condition but the molecule is repeated, which creates a cross-molecule dependency

      go { case c3(x) + c4(y) => },
      go { case c3(x) + c5(y) if y > 0 => } // other inputs, but no conditions

    )
    checkExpectedPipelined(Map(c1 -> false, c3 -> true, c4 -> true, c5 -> true))
  }

  it should "work correctly for reactions with factorizable conditions" in {
    val c1 = m[Int]
    val c2 = m[Int]
    val c3 = m[Int]
    val c4 = m[Int]
    val c5 = m[Int]

    site(
      go { case c1(x) if x > 0 => }, // c1 is pipelined:
      go { case c1(x) if x < 0 => }, // simple condition
      go { case c1(x) + c1(_) => }, // unconditional with no other inputs - still ok to pipeline c1

      go { case c2(x) if x > 0 => },
      go { case c2(x) if x < 0 => }, // simple condition
      go { case c2(x) + c3(y) => }, // unconditional but has other inputs, so c2 cannot be pipelined (but c3 can be)

      go { case c4(x) if x > 0 => },
      go { case c4(x) + c5(y) => } // other inputs and a condition prevents pipelining of c4

    )
    checkExpectedPipelined(Map(c1 -> true, c2 -> false, c3 -> true, c4 -> false, c5 -> true))
  }

  it should "detect pipelining while also detecting livelock" in {
    val a = m[Unit]
    val c = m[Int]
    intercept[Exception] {
      site(
        go { case c(x) => },
        go { case c(x) + a(_) => } // this is a livelock but we are interested in pipelining of c
      )
    }.getMessage shouldEqual "In Site{a + c => ...; c => ...}: Unavoidable nondeterminism: reaction {a(_) + c(x) => } is shadowed by {c(x) => }"
    checkExpectedPipelined(Map(c -> true, a -> true))
  }

  it should "detect pipelining while also detecting nondeterminism" in {
    val c = m[Int]
    intercept[Exception] {
      site(
        go { case c(x) if x > 0 => },
        go { case c(x) + c(y) => },
        go { case c(x) + c(y) + c(z) => } // unconditional nondeterminism with several inputs
      )
    }.getMessage shouldEqual "In Site{c + c + c => ...; c + c => ...; c => ...}: Unavoidable nondeterminism: reaction {c(x) + c(y) + c(z) => } is shadowed by {c(x) + c(y) => }"
    checkExpectedPipelined(Map(c -> true))
  }


  it should "detect pipelining while also detecting livelock, with condition" in {
    val a = m[Unit]
    val c = m[Int]
    //    intercept[Exception] {
    site(
      go { case c(x) if x > 0 => },
      go { case c(x) + a(_) if x > 0 => } // this is a livelock (although we can't detect it!) but we are interested in pipelining of c
    )
    //    }.getMessage shouldEqual "In Site{a + c => ...; c => ...}: Unavoidable nondeterminism: reaction {a(_) + c(x) => } is shadowed by {c(x) => }"
    checkExpectedPipelined(Map(c -> true, a -> true))
  }

  it should "detect pipelining with two conditions" in {
    val a = m[Int]
    val c = m[Int]

    site(
      go { case a(x) if x > 0 => },
      go { case a(x) + c(y) if x > 0 => } // same condition for all reactions, so a is pipelined
    )
    checkExpectedPipelined(Map(c -> true, a -> true))
  }

}
