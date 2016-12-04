package code.winitzki.jc

import JoinRun._
import Macros._

import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt

class MacrosSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  val warmupTimeMs = 50

  val tp0 = new FixedPool(4)

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  override def afterAll(): Unit = {
    tp0.shutdownNow()
  }

  behavior of "macros for defining new molecule injectors"

  it should "compute invocation names for molecule injectors" in {
    val a = m[Int]

    a.toString shouldEqual "a"

    val s = b[Map[(Boolean, Unit), Seq[Int]], Option[List[(Int, Option[Map[Int, String]])]]] // complicated type

    s.toString shouldEqual "s/B"
  }

  behavior of "macros for inspecting a reaction body"

  it should "inspect reaction body containing local molecule injectors" in {
    val a = m[Int]

    val reaction =
      & { case a(x) =>
        val q = new M[Int]("q")
        val s = new M[Unit]("s")
        val reaction1 = & { case q(_) + s(()) => }
        q(0)
      }
    reaction.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar))
    reaction.info.outputs shouldEqual List()
  }

  it should "inspect reaction body with embedded join" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    join(tp0, tp0)(
      & { case f(_, r) + bb(x) => r(x) },
      & { case a(x) =>
        val p = m[Int]
        join(tp0, tp0)(& { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    waitSome()
    f(timeout = 100 millis)() shouldEqual Some(2)
  }

  it should "inspect reaction body with embedded join and runSimple" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    join(tp0, tp0)(
      runSimple { case f(_, r) + bb(x) => r(x) },
      runSimple { case a(x) =>
        val p = m[Int]
        join(tp0, tp0)(& { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    waitSome()
    f(timeout = 100 millis)() shouldEqual Some(2)
  }

  it should "inspect a simple reaction body" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    val result = & { case a(x) => qq() }

    result.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar))
    result.info.outputs shouldEqual List(qq)
    result.info.sha1 shouldEqual "D28AED0C1674FFB41F65B825902D334B2DC9E11E"
  }

  it should "inspect a reaction body with another molecule and extra code" in {
    val a = m[Int]
    val qqq = m[String]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    object testWithApply {
      def apply(x: Int): Int = x + 1
    }

    val result = & {
      case a(_) + a(x) + a(1) =>
        a(x + 1)
        if (x > 0) a(testWithApply(123))
        println(x)
        qqq("")
    }

    result.info.inputs shouldEqual List(InputMoleculeInfo(a, Wildcard), InputMoleculeInfo(a, SimpleVar), InputMoleculeInfo(a, SimpleConst(1)))
    result.info.outputs shouldEqual List(a, a, qqq)
  }

  it should "inspect reaction body with embedded reaction" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    val result = & { case a(x) => & { case qq(_) => a(0) }; qq() }

    result.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar))
    result.info.outputs shouldEqual List(qq)
  }

  it should "inspect a very complicated reaction body" in {
    val a = m[Int]
    val c = m[Unit]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    // reaction contains all kinds of pattern-matching constructions, blocking molecule in a guard, and unit values in molecules
    val result = & {
      case a(p) + a(y) + a(1) + c(()) + c(_) + bb(_) + bb((1, z)) + bb((_, None)) + bb((t, Some(q))) + s(_, r) if y > 0 && s() > 0 => a(p + 1); qq(); r(p)
    }

    (result.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, SimpleVar),
      InputMoleculeInfo(`a`, SimpleVar),
      InputMoleculeInfo(`a`, SimpleConst(1)),
      InputMoleculeInfo(`c`, SimpleConst(())),
      InputMoleculeInfo(`c`, Wildcard),
      InputMoleculeInfo(`bb`, Wildcard),
      InputMoleculeInfo(`bb`, OtherPattern(_)),
      InputMoleculeInfo(`bb`, OtherPattern(_)),
      InputMoleculeInfo(`bb`, OtherPattern(_)),
      InputMoleculeInfo(`s`, Wildcard)
      ) => true
      case _ => false
    }) shouldEqual true

    result.info.outputs shouldEqual List(s, a, qq)
  }

  it should "inspect reaction body with two cases" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = & {
      case a(x) => qq()
      case qq(_) + a(y) => qq()
    }
    // TODO: add a test for inspecting this reaction
  }

  it should "define a reaction with correct inputs with non-default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(runSimple { case b(_) + a(Some(x)) + c(_) => })

    a.logSoup shouldEqual "Join{a + b => ...}\nNo molecules" // this is the wrong result
    // when the problem is fixed, this test will have to be rewritten
  }

  it should "define a reaction with correct inputs with default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(& { case b(_) + a(None) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-simple default pattern-matching in the middle of reaction" in {
    val a = new M[Seq[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(& { case b(_) + a(List()) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "fail to define a simple reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(runSimple { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(& { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(& { case a(Some(x)) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "run reactions correctly with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")

    join(tp0, tp0)(& { case a(Some(x)) + b(_) => })

    a(Some(1))
    waitSome()
    a.logSoup shouldEqual "Join{a + b => ...}\nMolecules: a(Some(1))"
    b()
    waitSome()
    a.logSoup shouldEqual "Join{a + b => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at start of reaction" in {
    val a = new M[Int]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(& { case a(1) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant default option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(& { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "determine input patterns correctly" in {
    val a = new M[Option[Int]]("a")
    val b = new M[String]("b")
    val c = new M[(Int, Int)]("c")
    val d = new M[Unit]("d")

    val r = & { case a(Some(1)) + b("xyz") + d(()) + c((2, 3)) => a(Some(2)) }

    (r.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, OtherPattern(_)),
      InputMoleculeInfo(`b`, SimpleConst("xyz")),
      InputMoleculeInfo(`d`, SimpleConst(())),
      InputMoleculeInfo(`c`, OtherPattern(_))
      ) => true
      case _ => false
    }) shouldEqual true
    r.info.outputs shouldEqual List(a)

    // Note: Scala 2.11 and Scala 2.12 have different syntax trees for the same reaction in this case!
    val shaScala211 = "3A03F935B238FBC427CCEC83D25D69820AB5CDBE"
    val shaScala212 = "C4A42A1C5082B4C5023CC3B0497BB7BCA6642C17"
    Set(shaScala211, shaScala212) should contain oneElementOf List(r.info.sha1)
  }

  it should "fail to compile reactions with detectable compile-time errors" in {
    val a = b[Unit, Unit]
    val c = b[Unit, Unit]

    "& { case a() => }" shouldNot compile // no pattern variable for reply in "a"
    "& { case a(_) => }" shouldNot compile // no pattern variable for reply in "a"
    "& { case a(_, _) => }" shouldNot compile // no pattern variable for reply in "a"
    "& { case a(_, _, _) => }" shouldNot compile // no pattern variable for reply in "a"
    "& { case a(_, r) => }" shouldNot compile // no reply is performed with r
    "& { case a(_, r) + a(_) + c(_) => r()  }" shouldNot compile // invalid patterns for "a" and "c"
    "& { case a(_, r) + a(_) + c(_) => r() + r() }" shouldNot compile // two replies are performed with r, and invalid patterns for "a" and "c"

  }

  it should "fail to compile reactions with no input molecules" in {
    val bb = m[Int]
    val bbb = m[Int]

    "& { case _ => bb(0) }" shouldNot compile // empty list of input molecules
    "& { case x => bb(x.asInstanceOf[Int]) }" shouldNot compile
    "& { case x => x }" shouldNot compile
  }

  it should "fail to compile reactions with unconditional livelock" in {
    val bb = m[Int]
    val bbb = m[Int]
    val a = m[(Int, Int)]

    "& { case a((x,y)) => a((1,1)) }" should compile // cannot detect unconditional livelock here
    "& { case a((_,x)) => a((x,x)) }" should compile // cannot detect unconditional livelock here
    "& { case a((1,_)) => a((1,1)) }" should compile // cannot detect unconditional livelock here

    "val r = & { case a(_) => a((1,1)) }" shouldNot compile // unconditional livelock

    "& { case bbb(1) => bbb(2) }" should compile // no unconditional livelock
    "val r = & { case bbb(_) => bbb(0) }" shouldNot compile // unconditional livelock
    "& { case bbb(x) => bbb(x) + bb(x) }" shouldNot compile
    "& { case bbb(x) + bb(y) => bbb(x) + bb(x) + bb(y) }" shouldNot compile

  }

  it should "detect output molecules with constant values" in {
    val bb = m[Int]
    val bbb = m[Int]

    val r1 = & { case bbb(x) => bb(x) }
    val r2 = & { case bbb(_) + bb(_) => bbb(0) }
    val r3 = & { case bbb(x) + bb(_) + bb(_) => bbb(1) + bb(x) + bbb(2) }

    r1.info.outputs shouldEqual List(bb)
    r2.info.outputs shouldEqual List(bbb)
    r3.info.outputs shouldEqual List(bbb, bb, bbb)
  }

  it should "create partial functions for matching from reaction body" in {
    val aa = m[Option[Int]]
    val bb = m[(Int, Option[Int])]

    val result = & { case aa(Some(x)) + bb((0, None)) => aa(Some(x + 1)) }

    result.info.outputs shouldEqual List(aa)

    val pat_aa = result.info.inputs(0)
    pat_aa.molecule shouldEqual aa
    val pat_bb = result.info.inputs(1)
    pat_bb.molecule shouldEqual bb

    (pat_aa.flag match {
      case OtherPattern(matcher) =>
        matcher.isDefinedAt(Some(1)) shouldEqual true
        matcher.isDefinedAt(None) shouldEqual false
        true
      case _ => false
    }) shouldEqual true

    (pat_bb.flag match {
      case OtherPattern(matcher) =>
        matcher.isDefinedAt((0, None)) shouldEqual true
        matcher.isDefinedAt((1, None)) shouldEqual false
        matcher.isDefinedAt((0, Some(1))) shouldEqual false
        matcher.isDefinedAt((1, Some(1))) shouldEqual false
        true
      case _ => false
    }) shouldEqual true

  }


}

