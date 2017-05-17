package io.chymyst.jc

import io.chymyst.jc.Core._
import io.chymyst.jc.Macros.{getName, rawTree}
import io.chymyst.test.LogSpec
import org.scalatest.{BeforeAndAfterEach, Matchers}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class MacrosSpec extends LogSpec with Matchers with BeforeAndAfterEach {

  val warmupTimeMs = 200L

  var tp0: Pool = _

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  override def beforeEach(): Unit = {
    tp0 = new FixedPool(4)
  }

  override def afterEach(): Unit = {
    tp0.shutdownNow()
  }

behavior of "reaction sha1"

  it should "compute different reaction sha1 for different conditions" in {
    val a = m[Int]
    val b = m[Int]
    val reaction1 = go { case b(x) if x < 0 ⇒ }
    val reaction2 = go { case a(x) if x < 0 ⇒ }
    val reaction3 = go { case a(x) if x > 0 ⇒ }

    reaction1.info.sha1 should not equal reaction2.info.sha1
    reaction2.info.sha1 should not equal reaction3.info.sha1
    reaction3.info.sha1 should not equal reaction1.info.sha1
  }

  it should "compute the same reaction sha1 regardless of molecule order" in {
    val a = m[Int]
    val b = m[Int]
    val reaction1 = go { case a(x) + b(y) if x < 0 ⇒ }
    val reaction2 = go { case b(y) + a(x) if x < 0 ⇒ }
    val reaction3 = go { case b(y) + a(x) if x < 0 => }
    reaction1.info.sha1 shouldEqual reaction2.info.sha1
    reaction1.info.sha1 shouldEqual reaction3.info.sha1
  }

  behavior of "reaction site"

  it should "track whether molecule emitters are bound" in {
    val a = new M[Unit]("a123")
    val b = new M[Unit]("b")
    val c = new M[Unit]("")

    a.toString shouldEqual "a123"
    b.toString shouldEqual "b"
    c.toString shouldEqual "<no name>"

    a.isBound shouldEqual false
    b.isBound shouldEqual false
    c.isBound shouldEqual false

    site(go { case a(_) + c(_) => b() })

    a.isBound shouldEqual true
    b.isBound shouldEqual false
    c.isBound shouldEqual true

    val expectedReaction = "<no name> + a123 → ..."

    // These methods are private to the package!
    a.emittingReactions shouldEqual Set()
    b.emittingReactions.size shouldEqual 1
    b.emittingReactions.map(_.toString) shouldEqual Set(expectedReaction)
    c.emittingReactions shouldEqual Set()
    a.consumingReactions.length shouldEqual 1
    a.consumingReactions.head.toString shouldEqual expectedReaction
    b.consumingReactions shouldEqual Array()
    c.consumingReactions shouldEqual a.consumingReactions
  }

  behavior of "macros for defining new molecule emitters"

  it should "fail to compute correct names when molecule emitters are defined together" in {
    val (counter, fetch) = (m[Int], b[Unit, String])

    counter.name shouldEqual fetch.name
    counter.name should fullyMatch regex "x\\$[0-9]+"
  }

  it should "compute correct names and classes for molecule emitters" in {
    val a = m[Option[(Int, Int, Map[String, Boolean])]] // complicated type

    a.isInstanceOf[M[_]] shouldEqual true
    a.toString shouldEqual "a"

    val s = b[Map[(Boolean, Unit), Seq[Int]], Option[List[(Int, Option[Map[Int, String]])]]] // complicated type

    s.isInstanceOf[B[_, _]] shouldEqual true
    s.toString shouldEqual "s/B"
  }

  it should "create an emitter of class M[Unit] for m[Unit]" in {
    val a = m[Unit]
    a.isInstanceOf[M[Unit]] shouldEqual true
  }

  it should "create an emitter of class B[Int, Unit] for b[Int, Unit]" in {
    val a = b[Int, Unit]
    a.isInstanceOf[B[Int, Unit]] shouldEqual true
  }

  it should "create an emitter of class B[Unit, Int] for b[Unit, Int]" in {
    val a = b[Unit, Int]
    a.isInstanceOf[B[Unit, Int]] shouldEqual true
  }

  it should "create an emitter of class B[Unit, Unit] for b[Unit, Unit]" in {
    val a = b[Unit, Unit]
    a.isInstanceOf[B[Unit, Unit]] shouldEqual true
  }

  behavior of "macros for inspecting a reaction body"

  it should "correctly sort input molecules with compound values and Option" in {
    val bb = m[(Int, Option[Int])]
    val reaction = go { case bb((1, Some(2))) + bb((0, None)) => }
    reaction.info.toString shouldEqual "bb((0,None)) + bb((1,Some(2))) → "
  }

  it should "correctly sort input molecules with compound values" in {
    val bb = m[(Int, Int)]
    val reaction = go { case bb((1, 2)) + bb((0, 3)) + bb((4, _)) => }
    reaction.info.toString shouldEqual "bb((0,3)) + bb((1,2)) + bb(?) → "
  }

  it should "inspect reaction body with default clause that declares a static molecule" in {
    val a = m[Int]

    val reaction = go { case _ => a(123) }

    reaction.info.inputs shouldEqual Nil
    reaction.info.guardPresence.noCrossGuards shouldEqual true
    reaction.info.outputs shouldEqual List(OutputMoleculeInfo(a, ConstOutputPattern(123), List()))
  }

  it should "inspect reaction body containing local molecule emitters" in {
    val a = m[Int]

    val reaction =
      go { case a(x) =>
        val q = m[Int]
        val s = m[Unit]
        go { case q(_) + s(_) => }
        q(0)
      }
    reaction.info.inputs should matchPattern { case Array(InputMoleculeInfo(`a`, 0, SimpleVarInput('x, _), `simpleVarXSha1`, _)) => }
    reaction.info.outputs shouldEqual List()
  }

  it should "inspect reaction body with embedded join" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    site(tp0)(
      go { case f(_, r) + bb(x) => r(x) },
      go { case a(x) =>
        val p = m[Int]
        site(tp0)(go { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    f.timeout()(1000 millis) shouldEqual Some(2)
  }

  it should "inspect reaction body with embedded join and go" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    site(tp0)(
      go { case f(_, r) + bb(x) => r(x) },
      go { case a(x) =>
        val p = m[Int]
        site(tp0)(go { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    f.timeout()(1000 millis) shouldEqual Some(2)
  }

  val simpleVarXSha1 = ""
  val constantNoneSha1 = "6EEF6648406C333A4035CD5E60D0BF2ECF2606D7"
  val wildcardSha1 = ""
  val constantZeroSha1 = "8227489534FBEA1F404CAAEC9F4CCAEEB9EF2DC1"
  val constantOneSha1 = "356A192B7913B04C54574D18C28D46E6395428AB"

  it should "inspect a two-molecule reaction body with None" in {
    val a = m[Int]
    val bb = m[Option[Int]]

    val result = go { case a(x) + bb(None) => bb(None) }

    (result.info.inputs match {
      case Array(
      InputMoleculeInfo(`a`, 0, SimpleVarInput('x, _), sha_a, Symbol("Int")),
      InputMoleculeInfo(`bb`, 1, ConstInputPattern(None), sha_bb, Symbol("Option[Int]"))
      ) =>
        sha_a shouldEqual simpleVarXSha1
        sha_bb shouldEqual constantNoneSha1
        true
      case _ => false
    }) shouldEqual true

    result.info.outputs shouldEqual List(OutputMoleculeInfo(bb, ConstOutputPattern(None), List()))
    result.info.guardPresence shouldEqual GuardAbsent
    result.info.sha1 shouldEqual "C10342E86F1AEB8992D97883B15773F4A2DBCF1F"
  }

  val ax_qq_reaction_sha1 = "E2D62113017684CECF8542301354A82BF5BB5EC3"

  it should "inspect a two-molecule reaction body" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = go { case a(x) + qq(_) => qq() }

    (result.info.inputs match {
      case Array(
      InputMoleculeInfo(`a`, 0, SimpleVarInput('x, _), `simpleVarXSha1`, 'Int),
      InputMoleculeInfo(`qq`, 1, WildcardInput, sha_qq, 'Unit)
      ) =>
        sha_qq shouldEqual wildcardSha1
        true
      case _ => false
    }) shouldEqual true
    result.info.outputs shouldEqual List(OutputMoleculeInfo(qq, ConstOutputPattern(()), List()))
    result.info.guardPresence shouldEqual AllMatchersAreTrivial
    result.info.sha1 shouldEqual ax_qq_reaction_sha1
  }

  it should "compute reaction sha1 independently of input molecule order" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = go { case a(x) + qq(_) => qq() }
    result.info.sha1 shouldEqual ax_qq_reaction_sha1

    // This reaction is different only in the order of input molecules, so its sha1 must be the same.
    val result2 = go { case qq(_) + a(x) => qq() }
    result2.info.sha1 shouldEqual ax_qq_reaction_sha1
  }

  it should "compute reaction sha1 independently of guard order" in {
    val a = m[Int]

    val result = go { case a(x) + a(y) if x > 1 && y > 1 => a(x + y) }

    // This reaction is different only in the order of guards, so its sha1 must be the same.
    val result2 = go { case a(x) + a(y) if y > 1 && x > 1 => a(x + y) }
    result.info.sha1 shouldEqual result2.info.sha1
  }

  it should "inspect a reaction body with another molecule and extra code" in {
    val a = m[Int]
    val qqq = m[String]

    object testWithApply {
      def apply(x: Int): Int = x + 1
    }

    val result = go {
      case a(_) + a(x) + a(1) =>
        a(x + 1)
        if (x > 0) a(testWithApply(123))
        println(x)
        qqq("")
    }

    (result.info.inputs match {
      case Array(
      InputMoleculeInfo(`a`, 0, WildcardInput, `wildcardSha1`, 'Int),
      InputMoleculeInfo(`a`, 1, SimpleVarInput('x, _), `simpleVarXSha1`, 'Int),
      InputMoleculeInfo(`a`, 2, ConstInputPattern(1), sha_a, 'Int)
      ) =>
        sha_a shouldEqual constantOneSha1
        true
      case _ => false
    }) shouldEqual true
    result.info.outputs should matchPattern { case Array(
    OutputMoleculeInfo(`a`, OtherOutputPattern, List()),
    OutputMoleculeInfo(`a`, OtherOutputPattern, List(ChooserBlock(_, 0, 2))),
    OutputMoleculeInfo(`qqq`, ConstOutputPattern(""), List())
    ) =>
    }

    result.info.guardPresence shouldEqual GuardAbsent
  }

  it should "inspect reaction body with embedded reaction" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = go { case a(x) => go { case qq(_) => a(0) }; qq() }

    result.info.inputs should matchPattern {
      case Array(InputMoleculeInfo(`a`, 0, SimpleVarInput('x, _), `simpleVarXSha1`, _)) =>
    }
    result.info.outputs shouldEqual List(OutputMoleculeInfo(qq, ConstOutputPattern(()), List()))
    result.info.guardPresence shouldEqual AllMatchersAreTrivial
  }

  it should "inspect a very complicated reaction input pattern" in {
    val a = m[Int]
    val c = m[Unit]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    // reaction contains all kinds of pattern-matching constructions
    val result = go {
      case a(p) + a(y) + a(1) + c(()) + c(_) + bb((0, None)) + bb((1, Some(2))) + bb((1, z)) + bb((_, None)) + bb((t, Some(q))) + s(_, r) => s(); a(p + 1); qq(); r(p)
    }

    result.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, SimpleVarInput('p, _), _, Symbol("Int")),
      InputMoleculeInfo(`a`, 1, SimpleVarInput('y, _), _, 'Int),
      InputMoleculeInfo(`a`, 2, ConstInputPattern(1), _, _),
      InputMoleculeInfo(`c`, 3, WildcardInput, _, Symbol("Unit")),
      InputMoleculeInfo(`c`, 4, WildcardInput, _, 'Unit),
      InputMoleculeInfo(`bb`, 5, ConstInputPattern((0, None)), _, Symbol("(Int, Option[Int])")),
      InputMoleculeInfo(`bb`, 6, ConstInputPattern((1, Some(2))), _, _),
      InputMoleculeInfo(`bb`, 7, OtherInputPattern(_, List('z), false), _, _),
      InputMoleculeInfo(`bb`, 8, OtherInputPattern(_, List(), false), _, _),
      InputMoleculeInfo(`bb`, 9, OtherInputPattern(_, List('t, 'q), false), _, _),
      InputMoleculeInfo(`s`, 10, WildcardInput, _, 'Unit)
      ) =>
    }
    result.info.outputs shouldEqual List(
      OutputMoleculeInfo(s, ConstOutputPattern(()), List()),
      OutputMoleculeInfo(a, OtherOutputPattern, List()),
      OutputMoleculeInfo(qq, ConstOutputPattern(()), List())
    )

    result.info.toString shouldEqual "a(1) + a(p) + a(y) + bb((0,None)) + bb((1,Some(2))) + bb(?z) + bb(?) + bb(?t,q) + c(_) + c(_) + s/B(_) → s/B() + a(?) + qq()"
  }

  it should "not fail to define a reaction with correct inputs with non-default pattern-matching in the middle of reaction" in {
    val a = m[Option[Int]]
    val b = m[Unit]
    val c = m[Unit]

    site(tp0)(go { case b(_) + a(Some(x)) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with default pattern-matching in the middle of reaction" in {
    val a = m[Option[Int]]
    val b = m[Unit]
    val c = m[Unit]

    site(tp0)(go { case b(_) + a(None) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-simple default pattern-matching in the middle of reaction" in {
    val a = m[Seq[Int]]
    val b = m[Unit]
    val c = m[Unit]

    site(go { case b(_) + a(List()) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "not fail to define a simple reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = m[Option[Int]]
    val b = m[Unit]
    val c = m[Unit]

    site(tp0)(go { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = m[Option[Int]]
    val b = m[Unit]
    val c = m[Unit]

    site(tp0)(go { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at start of reaction" in {
    val a = m[Option[Int]]
    val b = m[Unit]
    val c = m[Unit]

    site(tp0)(go { case a(Some(x)) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "run reactions correctly with non-default pattern-matching at start of reaction" in {
    val a = m[Option[Int]]
    val f = b[Unit, Int]

    site(tp0)(go { case a(Some(x)) + f(_, r) => r(x) })

    a(Some(1))
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Site{a + f/B → ...}\nMolecules: a/P(Some(1))"
    f.timeout()(2.second) shouldEqual Some(1)
    a.logSoup shouldEqual "Site{a + f/B → ...}\nNo molecules"
  }

  it should "not run a reaction whose static guard is false" in {
    val a = m[Option[Int]]
    val f = b[Unit, Int]

    val n = 1

    site(tp0)(go { case a(Some(x)) + f(_, r) if n < 1 => r(x) })

    a(Some(1))
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Site{a + f/B → ...}\nMolecules: a/P(Some(1))"
    f.timeout()(2.second) shouldEqual None
    waitSome() // Removal of blocking molecule upon timeout is now asynchronous.
    waitSome()
    a.logSoup shouldEqual "Site{a + f/B → ...}\nMolecules: a/P(Some(1))"
  }

  it should "not run a reaction whose cross-molecule guard is false" in {
    val a = m[Option[Int]]
    val f = b[Int, Int]

    val n = 2

    site(tp0)(go { case a(Some(x)) + f(y, r) if x < y + n => r(x) })

    a(Some(10))
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Site{a + f/B → ...}\nMolecules: a(Some(10))"
    f.timeout(0)(2.second) shouldEqual None
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Site{a + f/B → ...}\nMolecules: a(Some(10))"
  }

  it should "run a reaction whose cross-molecule guard is true" in {
    val a = m[Option[Int]]
    val f = b[Int, Int]

    val n = 2

    site(tp0)(go { case a(Some(x)) + f(y, r) if x < y + n => r(x) })

    a(Some(1))
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Site{a + f/B → ...}\nMolecules: a(Some(1))"
    f.timeout(0)(2.second) shouldEqual Some(1)
    a.logSoup shouldEqual "Site{a + f/B → ...}\nNo molecules"
  }

  it should "run a reaction with cross-molecule guards and some independent molecules" in {
    val a = m[Option[Int]]
    val f = b[Int, Int]
    val c = m[Int]

    val n = 2

    site(tp0)(go { case a(Some(x)) + c(z) + f(y, r) if x < y + n => r(x + z) })

    a(Some(1))
    c(123)
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Site{a + c + f/B → ...}\nMolecules: a(Some(1)) + c/P(123)"
    f.timeout(0)(2.second) shouldEqual Some(124)
    a.logSoup shouldEqual "Site{a + c + f/B → ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at start of reaction" in {
    val a = m[Int]
    val b = m[Unit]
    val c = m[Unit]

    site(tp0)(go { case a(1) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant default option pattern-matching at start of reaction" in {
    val a = m[Option[Int]]
    val b = m[Unit]
    val c = m[Unit]

    site(tp0)(go { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "determine constant input and output patterns correctly" in {
    val a = m[Option[Int]]
    val b = m[String]
    val c = m[(Int, Int)]
    val d = m[Unit]
    val e = m[Either[Option[Int], String]]

    val r = go { case a(Some(1)) + b("xyz") + d(()) + c((2, 3)) + e(Left(Some(1))) + e(Right("input")) =>
      a(Some(2)); e(Left(Some(2))); e(Right("output"))
    }

    r.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, ConstInputPattern(Some(1)), _, Symbol("Option[Int]")),
      InputMoleculeInfo(`b`, 1, ConstInputPattern("xyz"), _, 'String),
      InputMoleculeInfo(`d`, 2, WildcardInput, _, _),
      InputMoleculeInfo(`c`, 3, ConstInputPattern((2, 3)), _, Symbol("(Int, Int)")),
      InputMoleculeInfo(`e`, 4, ConstInputPattern(Left(Some(1))), _, Symbol("scala.util.Either[Option[Int],String]")),
      InputMoleculeInfo(`e`, 5, ConstInputPattern(Right("input")), _, _)
      ) =>
    }
    r.info.outputs shouldEqual List(
      OutputMoleculeInfo(a, ConstOutputPattern(Some(2)), List()),
      OutputMoleculeInfo(e, ConstOutputPattern(Left(Some(2))), List()),
      OutputMoleculeInfo(e, ConstOutputPattern(Right("output")), List())
    )
    r.info.guardPresence shouldEqual GuardAbsent
    r.info.sha1 shouldEqual "092BC1D2E16ECF2AC24374BC00EFB8BE1B5190F8"
  }

  it should "detect output molecules with constant values" in {
    val c = m[Int]
    val bb = m[(Int, Int)]
    val bbb = m[Int]
    val cc = m[Option[Int]]

    val r1 = go { case bbb(x) => c(x); bb((1, 2)); bb((3, x)) }
    val r2 = go { case bbb(_) + c(_) => bbb(0) }
    val r3 = go { case bbb(x) + c(_) + c(_) => bbb(1); c(x); bbb(2); cc(None); cc(Some(1)) }

    r1.info.outputs shouldEqual List(
      OutputMoleculeInfo(c, OtherOutputPattern, List()),
      OutputMoleculeInfo(bb, ConstOutputPattern((1, 2)), List()),
      OutputMoleculeInfo(bb, OtherOutputPattern, List())
    )
    r2.info.outputs shouldEqual List(OutputMoleculeInfo(bbb, ConstOutputPattern(0), List()))
    r3.info.outputs shouldEqual List(
      OutputMoleculeInfo(bbb, ConstOutputPattern(1), List()),
      OutputMoleculeInfo(c, OtherOutputPattern, List()),
      OutputMoleculeInfo(bbb, ConstOutputPattern(2), List()),
      OutputMoleculeInfo(cc, ConstOutputPattern(None), List()),
      OutputMoleculeInfo(cc, ConstOutputPattern(Some(1)), List())
    )
  }

  it should "compute input pattern variables correctly" in {
    val a = m[Int]
    val bb = m[(Int, Int, Option[Int], (Int, Option[Int]))]
    val c = m[Unit]

    val result = go { case a(1 | 2) + c(()) + bb(p@(ytt, 1, None, (s, Some(t)))) => }
    result.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, OtherInputPattern(_, List(), false), _, _),
      InputMoleculeInfo(`c`, 1, WildcardInput, _, _),
      InputMoleculeInfo(`bb`, 2, OtherInputPattern(_, List('p, 'ytt, 's, 't), false), _, Symbol("(Int, Int, Option[Int], (Int, Option[Int]))"))
      ) =>
    }
    result.info.toString shouldEqual "a(?) + bb(?p,ytt,s,t) + c(_) → "
  }

  it should "create partial functions for matching from reaction body" in {
    val aa = m[Option[Int]]
    val bb = m[(Int, Option[Int])]

    val result = go { case aa(Some(x)) + bb((0, None)) => aa(Some(x + 1)) }

    result.info.outputs shouldEqual List(OutputMoleculeInfo(aa, OtherOutputPattern, List()))

    val pat_aa = result.info.inputs.head
    pat_aa.molecule shouldEqual aa
    val pat_bb = result.info.inputs(1)
    pat_bb.molecule shouldEqual bb

    (pat_aa.flag match {
      case OtherInputPattern(matcher, vars, false) =>
        matcher.isDefinedAt(Some(1)) shouldEqual true
        matcher.isDefinedAt(None) shouldEqual false
        vars shouldEqual List('x)
        true
      case _ => false
    }) shouldEqual true

    pat_bb.flag shouldEqual ConstInputPattern((0, None))
  }

  behavior of "output environment computation"

  it should "ignore + and some other functions" in {
    val a = m[Unit]
    val c = m[Unit]
    val f = b[Unit, String]

    val r = go { case c(_) =>
      a() + a()
      a()
      Some(a())
      List(a(), a(), a())
      Left(a())
      Right(a())
      (a(), a(), a())
      Symbol(f.timeout()(1.second).get)
      val x = f()
      if (f() == x) ()
      f() match {
        case "" => true
      }
    }

    val aOut = OutputMoleculeInfo(a, ConstOutputPattern(()), List())
    val fOut = OutputMoleculeInfo(f, ConstOutputPattern(()), List())

    r.info.outputs shouldEqual Array(
      aOut, aOut, aOut, aOut, aOut, aOut, aOut, aOut, aOut, aOut, aOut, aOut, fOut, fOut, fOut, fOut
    )
  }

  it should "detect f.timeout()()" in {
    val a = m[Unit]
    val f = b[Unit, Unit]
    val r = go { case a(_) => Some(f.timeout()(1.second).get).foreach(_ => ()) }
    r.info.outputs shouldEqual Array(
      OutputMoleculeInfo(f, ConstOutputPattern(()), List())
    )
  }

  it should "detect molecules emitted in if-then-else blocks" in {
    val a = m[Int]
    val c = m[Unit]
    val d = m[Unit]
    val r = go { case a(x) => if (x > 0) c() else d() }

    r.info.outputs(0).environments should matchPattern { case List(ChooserBlock(_, 0, 2)) => }
    r.info.outputs(1).environments should matchPattern { case List(ChooserBlock(_, 1, 2)) => }
  }

  it should "detect molecules emitted in several if-then-else blocks" in {
    val a = m[Int]
    val c = m[Unit]
    val d = m[Unit]
    val r = go { case a(x) => if (x > 0) c() else d(); if (x < 0) c() else d() }

    r.info.outputs(0).environments should matchPattern { case List(ChooserBlock(2, 0, 2)) => }
    r.info.outputs(1).environments should matchPattern { case List(ChooserBlock(2, 1, 2)) => }
    r.info.outputs(2).environments should matchPattern { case List(ChooserBlock(4, 0, 2)) => }
    r.info.outputs(3).environments should matchPattern { case List(ChooserBlock(4, 1, 2)) => }
  }

  it should "detect molecules emitted in foreach blocks" in {
    val a = m[Int]
    val c = m[Int]

    val r = go { case a(x) => if (x > 0) (1 to 10).foreach(i => c(i)) }

    r.info.outputs(0).environments should matchPattern {
      case List(ChooserBlock(2, 0, 2), FuncBlock(5, "scala.collection.immutable.Range.foreach"), FuncLambda(6)) =>
    }
  }

  it should "detect molecules emitted in foreach blocks with short apply syntax" in {
    val a = m[Int]
    val c = m[Int]

    val r = go { case a(x) => if (x > 0) (1 to 10).foreach(c) }

    r.info.outputs(0).environments should matchPattern {
      case List(ChooserBlock(2, 0, 2), FuncBlock(5, "scala.collection.immutable.Range.foreach")) =>
    }
  }

  it should "detect molecules emitted in map blocks" in {
    val a = m[Int]
    val c = m[Int]

    val r = go { case c(x) => if (x > 0) (1 to 10).map { i => a(i); 1 } }

    r.info.outputs(0).environments should matchPattern {
      case List(ChooserBlock(2, 0, 2), FuncBlock(5, "scala.collection.TraversableLike.map"), FuncLambda(6)) =>
    }
  }

  it should "detect molecules emitted in map blocks with short syntax" in {
    val a = m[Int]
    val c = m[Int]

    val r = go { case a(x) => (1 to 10).map(c).forall(_ => true) }

    r.info.outputs(0).environments should matchPattern {
      case List(FuncBlock(3, "scala.collection.TraversableLike.map")) =>
    }
  }

  it should "detect molecules emitted in arguments of other molecules" in {
    val a = m[Int]
    val c = b[Int, Int]

    val r = go { case a(x) => c(if (x > 0) c(x) else c(x + 1)) }

    r.info.outputs(0).environments shouldEqual List(ChooserBlock(2, 0, 2))
    r.info.outputs(1).environments shouldEqual List(ChooserBlock(2, 1, 2))
    r.info.outputs(2).environments shouldEqual List()
  }

  it should "detect molecules emitted in custom apply()" in {
    val a = m[Int]
    val c = m[Int]

    val r = go { case a(x) => FuncLambda {
      c(x)
      1
    }
    }

    r.info.outputs(0).environments should matchPattern {
      case List(FuncBlock(1, "io.chymyst.jc.FuncLambda.apply")) =>
    }
  }

  it should "detect molecules emitted in user-defined methods" in {
    val a = m[Int]
    val c = m[Int]

    def f(x: Unit): Int = 1

    val r = go { case a(x) => c(if (x > 0) f(c(x))
    else {
      c(x)
      2
    })
    }

    r.info.outputs(0).environments should matchPattern {
      case List(ChooserBlock(2, 0, 2), FuncBlock(3, "io.chymyst.jc.MacrosSpec.f")) =>
    }
  }

  it should "detect molecules emitted in user-defined methods within reaction scope" in {
    val a = m[Int]
    val c = m[Int]

    val r = go { case a(x) =>
      def f(x: Unit): Int = 1

      c(if (x > 0) f(c(x))
      else {
        c(x)
        2
      })
    }

    r.info.outputs(0).environments should matchPattern {
      case List(ChooserBlock(x, 0, 2), FuncBlock(y, "io.chymyst.jc.MacrosSpec.$anonfun.f")) if y > x =>
    }
  }

  it should "detect molecules emitted in while loops" in {
    val a = m[Int]
    val c = m[Int]

    val r = go { case a(x) => if (x > 0)
      while ({c(x); true }) {
        c(x)
      }
    }

    r.info.outputs(0).environments should matchPattern { case List(ChooserBlock(2, 0, 2), AtLeastOneEmitted(3, "condition of while")) => }
    r.info.outputs(1).environments should matchPattern { case List(ChooserBlock(2, 0, 2), FuncBlock(3, "while")) => }
  }

  it should "detect molecules emitted in do-while loops" in {
    val a = m[Int]
    val c = m[Int]

    val r = go { case a(x) => if (x > 0)
      do {
        c(x)
      } while (x > 0)
    }

    r.info.outputs(0).environments should matchPattern { case List(ChooserBlock(2, 0, 2), AtLeastOneEmitted(_, "do while")) => }
  }

  it should "detect molecules emitted in match-case blocks with nested if-then-else" in {
    val a = m[Int]
    val c = m[Unit]
    val d = m[Unit]

    val r = go { case a(x) =>
      x match {
        case 0 => c(); if (x > 0) c()
        case 1 => d()
        case 2 => c(); if (x > 0) d() else c()
      }
    }

    r.info.outputs(0).environments should matchPattern { case List(ChooserBlock(1, 0, 3)) => }
    r.info.outputs(1).environments should matchPattern { case List(ChooserBlock(1, 0, 3), ChooserBlock(3, 0, 2)) => }
    r.info.outputs(2).environments should matchPattern { case List(ChooserBlock(1, 1, 3)) => }
    r.info.outputs(3).environments should matchPattern { case List(ChooserBlock(1, 2, 3)) => }
    r.info.outputs(4).environments should matchPattern { case List(ChooserBlock(1, 2, 3), ChooserBlock(5, 0, 2)) => }
    r.info.outputs(5).molecule shouldEqual c
    r.info.outputs(5).environments should matchPattern { case List(ChooserBlock(1, 2, 3), ChooserBlock(5, 1, 2)) => }
  }

  it should "detect molecules emitted in anonymous functions" in {
    val a = m[Int]
    val c = m[Unit]
    val r = go { case a(x) =>
      val pf: Int => Unit = { x => c() }
      pf(0)
    }
    r.info.outputs(0).environments should matchPattern { case List(FuncLambda(_)) => }
  }

  it should "detect molecules emitted in partial functions" in {
    val a = m[Int]
    val c = m[Unit]
    val r = go { case a(x) =>
      val pf: PartialFunction[Int, Unit] = {
        case 123 => c()
      }
      pf(0)
    }
    r.info.outputs(0).environments should matchPattern { case List(FuncLambda(1), ChooserBlock(2, 0, 1)) => }
  }

  behavior of "output value computation"

  it should "compute outputs for an inline reaction" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      site(
        go { case a(1) => a(1) }
      )
      a.consumingReactions.map(_.info.outputs) shouldEqual Array(Array(OutputMoleculeInfo(a, ConstOutputPattern(1), List())))
    }
    thrown.getMessage shouldEqual "In Site{a → ...}: Unavoidable livelock: reaction {a(1) → a(1)}"
  }

  it should "compute inputs and outputs for an inline nested reaction" in {
    val a = m[Int]
    site(
      go {
        case a(1) =>
          val c = m[Int]
          site(go { case c(_) => })
          c(2)
          a(2)
      }
    )
    a.emittingReactions.size shouldEqual 1
    a.consumingReactions.length shouldEqual 1
    a.consumingReactions.map(_.info.outputs).head shouldEqual List(OutputMoleculeInfo(a, ConstOutputPattern(2), List()))
    a.consumingReactions.map(_.info.inputs).head shouldEqual List(InputMoleculeInfo(a, 0, ConstInputPattern(1), constantOneSha1, 'Int))
    a.emittingReactions.map(_.info.outputs).head shouldEqual List(OutputMoleculeInfo(a, ConstOutputPattern(2), List()))
    a.emittingReactions.map(_.info.inputs).head shouldEqual List(InputMoleculeInfo(a, 0, ConstInputPattern(1), constantOneSha1, Symbol("Int")))
  }

  it should "compute outputs for an inline nested reaction" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      site(
        go {
          case a(1) =>
            val c = m[Int]
            site(go { case c(_) => })
            c(2)
            a(1)
        }
      )
    }
    thrown.getMessage shouldEqual "In Site{a → ...}: Unavoidable livelock: reaction {a(1) → a(1)}"
  }

  it should "compute outputs in the correct order for a reaction with no livelock" in {
    val a = m[Int]
    val b = m[Int]
    site(
      go { case a(2) => b(2); a(1); b(1) }
    )
    a.consumingReactions.length shouldEqual 1
    a.consumingReactions.map(_.info.outputs).head shouldEqual List(
      OutputMoleculeInfo(b, ConstOutputPattern(2), List()),
      OutputMoleculeInfo(a, ConstOutputPattern(1), List()),
      OutputMoleculeInfo(b, ConstOutputPattern(1), List())
    )
  }

  it should "recognize nested emissions of non-blocking molecules in the correct order" in {
    val a = m[Int]
    val c = m[Int]
    val d = m[Boolean]

    site(
      go { case a(x) + d(_) => c({
        a(1)
        2
      })
      }
    )

    a.isBound shouldEqual true
    c.isBound shouldEqual false

    val reaction = a.consumingReactions.head
    c.emittingReactions.head shouldEqual reaction
    a.emittingReactions.head shouldEqual reaction

    reaction.info.inputs should matchPattern {
      case Array(InputMoleculeInfo(`a`, 0, SimpleVarInput('x, _), `simpleVarXSha1`, 'Int), InputMoleculeInfo(`d`, 1, WildcardInput, `wildcardSha1`, 'Boolean)) =>
    }
    reaction.info.outputs shouldEqual List(
      OutputMoleculeInfo(a, ConstOutputPattern(1), List()),
      OutputMoleculeInfo(c, OtherOutputPattern, List())
    )
  }

  it should "recognize nested emissions of blocking molecules and reply values" in {
    val a = b[Int, Int]
    val c = m[Int]
    val d = m[Unit]

    site(
      go { case d(_) => c(a(1)) },
      go { case a(x, r) => d(r(x)) }
    )

    a.isBound shouldEqual true
    c.isBound shouldEqual false
    d.isBound shouldEqual true

    val reaction1 = d.consumingReactions.head
    a.emittingReactions.head shouldEqual reaction1
    c.emittingReactions.head shouldEqual reaction1

    val reaction2 = a.consumingReactions.head
    d.emittingReactions.head shouldEqual reaction2

    reaction1.info.inputs shouldEqual List(InputMoleculeInfo(d, 0, WildcardInput, wildcardSha1, 'Unit))
    reaction1.info.outputs shouldEqual Array(
      OutputMoleculeInfo(a, ConstOutputPattern(1), List()),
      OutputMoleculeInfo(c, OtherOutputPattern, List())
    )

    reaction2.info.inputs should matchPattern {
      case Array(InputMoleculeInfo(`a`, 0, SimpleVarInput('x, _), `simpleVarXSha1`, _)) =>
    }
    reaction2.info.outputs shouldEqual List(OutputMoleculeInfo(d, OtherOutputPattern, List()))
  }

  behavior of "output environment shrinkage"

  it should "detect simple constant due to perfect if-then-else shrinkage" in {
    val a = m[Int]
    val r = go { case a(1) => if (true) a(1) else a(1) } // This livelock cannot be detected at compile time because it can't evaluate constants.
    r.info.shrunkOutputs shouldEqual Array(OutputMoleculeInfo(a, ConstOutputPattern(1), Nil))
  }

  it should "detect other pattern due to non-perfect if-then-else shrinkage" in {
    val a = m[Int]
    val r = go { case a(1) => if (true) a(1) else a(2) }
    r.info.shrunkOutputs shouldEqual Array(OutputMoleculeInfo(a, OtherOutputPattern, Nil))
  }

  behavior of "auxiliary functions"

  it should "find expression trees for constant values" in {
    rawTree(1) shouldEqual "Literal(Constant(1))"
    rawTree(None) shouldEqual "Select(Ident(scala), scala.None)"

    (Set(
      "Apply(TypeApply(Select(Select(Ident(scala), scala.Some), TermName(\"apply\")), List(TypeTree())), List(Literal(Constant(1))))"
    ) contains rawTree(Some(1))) shouldEqual true
  }

  it should "find expression trees for matchers" in {
    rawTree(Some(1) match { case Some(1) => }) shouldEqual "Match(Apply(TypeApply(Select(Select(Ident(scala), scala.Some), TermName(\"apply\")), List(TypeTree())), List(Literal(Constant(1)))), List(CaseDef(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Literal(Constant(1)))), EmptyTree, Literal(Constant(())))))"
  }

  it should "find enclosing symbol names with correct scopes" in {
    val x = getName
    x shouldEqual "x"

    val y = {
      val z = getName
      (z, getName)
    }
    y shouldEqual (("z", "y"))

    val (y1, y2) = {
      val z = getName
      (z, getName)
    }
    y1 shouldEqual "z"
    y2 should fullyMatch regex "x\\$[0-9]+"
  }

  behavior of "errors while emitting static molecules"

  it should "refuse to emit static molecule from non-reaction thread" in {
    val dIncorrectStaticMol = m[Unit]
    val e = m[Unit]

    val r1 = go { case dIncorrectStaticMol(_) + e(_) => dIncorrectStaticMol(); 123 }

    site(tp0)(
      r1,
      go { case _ => dIncorrectStaticMol() }
    )

    val inputs = new InputMoleculeList(2)
    inputs(0) = MolValue(())
    inputs(1) = MolValue(())
    val thrown = intercept[Exception] {
      r1.body.apply((inputs.length - 1, inputs)) shouldEqual 123 // Reaction ran on a non-reaction thread (i.e. on this thread) and attempted to emit the static molecule.
    }
    val expectedMessage = s"In Site{${dIncorrectStaticMol.name} + e → ...}: Refusing to emit static molecule ${dIncorrectStaticMol.name}() because this thread does not run a chemical reaction"
    thrown.getMessage shouldEqual expectedMessage
    waitSome()
    e.logSoup shouldEqual s"Site{${dIncorrectStaticMol.name} + e → ...}\nMolecules: ${dIncorrectStaticMol.name}/P()"
  }

  it should "refuse to emit static molecule from a reaction that did not consume it when this cannot be determined statically" in {
    val c = new M[Unit]("c")
    val dIncorrectStaticMol = m[Unit]
    val e = new M[M[Unit]]("e")

    site(tp0)(
      go { case e(s) => s() },
      go { case dIncorrectStaticMol(_) + c(_) => dIncorrectStaticMol() },
      go { case _ => dIncorrectStaticMol() }
    )

    e(dIncorrectStaticMol)
    waitSome()
    e.logSoup shouldEqual s"Site{c + ${dIncorrectStaticMol.name} → ...; e → ...}\nMolecules: ${dIncorrectStaticMol.name}/P()"
    globalErrorLog.exists(_.contains(s"In Site{c + ${dIncorrectStaticMol.name} → ...; e → ...}: Refusing to emit static molecule ${dIncorrectStaticMol.name}() because this reaction {e(s) → } does not consume it")) shouldEqual true
  }

}
