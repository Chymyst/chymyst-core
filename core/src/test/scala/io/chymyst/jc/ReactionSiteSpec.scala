package io.chymyst.jc

import io.chymyst.test.Common._
import io.chymyst.test.LogSpec
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Try

class ReactionSiteSpec extends LogSpec with BeforeAndAfterEach {

  behavior of "MolValue hash codes"

  it should "correctly store several molecule copies in a MutableQueueBag" in {
    val v1 = MolValue(())
    val v2 = MolValue(())
    v1.hashCode shouldEqual v2.hashCode
    val b = new MutableQueueBag[AbsMolValue[_]]()
    b.add(v1)
    b.add(v2)
    b.getCountMap.get(v1) shouldEqual Some(2)
  }

  // This test is here only to ensure coverage.
  it should "produce messages for reaction status" in {
    ReactionExitFailure("abc").getMessage shouldEqual ". Reported error: abc"
    ReactionExitSuccess.getMessage shouldEqual ""
    ReactionExitRetryFailure("abc").getMessage shouldEqual ". Reported error: abc"
  }

  behavior of "reaction site hash sums"

  it should "create the same hash sums for the same reaction code" in {
    def makeRS[A](x: A): ReactionSite = {
      val a = m[A]
      site(go { case a(_) ⇒ x })
      a.reactionSite
    }

    val rs1 = makeRS[Int](123)
    val rs2 = makeRS[String]("abc")
    rs1.sha1Code shouldEqual rs2.sha1Code
    rs1.sha1CodeWithNames shouldEqual rs2.sha1CodeWithNames

    rs1.isDistributed shouldEqual false
    rs2.isDistributed shouldEqual false
  }

  it should "create different hash sums for different reaction code with the same molecule names" in {
    val n = 1
    val a1 = new M[Int]("a")
    site(go { case a1(x) ⇒ x + n })

    val code1 = a1.reactionSite.sha1Code
    val code1name = a1.reactionSite.sha1CodeWithNames

    val a2 = new M[Int]("a")
    site(go { case a2(x) ⇒ x + n })
    val code2 = a2.reactionSite.sha1Code
    val code2name = a2.reactionSite.sha1CodeWithNames

    code1 should not equal code2
    code1name should not equal code2name
  }

  it should "detect differences in molecule names when reaction code is the same" in {
    def makeRS[A](x: A, name: String): ReactionSite = {
      val a = new M[A](name)
      site(go { case a(_) ⇒ x })
      a.reactionSite
    }

    val rs1 = makeRS[Int](123, "x")
    val rs2 = makeRS[String]("abc", "x")
    rs1.sha1Code shouldEqual rs2.sha1Code
    rs1.sha1CodeWithNames shouldEqual rs2.sha1CodeWithNames

    val rs3 = makeRS[Int](123, "x")
    val rs4 = makeRS[String]("abc", "y")
    rs3.sha1Code shouldEqual rs4.sha1Code
    rs3.sha1CodeWithNames should not equal rs4.sha1CodeWithNames
  }

  behavior of "distributed reaction sites"

  it should "detect distributed reaction site" in {
    implicit val clusterConfig = ClusterConfig("")
    val a = dm[Int]
    val d = m[Int]
    site(go { case a(_) ⇒ })
    a.reactionSite.isDistributed shouldEqual true
    site(go { case d(x) ⇒ a(x) })
    d.reactionSite.isDistributed shouldEqual false
  }

  it should "detect single-instance and multiple-instance reaction site" in {
    def makeRS(name: String): ReactionSite = {
      val a = new M[Int](name)
      site(go { case a(_) ⇒ })
      a.reactionSite
    }

    val List(rs1a, rs1b, rs2, rs3) = List(1, 1, 2, 3).map(i ⇒ makeRS(i.toString))
    rs1a.isSingleInstance shouldEqual false
    rs1b.isSingleInstance shouldEqual false
    rs2.isSingleInstance shouldEqual true
    rs3.isSingleInstance shouldEqual true
  }

  it should "detect error in multiple-instance reaction site with DMs" in {
    implicit val clusterConfig = ClusterConfig("")

    def makeRS(name: String): ReactionSite = {
      val a = new DM[Int](name)
      site(go { case a(_) ⇒ })
      a.reactionSite
    }

    val rs0 = makeRS("a0")
    val rs1 = makeRS("a1")
    the[Exception] thrownBy makeRS("a1") should
      have message "In Site{a1/D → ...}: Non-single-instance reaction site may not consume distributed molecules, but found molecule(s) a1/D"

    rs0.knownInputMolecules.keySet.head.isBound shouldEqual true
    rs1.knownInputMolecules.keySet.head.isBound shouldEqual true
  }

  it should "detect error when a DM is declared static" in {
    implicit val clusterConfig = ClusterConfig("")
    val a = dm[Int]
    val c = dm[Unit]
    the[Exception] thrownBy site(
      go { case a(x) + c(_) ⇒ a(x) }
      , go { case _ ⇒ a(0) }
    ) should
      have message "In Site{a/D + c/D → ...}: Distributed molecules may not be declared static, but found such molecule(s): a/D"

    // Input molecules remain unbound since reaction site had errors.
    a.isBound shouldEqual false
    c.isBound shouldEqual false
  }

  it should "detect RS error when input DMs belong to different clusters" in {
    val x1a = {
      implicit val clusterConfig = ClusterConfig("", "", "a")
      val x1 = dm[Int]
      x1
    }
    val x1b = {
      implicit val clusterConfig = ClusterConfig("", "", "b")
      val x1 = dm[Int]
      x1
    }

    the[Exception] thrownBy site(
      go { case x1a(_) + x1b(_) ⇒ }
    ) should
      have message "In Site{x1/D + x1/D → ...}: All input distributed molecules must belong to the same cluster, but found molecule(s) x1/D, x1/D"
    // Cannot emit DMs since reaction site is not active. 
    the[ExceptionNoReactionSite] thrownBy x1a(1) should have message "Molecule x1/D is not bound to any reaction site, cannot emit"
  }

  behavior of "reaction"

  it should "admit values by simple constant matching" in {
    val a = m[Short]

    val r = go { case a(1) => }

    r.info.toString shouldEqual "a(1) → "
    val input = r.info.inputs.head
    input.admitsValue(MolValue(1)) shouldEqual true
    input.admitsValue(MolValue(0)) shouldEqual false

    input.valType shouldEqual 'Short
    input.isSimpleType shouldEqual true
  }

  it should "admit values by simple variable matching" in {
    val a = m[Int]

    val r = go { case a(x) => }

    r.info.toString shouldEqual "a(x) → "
    val input = r.info.inputs.head
    input.admitsValue(MolValue(1)) shouldEqual true
    input.admitsValue(MolValue(0)) shouldEqual true
  }

  it should "admit values by simple variable matching with conditional" in {
    val a = m[Int]

    val r = go { case a(x) if x > 0 => }

    r.info.toString shouldEqual "a(x if ?) → "
    val input = r.info.inputs.head
    input.admitsValue(MolValue(1)) shouldEqual true
    input.admitsValue(MolValue(0)) shouldEqual false
  }

  it should "admit values by pattern matching" in {
    val a = m[Option[Int]]

    val r = go { case a(Some(x)) => }

    r.info.toString shouldEqual "a(?x) → "
    val input = r.info.inputs.head
    input.admitsValue(MolValue(Some(1))) shouldEqual true
    input.admitsValue(MolValue(None)) shouldEqual false

    input.valType shouldEqual Symbol("Option[Int]")
    input.isSimpleType shouldEqual false
  }

  it should "admit values by pattern matching with conditional" in {
    val a = m[Option[Int]]

    val r = go { case a(Some(x)) if x > 0 => }

    r.info.toString shouldEqual "a(?x) → "
    val input = r.info.inputs.head
    input.admitsValue(MolValue(Some(1))) shouldEqual true
    input.admitsValue(MolValue(Some(0))) shouldEqual false
    input.admitsValue(MolValue(None)) shouldEqual false
  }

  it should "admit values by pattern matching with some specified values" in {
    val a = m[(Int, Option[Int])]

    val r = go { case a((1, Some(x))) => }

    r.info.toString shouldEqual "a(?x) → "
    val input = r.info.inputs.head
    input.admitsValue(MolValue((1, Some(1)))) shouldEqual true
    input.admitsValue(MolValue((1, Some(0)))) shouldEqual true
    input.admitsValue(MolValue((0, Some(0)))) shouldEqual false
    input.admitsValue(MolValue((1, None))) shouldEqual false
  }

  it should "run reactions with cross-molecule conditionals but without cross-molecule guards" in {
    withPool(FixedPool(2)) { tp =>
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

  case class Cell(x: Int, y: Int, t: Int, state: Int, label: (Int, Int))

  it should "create DSL program for cross-molecule conditionals with repeated inputs" in {
    val c = m[Cell]
    val reaction = go { case
      c(Cell(x0, y0, t0, state0, (0, 0))) +
        c(Cell(x1, y1, t1, state1, (1, 0))) +
        c(Cell(x2, y2, t2, state2, (-1, 0))) +
        c(Cell(x3, y3, t3, state3, (0, 1))) +
        c(Cell(x4, y4, t4, state4, (1, 1))) +
        c(Cell(x5, y5, t5, state5, (-1, 1))) +
        c(Cell(x6, y6, t6, state6, (0, -1))) +
        c(Cell(x7, y7, t7, state7, (1, -1))) +
        c(Cell(x8, y8, t8, state8, (-1, -1)))
      if x0 == x1 && x0 == x2 && x0 == x3 && x0 == x4 && x0 == x5 && x0 == x6 && x0 == x7 && x0 == x8 &&
        y0 == y1 && y0 == y2 && y0 == y3 && y0 == y4 && y0 == y5 && y0 == y6 && y0 == y7 && y0 == y8 &&
        t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 =>
    }
    reaction.info.searchDSLProgram.toList shouldEqual List(
      ChooseMol(0),
      ChooseMol(8),
      ConstrainGuard(7),
      ChooseMol(7),
      ConstrainGuard(6),
      ChooseMol(6),
      ConstrainGuard(5),
      ChooseMol(5),
      ConstrainGuard(4),
      ChooseMol(4),
      ConstrainGuard(3),
      ChooseMol(3),
      ConstrainGuard(2),
      ChooseMol(2),
      ConstrainGuard(1),
      ChooseMol(1),
      ConstrainGuard(0),
      CloseGroup
    )
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

  it should "shrink if-then-else in the presence of NotLastBlock()" in {
    val item1: OutputEnvironment.OutputItem[Int] = (100, ConstOutputPattern(123), List(NotLastBlock(0), ChooserBlock(1, 0, 2)))
    val item2: OutputEnvironment.OutputItem[Int] = (100, ConstOutputPattern(123), List(NotLastBlock(0), ChooserBlock(1, 1, 2)))
    val outputs: OutputEnvironment.OutputList[Int] = List(item1, item2)
    val expectedShrunkOutputs = List((100, ConstOutputPattern(123), List(NotLastBlock(0))))
    OutputEnvironment.shrink[Int](outputs) shouldEqual expectedShrunkOutputs
  }

  it should "shrink if-then-else in the presence of several copies of NotLastBlock()" in {
    val item1: OutputEnvironment.OutputItem[Int] = (100, ConstOutputPattern(123), List(NotLastBlock(10), NotLastBlock(20), ChooserBlock(1, 0, 2)))
    val item2: OutputEnvironment.OutputItem[Int] = (100, ConstOutputPattern(123), List(NotLastBlock(10), NotLastBlock(20), ChooserBlock(1, 1, 2)))
    val outputs: OutputEnvironment.OutputList[Int] = List(item1, item2)
    val expectedShrunkOutputs = List((100, ConstOutputPattern(123), List(NotLastBlock(10))))
    OutputEnvironment.shrink[Int](outputs) shouldEqual expectedShrunkOutputs
  }

  behavior of "error detection for blocking replies"

  it should "not print a message about assigning reporter if reporter did not change" in {
    val memLog = new MemoryLogger
    val reporter = new ErrorsAndWarningsReporter(memLog)
    withPool(FixedPool(1).withReporter(reporter)) { tp ⇒
      tp.reporter = reporter
      memLog.messages.size shouldEqual 0
    }
  }

  it should "report errors when no reply received due to exception" in {
    val f = b[Unit, Unit]
    val x = 10

    withPool(FixedPool(2)) { tp ⇒
      val memLog = new MemoryLogger
      tp.reporter = new ErrorReporter(memLog)
      site(tp)(
        go { case f(_, r) =>
          if (x > 0) throw new Exception("crash! ignore this exception")
          r()
        }
      )
      f.timeout()(500.millis)
      globalLogHas(memLog, "finished without replying", "In Site{f/B → ...}: Reaction {f/B(_) → } with inputs [f/BP()] finished without replying to f/B. Reported error: crash! ignore this exception")
    }.get
  }

  it should "report errors when no reply received due to exception within timeout" in {
    val f = b[Unit, Unit]
    val x = 10

    withPool(FixedPool(2)) { tp ⇒
      val memLog = new MemoryLogger
      tp.reporter = new ErrorReporter(memLog)
      site(tp)(
        go { case f(_, r) =>
          if (x > 0) throw new Exception("crash! ignore this exception")
          r()
        }
      )
      f.timeout()(500.millis)
      globalLogHas(memLog, "finished without replying", "In Site{f/B → ...}: Reaction {f/B(_) → } with inputs [f/BP()] finished without replying to f/B. Reported error: crash! ignore this exception")
    }.get
  }

  behavior of "reaction site error reporting"

  it should "report no errors to empty reporter when no reply received due to exception" in {
    val f = b[Unit, Unit]
    val x = 10
    val memLog = new MemoryLogger
    withPool(FixedPool(2).withReporter(new EmptyReporter(memLog))) { tp ⇒
      site(tp)(
        go { case f(_, r) =>
          if (x > 0) throw new Exception("crash! ignore this exception")
          r()
        }
      )
      f.timeout()(500.millis)
    }.get shouldEqual None
    memLog.messages.size shouldEqual 0
  }

  it should "report debug messages due to restarting of reaction" in {
    val f = b[Unit, Unit]

    withPool(FixedPool(2)) { tp ⇒
      val memLog = new MemoryLogger
      tp.reporter = new DebugAllReporter(memLog)
      @volatile var x: Int = -2

      site(tp)(
        go { case f(_, r) =>
          x += 1
          if (x < 0) throw new Exception(s"crash! ignore this exception (x = $x)") else println(s"ok, have x = $x")
          r()
        }.withRetry
      )
      f.timeout()(2.seconds) shouldEqual Some(())
      Thread.sleep(100) // make sure we gather the log message about the exception; this message is generated concurrenly with the present thread!
      globalLogHas(memLog, "Retry", "In Site{f/B → .../R}: Reaction {f/B(_) → } with inputs [f/BP()] produced Exception. Retry run was scheduled. Message: crash! ignore this exception (x = -1)")
      globalLogHas(memLog, "received reply value", "Debug: In Site{f/B → .../R}: molecule f/B received reply value: Some(())")
    }.get
  }

  it should "report errors when creating a reaction site" in {
    val a = m[Unit]
    val memLog = new MemoryLogger
    val tp = FixedPool(1).withReporter(new ErrorsAndWarningsReporter(memLog))
    the[Exception] thrownBy {
      site(tp)(
        go { case a(x) ⇒ }
        , go { case a(x) ⇒ }
      )
    } should have message "In Site{a → ...; a → ...}: Identical repeated reactions: {a(x) → }, {a(x) → }; Unavoidable indeterminism: reaction {a(x) → } is shadowed by {a(x) → }, reaction {a(x) → } is shadowed by {a(x) → }"
    memLog.messages.size shouldEqual 1
    globalLogHas(memLog, "repeated", "Error: In Site{a → ...; a → ...}: Identical repeated reactions: {a(x) → }, {a(x) → }; Unavoidable indeterminism: reaction {a(x) → } is shadowed by {a(x) → }, reaction {a(x) → } is shadowed by {a(x) → }")
  }

  it should "report no errors with empty reporter when creating a reaction site" in {
    val a = m[Unit]
    val memLog = new MemoryLogger
    val tp = FixedPool(1).withReporter(new EmptyReporter(memLog))
    the[Exception] thrownBy {
      site(tp)(
        go { case a(x) ⇒ }
        , go { case a(x) ⇒ }
      )
    } should have message "In Site{a → ...; a → ...}: Identical repeated reactions: {a(x) → }, {a(x) → }; Unavoidable indeterminism: reaction {a(x) → } is shadowed by {a(x) → }, reaction {a(x) → } is shadowed by {a(x) → }"
    memLog.messages.size shouldEqual 0
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
    checkExpectedPipelined(Map(a -> true, c -> true, d -> true, e -> true)) shouldEqual ""
  }

  it should "work correctly for a separable condition" in {
    val a = m[Int]
    val c = m[Int]
    site(
      go { case a(x) if x > 0 => },
      go { case a(x) + c(y) if x > 0 && y > 0 => } // same condition for all reactions; separable guard; so both a and c are pipelined
    )
    checkExpectedPipelined(Map(a -> true, c -> true)) shouldEqual ""
  }

  it should "work correctly for a separable condition, in different order" in {
    val a = m[Int]
    val c = m[Int]
    site(
      go { case a(x) + c(y) if x > 0 && y > 0 => }, // same condition for all reactions; separable guard; so both a and c are pipelined
      go { case a(x) if x > 0 => }
    )
    checkExpectedPipelined(Map(a -> true, c -> true)) shouldEqual ""
  }

  it should "work correctly for reactions with complicated conditions" in {
    val a = m[Int]
    val c = m[(Int, Int)]
    val d = m[Int]
    val e = m[Int]
    val f = m[Int]
    val g = m[(Int, Int)]

    val n = 10

    site(
      go { case a(x) if x > 0 => },
      go { case a(x) + c((y, z)) if x > 0 && y > z => }, // same condition for all reactions; separable guard; so both a and c are pipelined

      go { case d(x) + e(_) => },
      go { case d(x) + f(y) if y > 0 => }, // d, e, f should be pipelined

      go { case g((y, z)) if y > z && n > 0 => } // g is pipelined

    )
    checkExpectedPipelined(Map(e -> true, f -> true, g -> true, c -> true, a -> true, d -> true)) shouldEqual ""
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
    checkExpectedPipelined(Map(a -> true, c -> true, d -> true, e -> false, f -> false, g -> false, h -> false, j -> true, k -> false)) shouldEqual ""
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
    checkExpectedPipelined(Map(c1 -> false, c3 -> true, c4 -> true, c5 -> true)) shouldEqual ""
  }

  it should "not pipeline if there are conditions and other inputs" in {
    repeat(100) {
      val a = m[Int]
      val c = m[Unit]

      site(
        go { case a(x) if x > 0 => },
        go { case a(x) + c(_) => }
      )
      checkExpectedPipelined(Map(c -> true, a -> false)) shouldEqual ""
    }
  }

  it should "not pipeline if there are conditions and other inputs regardless of reaction order" in {
    repeat(100) {
      val a = m[Int]
      val c = m[Unit]

      site(
        go { case a(x) + c(_) => },
        go { case a(x) if x > 0 => }
      )
      checkExpectedPipelined(Map(c -> true, a -> false)) shouldEqual ""
    }
  }

  it should "not pipeline if there are multiple conditions and other inputs" in {
    repeat(100) {
      val a = m[Int]
      val c = m[Unit]

      site(
        go { case a(x) if x > 0 => },
        go { case a(x) if x < 0 => },
        go { case a(x) + c(_) => }
      )
      checkExpectedPipelined(Map(c -> true, a -> false)) shouldEqual ""
    }
  }

  it should "not pipeline if there are multiple conditions and other inputs, reaction order 1" in {
    repeat(100) {
      val a = m[Int]
      val c = m[Unit]

      site(
        go { case a(x) + c(_) => },
        go { case a(x) if x > 0 => },
        go { case a(x) if x < 0 => }
      )
      checkExpectedPipelined(Map(c -> true, a -> false)) shouldEqual ""
    }
  }

  it should "not pipeline if there are multiple conditions and other inputs, reaction order 2" in {
    repeat(100) {
      val a = m[Int]
      val c = m[Unit]

      site(
        go { case a(x) if x > 0 => },
        go { case a(x) + c(_) => },
        go { case a(x) if x < 0 => }
      )
      checkExpectedPipelined(Map(c -> true, a -> false)) shouldEqual ""
    }
  }

  it should "work correctly for reactions with factorizable conditions" in {
    repeat(100) {
      // make sure this does not fail
      val c1 = m[Int]
      val c2 = m[Int]
      val c3 = m[Int]

      site(
        go { case c1(x) if x > 0 => }, // c1 is pipelined:
        go { case c1(x) if x < 0 => }, // simple condition
        go { case c1(x) + c1(_) => }, // unconditional with no other inputs - still ok to pipeline c1

        go { case c2(x) if x > 0 => },
        go { case c2(x) if x < 0 => }, // simple condition
        go { case c2(x) + c3(y) => } // unconditional but has other inputs, so c2 cannot be pipelined (but c3 can be)
      )
      checkExpectedPipelined(Map(c1 -> true, c2 -> false, c3 -> true)) shouldEqual ""
    }
  }

  it should "detect pipelining while also detecting livelock" in {
    val a = m[Unit]
    val c = m[Int]
    intercept[Exception] {
      site(
        go { case c(x) => },
        go { case c(x) + a(_) => } // this is a livelock but we are interested in pipelining of c
      )
    }.getMessage shouldEqual "In Site{a + c → ...; c → ...}: Unavoidable indeterminism: reaction {a(_) + c(x) → } is shadowed by {c(x) → }"
    checkExpectedPipelined(Map(c -> true, a -> true)) shouldEqual ""
  }

  it should "detect pipelining while also detecting indeterminism" in {
    val c = m[Int]
    intercept[Exception] {
      site(
        go { case c(x) if x > 0 => },
        go { case c(x) + c(y) => },
        go { case c(x) + c(y) + c(z) => } // Unavoidable indeterminism with several repeated inputs.
      )
    }.getMessage shouldEqual "In Site{c + c + c → ...; c + c → ...; c → ...}: Unavoidable indeterminism: reaction {c(x) + c(y) + c(z) → } is shadowed by {c(x) + c(y) → }"
    checkExpectedPipelined(Map(c -> true)) shouldEqual ""
  }

  it should "detect pipelining while also detecting livelock, with condition" in {
    val a = m[Unit]
    val c = m[Int]
    //    intercept[Exception] {
    site(
      go { case c(x) if x > 0 => },
      go { case c(x) + a(_) if x > 0 => } // this is a livelock (although we can't detect it!) but we are interested in pipelining of c
    )
    //    }.getMessage shouldEqual "In Site{a + c → ...; c → ...}: Unavoidable indeterminism: reaction {a(_) + c(x) => } is shadowed by {c(x) => }"
    checkExpectedPipelined(Map(c -> true, a -> true)) shouldEqual ""
  }

  it should "detect pipelining with two conditions" in {
    val a = m[Int]
    val c = m[Int]

    site(
      go { case a(x) if x > 0 => },
      go { case a(x) + c(y) if x > 0 => } // same condition for all reactions, so a is pipelined
    )
    checkExpectedPipelined(Map(c -> true, a -> true)) shouldEqual ""
  }

  it should "detect non-pipelining with two conditions" in {
    val c = m[Int]
    val d = b[Unit, Unit]

    site(
      go { case c(0) => c(1) },
      go { case c(n) + d(_, r) if n > 0 => c(n - 1); r() }
    )
    checkExpectedPipelined(Map(c → false, d → true)) shouldEqual ""
  }

  behavior of "pipelined molecules with conditions"

  it should "refuse to emit when condition fails" in {
    val c = m[Int]
    val memLog = new MemoryLogger
    withPool(FixedPool(1).withReporter(new DebugAllReporter(memLog))) { tp ⇒
      site(tp)(
        go { case c(x) if x > 0 ⇒ }
      )
      c(-1)
      c(0)
      c(1)
    }
    globalLogHas(memLog, "Refusing to emit", "Debug: In Site{c → ...}: Refusing to emit pipelined molecule c(-1) since its value fails the relevant conditions")
  }

  behavior of "errors when initializing reaction site"

  it should "refuse to emit molecules while reaction site is not yet active" in {
    val a = m[Int]
    val c = m[Unit]
    import scala.concurrent.ExecutionContext.Implicits.global
    // While we try to create this reaction site, we also try to emit molecule c() on a different thread. This should fail.
    val resultFuture = Future {
      (1 to 500000).map { i ⇒ Try(c(i)) }
    }

    the[ExceptionCreatingReactionSite] thrownBy
      site(go { case a(_) + c(_) ⇒ }, go { case _ ⇒ a(0) }) should // This is a runtime error due to incorrect usage of static molecule.
      have message "In Site{a + c → ...}: Incorrect static molecule usage: static molecule (a) consumed but not emitted by reaction {a(_) + c(_) → }"

    val result = Await.result(resultFuture, Duration.Inf)
    // There should be no successful emissions of the molecule `c()`.
    result.forall(_.isFailure) shouldEqual true
    // Some molecules c() are not emitted because c() is not yet bound.
    // Later c() is bound but reaction site is not active.
    // There should be no other errors. Let's collect all error messages.
    (result.map(_.failed.get.getMessage).toSet diff Set("Molecule c is not bound to any reaction site, cannot emit", "Molecule c() cannot be emitted because reaction site is inactive")) shouldEqual Set()
  }

}
