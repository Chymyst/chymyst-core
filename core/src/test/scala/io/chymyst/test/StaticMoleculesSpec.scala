package io.chymyst.test

import io.chymyst.jc._
import io.chymyst.test.Common._
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.duration._
import scala.language.postfixOps

class StaticMoleculesSpec extends LogSpec with BeforeAndAfterEach {

  var tp: Pool = _

  override def beforeEach(): Unit = tp = FixedPool(3)

  override def afterEach(): Unit = tp.shutdownNow()

  behavior of "static molecule emission"

  it should "refuse to emit a static molecule from user code" in {

    val f = b[Unit, String]
    val d = m[String]

    val tp1 = FixedPool(1) // This test works only with single threads.

    site(tp1)(
      go { case f(_, r) + d(text) => r(text); d(text) },
      go { case _ => d("ok") } // static reaction
    )

    repeat(200, { i =>
      val thrown = intercept[Exception] {
        d(s"bad $i") // this "d" should not be emitted, even though "d" is sometimes not in the soup due to reactions!
      }
      thrown.getMessage shouldEqual s"Error: static molecule d(bad $i) cannot be emitted non-statically"
      f.timeout()(500 millis) shouldEqual Some("ok")
    })

    tp1.shutdownNow()
  }

  it should "refuse to emit a static molecule immediately after reaction site" in {

    val tp1 = FixedPool(1) // This test works only with single threads.

    repeat(20, { i =>
      val f = b[Unit, String]
      val d = m[String]

      site(tp1)(
        go { case f(_, r) + d(text) => r(text); d(text) },
        go { case _ => d("ok") } // static reaction
      )

      // Warning: the timeouts might fail the test due to timed tests.
      repeat(20, { j =>
        val thrown = intercept[Exception] {
          d(s"bad $i $j") // this "d" should not be emitted, even though we are immediately after a reaction site,
          // and even if the initial d() emission was done late
        }
        thrown.getMessage shouldEqual s"Error: static molecule d(bad $i $j) cannot be emitted non-statically"
        f.timeout()(500 millis) shouldEqual Some("ok")
      })
    })

    tp1.shutdownNow()
  }

  it should "signal error when a static molecule is consumed by reaction but not emitted" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      site(tp)(
        go { case c(_, r) + d(_) => r("ok") },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d → ...}: Incorrect static molecule usage: static molecule (d) consumed but not emitted by reaction {c/B(_) + d(_) → }"
  }

  it should "signal error when a static molecule is consumed by reaction and emitted twice" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      site(tp)(
        go { case c(_, r) + d(_) => r("ok"); d() + d() },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d → ...}: Incorrect static molecule usage: static molecule (d) emitted more than once by reaction {c/B(_) + d(_) → d() + d()}"
  }

  it should "signal error when a static molecule is the sole input of a reaction" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]
      val e = m[Unit]

      site(tp)(
        go { case c(_, r) => r("ok"); d() },
        go { case d(_) => e() },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B → ...; d → ...}: Incorrect static molecule usage: static molecule (d) emitted but not consumed by reaction {c/B(_) → d()}; Incorrect static molecule usage: static molecule (d) consumed but not emitted by reaction {d(_) → e()}; reaction {d(_) → e()} has only static input molecules (d)"
  }

  it should "signal error when two static molecules are the sole input of a reaction" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]
      val e = m[Unit]

      site(tp)(
        go { case c(_, r) => r("ok"); d() },
        go { case d(_) + e(_) => e() },
        go { case _ => d() + e() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B → ...; d + e → ...}: Incorrect static molecule usage: static molecule (d) emitted but not consumed by reaction {c/B(_) → d()}; Incorrect static molecule usage: static molecule (d) consumed but not emitted by reaction {d(_) + e(_) → e()}; reaction {d(_) + e(_) → e()} has only static input molecules (d,e)"
  }

  it should "signal error when a static molecule is emitted but not consumed by reaction" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]
      val e = m[Unit]

      site(tp)(
        go { case c(_, r) => r("ok"); d() },
        go { case e(_) => d() },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B → ...; e → ...}: Incorrect static molecule usage: static molecule (d) emitted but not consumed by reaction {c/B(_) → d()}; static molecule (d) emitted but not consumed by reaction {e(_) → d()}; Incorrect static molecule usage: static molecule (d) not consumed by any reactions"
  }

  it should "signal error when a static molecule is emitted by reaction inside a loop to trick static analysis" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, Unit]
      val d = m[Unit]

      site(tp)(
        go { case c(_, r) + d(_) => (1 to 2).foreach(_ => d()); r() },
        go { case _ => d() } // static reaction
      )
      c()
    }
    thrown.getMessage shouldEqual "In Site{c/B + d → ...}: Incorrect static molecule usage: static molecule (d) consumed but not guaranteed to be emitted by reaction {c/B(_) + d(_) → d()}"
  }

  it should "signal error when a static molecule is emitted by another reaction to trick static analysis" in {
    val a = m[Unit]
    val c = b[Unit, Unit]
    val d = m[Unit]
    val carrier = m[M[Unit]]

    val memLog = new MemoryLogger
    tp.reporter = new ErrorReporter(memLog)
    site(tp)(
      go { case c(_, r) + carrier(q) ⇒ q(); r() },
      go { case a(_) + d(_) => d() + carrier(d) },
      go { case _ => d() } // static reaction
    )
    a()
    c.timeout()(300.millis) shouldEqual None
    globalLogHas(memLog, "cannot be emitted non-statically", "In Site{a + d → ...; c/B + carrier → ...}: Reaction {c/B(_) + carrier(q) → } with inputs [c/BP() + carrier/P(d)] produced an exception internal to Chymyst Core. Retry run was not scheduled. Message: Error: static molecule d(()) cannot be emitted non-statically")
  }

  it should "signal error when a static molecule is emitted twice by another reaction to trick static analysis" in {
    val memLog = new MemoryLogger
    tp.reporter = new ErrorReporter(memLog)
    val a = m[Unit]
    val c = b[Unit, Unit]
    val d = m[Unit]
    val carrier = m[M[Unit]]

    site(tp)(
      go { case c(_, r) + carrier(q) + d(_) ⇒ q(); d(); r() },
      go { case a(_) + d(_) => d() + carrier(d) },
      go { case _ => d() } // static reaction
    )
    a()
    c.timeout()(600.millis) shouldEqual None
    globalLogHas(memLog, "cannot be emitted non-statically", "In Site{a + d → ...; c/B + carrier + d → ...}: Reaction {c/B(_) + carrier(q) + d(_) → d()} with inputs [c/BP() + carrier/P(d) + d/P()] produced an exception internal to Chymyst Core. Retry run was not scheduled. Message: Error: static molecule d(()) cannot be emitted non-statically")
  }

  it should "signal error when a static molecule is emitted inside an if-then block" in {
    val c = b[Unit, Unit]
    val d = m[Unit]
    the[Exception] thrownBy {
      site(tp)(
        go { case c(_, r) + d(_) => if (1 == 1) d(); r() },
        go { case _ => d() } // static reaction
      )
      c()
    } should have message "In Site{c/B + d → ...}: Incorrect static molecule usage: static molecule (d) consumed but not guaranteed to be emitted by reaction {c/B(_) + d(_) → d()}"
  }

  it should "find no error when a static molecule is emitted inside an if-then block with perfect shrinkage" in {
    val c = b[Int, Int]
    val d = m[Unit]
    site(tp)(
      go { case c(x, r) + d(_) => if (x == 1) {
        d()
        r(0)
      } else {
        d()
        r(1)
      }
      },
      go { case _ => d() } // static reaction
    )
    c(0) shouldEqual 1
    c(1) shouldEqual 0
  }

  it should "signal error when a static molecule is consumed multiple times by reaction" in {
    val thrown = intercept[Exception] {
      val d = m[Unit]
      val e = m[Unit]

      site(tp)(
        go { case e(_) + d(_) + d(_) => d() },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{d + d + e → ...}: Incorrect static molecule usage: static molecule (d) consumed 2 times by reaction {d(_) + d(_) + e(_) → d()}"
  }

  it should "signal error when a static molecule is emitted but has no reactions" in {
    val thrown = intercept[Exception] {
      val d = m[Unit]

      site(tp)(
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{}: Incorrect static molecule usage: static molecule (d) not consumed by any reactions"
  }

  it should "signal error when a static molecule is emitted but bound to another reaction site" in {
    val thrown = intercept[Exception] {
      val d = m[Unit]

      site(tp)(
        go { case d(_) => }
      )

      site(tp)(
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{}: Incorrect static molecule usage: static molecule (d) not consumed by any reactions"
  }

  it should "signal error when a static molecule is emitted but incorrectly bound to another reaction site" in {
    val thrown = intercept[Exception] {
      val d = m[Unit]

      site(tp)(
        go { case d(_) => }
      )

      site(tp)(
        go { case d(_) => },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "Molecule d cannot be used as input in Site{d → ...} since it is already bound to Site{d → ...}"
  }

  it should "signal error when a static molecule is emitted but not bound to this reaction site" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      site(tp)(
        go { case d(_) => }
      )

      site(tp)(
        go { case c(_, r) => r("ok"); d() },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B → ...}: Incorrect static molecule usage: static molecule (d) emitted but not consumed by reaction {c/B(_) → d()}; Incorrect static molecule usage: static molecule (d) not consumed by any reactions"
  }

  it should "signal error when a static molecule is defined by a static reaction with guard" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      val n = 1

      site(tp)(
        go { case c(_, r) + d(_) => r("ok"); d() },
        go { case _ if n > 0 => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d → ...}: Static reaction {_ if(?) → d()} should not have a guard condition"
  }

  it should "refuse to define a blocking molecule as a static molecule" in {
    val c = m[Int]
    val d = m[Int]
    val f = b[Unit, Unit]

    val thrown = intercept[Exception] {
      site(tp)(
        go { case f(_, r) => r() },
        go { case c(x) + d(_) => d(x) },
        go { case _ => f(); d(0) }
      )
    }

    thrown.getMessage shouldEqual "In Site{c + d → ...; f/B → ...}: Molecule f/B() cannot be emitted initially as static (must be a non-blocking molecule)"
  }

  behavior of "volatile reader"

  it should "refuse to read the value of a molecule not bound to a reaction site" in {
    val cc = m[Int]

    val thrown = intercept[Exception] {
      cc.volatileValue shouldEqual null.asInstanceOf[Int] // If this passes, we are not detecting the fact that cc is not bound.
    }

    thrown.getMessage shouldEqual "Molecule cc is not bound to any reaction site, cannot read volatile value"
  }

  it should "refuse to read the value of a non-static molecule" in {
    val c = m[Int]
    site(tp)(go { case c(_) => })

    val thrown = intercept[Exception] {
      c.volatileValue
    }

    thrown.getMessage shouldEqual "In Site{c → ...}: volatile reader requested for non-static molecule (c)"
  }

  it should "always be able to read the value of a static molecule early" in {
    def makeNewVolatile(i: Int) = {
      val c = m[Int]
      val d = m[Int]

      site(tp)(
        go { case c(x) + d(_) => d(x) },
        go { case _ => d(i) }
      )

      d.volatileValue
    }

    repeat(100, { i =>
      makeNewVolatile(i) // This should sometimes throw an exception, so let's make sure it does.
    })
  }

  it should "report that the value of a static molecule is ready even if called early" in {
    def makeNewVolatile(i: Int): Int = {
      val c = m[Int]
      val d = m[Int]

      site(tp)(
        go { case c(x) + d(_) => d(x) },
        go { case _ => d(i) }
      )

      if (d.volatileValue > 0) 0 else 1
    }

    val result = (1 to 100).map { i =>
      makeNewVolatile(i)
    }.sum // how many times we failed

    println(s"Volatile value was not ready $result times")
    result shouldEqual 0
  }

  it should "handle static molecules with conditions" in {
    val d123 = m[Short]
    val c = m[Short]
    val g = b[Unit, Unit]
    val (e, f) = litmus[Short](tp)
    val memLog = new MemoryLogger
    tp.reporter = new ErrorReporter(memLog)
    site(tp)(
      go { case _ => d123(1) },
      go { case d123(x) + g(_, r) if x < 10 => d123(123) + r() },
      go { case d123(x) + c(_) if x < 10 => d123(x) + e(1) }
    )
    checkExpectedPipelined(Map(c -> true, d123 -> true, e -> true, f -> true)) shouldEqual ""
    g() // now we have attempted to emit d123(123) but we should have failed
    c(0)
    f.timeout()(1.second) shouldEqual None // if this is Some(1), reaction ran, which means the test failed
    globalLogHas(memLog, "d123(123)", "In Site{c + d123 → ...; d123 + g/B → ...}: Refusing to emit static pipelined molecule d123(123) since its value fails the relevant conditions")
  }

  it should "handle static molecules with cross-molecule guards" in {
    repeat(100) {
      val d = m[Short]
      val c = m[Short]
      val e = m[Unit]
      val f = b[Unit, Unit]
      val stabilize_d = b[Unit, Unit]
      site(tp)(
        go { case e(_) + f(_, r) => r() },
        go { case c(x) + d(y) if x > y => d((x + y).toShort) + e() },
        go { case d(x) + stabilize_d(_, r) if x > 0 => r(); d(x) }, // Await stabilizing the presence of d
        go { case _ => d(123) } // static reaction
      )
      d.isPipelined shouldEqual false // Since `d()` is not pipelined and has `Short` type, its values will be kept in a hashmap.
      // This will exercise the hashmap functions of MolValueBag.
      d.volatileValue shouldEqual 123
      c(1)
      stabilize_d()
      d.volatileValue shouldEqual 123
      c(100)
      stabilize_d()
      d.volatileValue shouldEqual 123
      c(200) // now eventually e() will be emitted, which we wait for
      f()
      d.volatileValue shouldEqual 323
    }
  }

  it should "read the initial value of the static molecule after stabilization" in {
    val d = m[Int]
    val stabilize_d = b[Unit, Unit]
    site(tp)(
      go { case d(x) + stabilize_d(_, r) => r(); d(x) }, // Await stabilizing the presence of d
      go { case _ => d(123) } // static reaction
    )
    stabilize_d()
    d.volatileValue shouldEqual 123
    d.isPipelined shouldEqual true
  }

  it should "read the volatile value of the static molecule always accurately after many changes" in {
    val d = m[Int]
    val incr = b[Unit, Unit]
    val stabilize_d = b[Unit, Unit]
    val n = 1
    val delta_n = 1000

    site(tp)(
      go { case d(x) + incr(_, r) => d(x + 1) + r() },
      go { case d(x) + stabilize_d(_, r) => d(x); r() }, // Await stabilizing the presence of d
      go { case _ => d(n) } // static reaction
    )
    stabilize_d.timeout()(500 millis)
    d.volatileValue shouldEqual n

    (n + 1 to n + delta_n).map { i =>
      incr.timeout()(500 millis) shouldEqual Some(())

      i - d.volatileValue // this should be always 0 unless we have a race condition and the volatile value is assigned late
    }.sum shouldEqual 0 // there should be some cases when d.value reads the previous value
  }

  it should "keep the previous value of the static molecule while update reaction is running" in {
    val d = m[Int]
    val e = m[Unit]
    val wait = b[Unit, Unit]
    val incr = b[Unit, Unit]
    val stabilize_d = b[Unit, Unit]

    val tp1 = FixedPool(1)
    val tp3 = BlockingPool(5)

    site(tp3)(
      go { case wait(_, r) + e(_) => r() } onThreads tp3,
      go { case d(x) + incr(_, r) => r(); wait(); d(x + 1) } onThreads tp1,
      go { case d(x) + stabilize_d(_, r) => d(x); r() } onThreads tp1, // Await stabilizing the presence of d
      go { case _ => d(100) } // static reaction
    )
    stabilize_d.timeout()(500 millis) shouldEqual Some(())
    d.volatileValue shouldEqual 100
    incr.timeout()(500 millis) shouldEqual Some(()) // update started and is waiting for e()
    d.volatileValue shouldEqual 100 // We don't have d() present in the soup, but we can read its previous value.
    e()
    stabilize_d.timeout()(500 millis) shouldEqual Some(())
    d.volatileValue shouldEqual 101

    tp1.shutdownNow()
    tp3.shutdownNow()
  }

  it should "signal error when a static molecule is emitted fewer times than declared" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]
      val e = m[Unit]
      val f = m[Unit]

      site(tp)(
        go { case d(_) + e(_) + f(_) + c(_, r) => r("ok"); d(); e(); f() },
        go { case _ => if (false) {
          d()
          e()
        }
          f()
        } // static molecules d() and e() will actually not be emitted because of a condition
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d + e + f → ...}: Too few static molecules emitted: d emitted 0 times instead of 1, e emitted 0 times instead of 1"
  }

  it should "signal no error (but a warning) when a static molecule is emitted more times than declared" in {
    val c = b[Unit, String]
    val d = m[Unit]
    val e = m[Unit]
    val f = m[Unit]

    val warnings = site(tp)(
      go { case d(_) + e(_) + f(_) + c(_, r) => r("ok"); d(); e(); f() },
      go { case _ => (1 to 2).foreach { _ => d(); e() }; f(); } // static molecules d() and e() will actually be emitted more times
    )
    warnings.errors shouldEqual Seq()
    warnings.warnings shouldEqual Seq("Possibly too many static molecules emitted: d emitted 2 times instead of 1, e emitted 2 times instead of 1")
  }

  it should "detect livelock with static molecules" in {
    val a = m[Unit]
    val c = m[Int]
    val thrown = intercept[Exception] {
      site(tp)(
        go { case a(_) + c(x) if x > 0 => c(1) + a() },
        go { case _ => c(0) }
      )
    }
    thrown.getMessage shouldEqual "In Site{a + c → ...}: Unavoidable livelock: reaction {a(_) + c(x if ?) → c(1) + a()}"
  }

}
