package code.winitzki.jc

import JoinRun._
import Macros._
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt

class MacrosSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  val warmupTimeMs = 50

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  override def afterAll(): Unit = {
    defaultJoinPool.shutdownNow()
    defaultReactionPool.shutdownNow()
  }

  behavior of "macros for defining new molecule injectors"

  it should "compute invocation names for molecule injectors" in {
    val a = m[Int]

    a.toString shouldEqual "a"

    val s = b[Map[(Boolean, Unit), Seq[Int]], Option[List[(Int, Option[Map[Int, String]])]]] // complicated type

    s.toString shouldEqual "s/B"
  }

  behavior of "macros for inspecting a reaction body"

  it should "inspect reaction body containing a local molecule injector" in {
    val a = m[Int]

    val reaction =
      & { case a(x) =>
        val q = new M[Int]("q")
        val reaction1 = & { case q(_) => }
        q(0)
      }
    reaction.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar))
    reaction.info.outputs shouldEqual List()
  }

  it should "inspect reaction body with embedded join" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    join(
      & { case f(_, r) + bb(x) => r(x) },
      & { case a(x) =>
        val p = m[Int]
        join(& { case p(y) => bb(y) })
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
    join(
      runSimple { case f(_, r) + bb(x) => r(x) },
      runSimple { case a(x) =>
        val p = m[Int]
        join(& { case p(y) => bb(y) })
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

    val result = & { case a(x) => a(x + 1) }

    result.info shouldEqual ReactionInfo(List(InputMoleculeInfo(a, SimpleVar)), List(a), "4CD3BBD8E3B9DA58E46705320AE974479A7784E4")
  }

  object testWithApply {
    def apply(x: Int): Int = x + 1
  }

  it should "inspect a reaction body with another molecule and extra code" in {
    val a = m[Int]
    val qq = m[String]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    val result = & {
      case a(_) + a(x) =>
        a(x + 1)
        if (x > 0) a(testWithApply(123))
        println(x)
        qq("")
    }

    result.info.inputs shouldEqual List(InputMoleculeInfo(a, Wildcard), InputMoleculeInfo(a, SimpleVar))
    result.info.outputs shouldEqual List(a, a, qq)
  }

  it should "inspect reaction body with embedded reaction" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    val result = & { case a(x) => & { case qq(_) => a(0) }; a(x + 1) }

    result.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar))
    result.info.outputs shouldEqual List(a)
  }

  it should "inspect a very complicated reaction body" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    // reaction contains all kinds of pattern-matching constructions, blocking molecule in a guard, and unit values in molecules
    val result = & {
      case a(p) + a(y) + a(1) + bb(_) + bb((1, z)) + bb((_, None)) + bb((t, Some(q))) + s(_, r) if y > 0 && s() > 0 => a(p + 1); qq(); r(p)
    }

    result.info.inputs shouldEqual List(
      InputMoleculeInfo(a, SimpleVar),
      InputMoleculeInfo(a, SimpleVar),
      InputMoleculeInfo(a, SimpleConst),
      InputMoleculeInfo(bb, Wildcard),
      InputMoleculeInfo(bb, OtherPattern),
      InputMoleculeInfo(bb, OtherPattern),
      InputMoleculeInfo(bb, OtherPattern),
      InputMoleculeInfo(s, Wildcard)
    )
    result.info.outputs shouldEqual List(s, a, qq)
  }

  it should "inspect reaction body with two cases" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = & {
      case a(x) => a(x + 1)
      case qq(_) + a(y) => qq()
    }
    // TODO: add a test for inspecting this reaction
  }

  it should "define a reaction with correct inputs with non-default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(runSimple { case b(_) + a(Some(x)) + c(_) => })

    a.logSoup shouldEqual "Join{a + b => ...}\nNo molecules" // this is the wrong result
    // when the problem is fixed, this test will have to be rewritten
  }

  it should "define a reaction with correct inputs with default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(& { case b(_) + a(None) + c(_) => })

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

    join(runSimple { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(& { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(& { case a(Some(x)) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "run reactions correctly with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")

    join(& { case a(Some(x)) + b(_) => })

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

    join(& { case a(1) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant default option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(& { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

}

