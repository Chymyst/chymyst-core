package io.chymyst.jc

import io.chymyst.test.Common._
import io.chymyst.test.LogSpec
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Waiters.{PatienceConfig, Waiter}
import org.scalatest.time.{Millis, Span}

import scala.concurrent.duration._
import scala.language.postfixOps

class MoleculesSpec extends LogSpec with BeforeAndAfterEach {

  var tp0: Pool = _

  var memLog: MemoryLogger = _

  implicit val patienceConfig = PatienceConfig(timeout = Span(1000, Millis))

  override def beforeEach(): Unit = {
    memLog = new MemoryLogger
    tp0 = FixedPool(4).withReporter(new ErrorReporter(memLog))
  }

  override def afterEach(): Unit = {
    tp0.shutdownNow()
  }

  val warmupTimeMs = 50L

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "reaction site"

  it should "define a reaction with correct inputs" in {
    val a = m[Unit]
    val b = m[Unit]
    val c = m[Unit]

    site(tp0)(go { case a(_) + b(_) + c(_) => })
    a.logSite shouldEqual "Site{a + b + c → ...}\nNo molecules"
    a.typeSymbol shouldEqual 'Unit
  }

  it should "define a reaction using pool syntax" in {
    val a = m[Unit]
    val b = m[Unit]
    val c = m[Unit]

    tp0(go { case a(_) + b(_) + c(_) => })
    a.logSite shouldEqual "Site{a + b + c → ...}\nNo molecules"
    a.typeSymbol shouldEqual 'Unit
  }

  it should "correctly list molecules present in soup" in {
    val a = m[Unit]
    val bb = m[Unit]
    val c = m[Unit]
    val d = m[Unit]
    val f = b[Unit, Unit]
    val g = b[Unit, Unit]
    val h = m[List[(Int, String)]]

    f.typeSymbol shouldEqual null
    f.siteIndex shouldEqual -1
    site(tp0)(
      go { case g(_, r) + d(_) + h(_) ⇒ r() },
      go { case a(_) + bb(_) + c(_) + f(_, r) ⇒ r() }
    )
    val molecules = Seq(a, bb, c, d, f, g)
    molecules.map(_.siteIndex) shouldEqual molecules.indices
    molecules.map(_.typeSymbol) shouldEqual Seq.fill(molecules.size)('Unit)

    val siteString = "Site{a + bb + c + f/B → ...; d + g/B + h → ...}"
    
    a.logSite shouldEqual s"$siteString\nNo molecules"

    h.typeSymbol.name shouldEqual "List[(Int, String)]"
    
    a.apply()
    a()
    bb()
    Thread.sleep(300)
    a.logSite shouldEqual s"$siteString\nMolecules: a/P() * 2 + bb/P()"
    d()
    h(Nil)
    g.timeout()(1.second) shouldEqual Some(())
    a.logSite shouldEqual s"$siteString\nMolecules: a/P() * 2 + bb/P()"
    c()
    f.timeout()(1.second) shouldEqual Some(())
    a.logSite shouldEqual s"$siteString\nMolecules: a/P()"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at end of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    site(go { case b(_) + c(_) + a(Some(_)) ⇒ })

    a.logSite shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with zero default pattern-matching at start of reaction" in {
    val a = new M[Int]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    site(go { case a(0) + b(_) + c(_) => })

    a.logSite shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at end of reaction" in {
    val a = new M[Int]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    site(go { case b(_) + c(_) + a(1) => })

    a.logSite shouldEqual "Site{a + b + c → ...}\nNo molecules"
  }

  it should "start a simple reaction with one input, defining the emitter explicitly" in {

    val waiter = new Waiter

    val a = new M[Unit]("a")
    site(tp0)(go { case a(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction with one input" in {

    val waiter = new Waiter

    val a = new M[Unit]("a")
    site(tp0)(go { case a(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction chain" in {

    val waiter = new Waiter

    val a = new M[Unit]("a")
    val b = new M[Unit]("b")
    site(tp0)(
      go { case a(_) => b() },
      go { case b(_) => waiter.dismiss() }
    )
    a()
    waiter.await()
  }

  it should "start a simple reaction chain with two inputs with values" in {

    val waiter = new Waiter

    val a = new M[Int]("a")
    val b = new M[Int]("b")
    val c = new M[Int]("c")
    site(tp0)(go { case a(x) + b(y) => c(x + y) }, go { case c(z) => waiter {
      z shouldEqual 3
      ()
    }
      waiter.dismiss()
    })
    a(1)
    b(2)
    waiter.await()
  }

  it should "accept nonlinear input patterns" in {
    val a = new M[Unit]("a")
    site(go { case a(_) + a(_) => () })
  }

  it should "accept nonlinear input patterns, with blocking molecule" in {
    val a = new B[Unit, Unit]("a")
    site(go { case a(_, r) + a(_, s) => r(); s() })
  }

  it should "throw exception when input pattern attempts to redefine a blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = new B[Unit, Unit]("a")
      site(go { case a(_, r) => r() })
      site(go { case a(_, r) => r() })
    }
    thrown.getMessage shouldEqual "Molecule a/B cannot be used as input in Site{a/B → ...} since it is already bound to Site{a/B → ...}"
  }

  it should "throw exception when input pattern attempts to redefine a non-blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = new M[Unit]("x")
      val b = new M[Unit]("y")
      site(go { case a(_) + b(_) => () })
      site(go { case a(_) => () })
    }
    thrown.getMessage shouldEqual "Molecule x cannot be used as input in Site{x → ...} since it is already bound to Site{x + y → ...}"
  }

  it should "throw exception when trying to emit a blocking molecule that has no reaction site" in {
    val thrown = intercept[Exception] {
      val a = new B[Unit, Unit]("x")
      a()
    }
    thrown.getMessage shouldEqual "Molecule x/B is not bound to any reaction site, cannot emit"
  }

  it should "throw exception when trying to emit a non-blocking molecule that has no reaction site" in {
    val a = new M[Unit]("x")
    the[Exception] thrownBy {
      a()
    } should have message "Molecule x is not bound to any reaction site, cannot emit"
  }

  it should "throw exception when trying to log soup of a blocking molecule that has no reaction site" in {
    val thrown = intercept[Exception] {
      val a = new B[Unit, Unit]("x")
      a.logSite
    }
    thrown.getMessage shouldEqual "Molecule x/B is not bound to any reaction site, cannot emit"
  }

  it should "throw exception when trying to log soup a non-blocking molecule that has no reaction site" in {
    val thrown = intercept[Exception] {
      val a = new M[Unit]("x")
      a.logSite
    }
    thrown.getMessage shouldEqual "Molecule x is not bound to any reaction site, cannot emit"
  }

  it should "fail to start reactions when pattern is not matched" in {

    val a = new M[Int]("a")
    val b = new M[Int]("b")
    val f = new B[Unit, Int]("f")

    site(tp0)(go { case a(x) + b(0) => a(x + 1) }, go { case a(z) + f(_, r) => r(z) })
    a(1)
    b(2)
    f() shouldEqual 1
  }

  it should "fail to start reactions when unbound molecule is emitted by reactions" in {

    val a = m[Int]
    val b = m[Int]

    site(tp0)(go { case a(x) => b(x) })
    val thrown = intercept[Exception] {
      a(1)
    }
    thrown.getMessage shouldEqual "In Site{a → ...}: As a(1) is emitted, some reactions may emit molecules (b) that are not bound to any reaction site"
  }

  it should "fail to start reactions when several unbound molecules are emitted by reactions" in {
    val a = m[Int]
    val a1 = m[Int]
    val a2 = m[Int]
    val b = m[Int]
    val c = m[Unit]

    site(tp0)(go { case a(_) => }, go { case a1(_) => c() + c() }, go { case a2(x) => c() + b(x) })
    val thrown = intercept[Exception] {
      a(1) // This molecule will not actually start any reactions that would emit unbound molecules.
      // Nevertheless, the error must be flagged.
    }
    thrown.getMessage shouldEqual "In Site{a → ...; a1 → ...; a2 → ...}: As a(1) is emitted, some reactions may emit molecules (b, c) that are not bound to any reaction site"
  }

  it should "start reactions when molecule emitters are passed on input molecules" in {
    (1 to 100).foreach { _ =>
      val a = m[M[Int]]
      val c = m[Int]
      val f = b[Unit, Int]
      site(tp0)(
        go { case a(s) => s(123) }
      )
      site(tp0)(
        go { case c(x) + f(_, r) => r(x) }
      )
      a(c)
      f() shouldEqual 123
    }
  }

  it should "start reactions when molecule emitters are passed on input molecules slightly before they are bound" in {
    val total = 100
    (1 to total).foreach { i =>
      val p = m[M[Int]]
      val c = m[Int]
      site(tp0)(
        go { case p(s) => if (i % 5 == 0) Thread.sleep(150); s(123) } // Some reactions will start later.
      )
      p(c) // The reaction above will sometimes emit c() even though no reaction for c() is defined yet.
      if (i % 6 == 0) Thread.sleep(150)
      site(tp0)(
        go { case c(_) ⇒ }
      )
    }
    val errors = memLog.messages.count(_ contains "Molecule c is not bound to any reaction site, cannot emit")

    println(s"unbound molecule exceptions, test 1: $errors errors out of $total runs")
    errors should be > 0
    errors should be < total
    globalLogHas(memLog, "xception", "In Site{p → ...}: Reaction {p(s) → } with inputs [p/P(c)] produced an exception internal to Chymyst Core. Retry run was not scheduled. Message: Molecule c")
    // Error message could be either that molecule c() is not bound, or that the reaction site is not active.
  }

  // This test verifies that unbound molecule emitters will cause an exception when used in a nested reaction site.
  // The way to avoid this problem is to define nested reaction sites *before* the new emitters are used.
  // This test intentionally defines the reaction site defining the {e -> } reaction *after* the `e` emitter is passed to the `a` reaction.
  it should "start reactions and throw exception when molecule emitters are passed to nested reactions slightly before they are bound" in {
    val tp1 = FixedPool(2)
    val memLog1 = new MemoryLogger
    tp1.reporter = new ErrorReporter(memLog1)
    val total = 300
    (1 to total).foreach { i =>
      val a = m[M[Int]]
      site(tp1)(
        go { case a(s) => if (i % 25 == 0) Thread.sleep(100); s(123) }
      )

      val begin = m[Unit]
      site(tp0)(
        go { case begin(_) =>
          val e = m[Int]
          a(e) // The reaction for `a` will emit `e(123)`, unless it crashes due to `e` being unbound.
          if (i % 6 == 0) Thread.sleep(2L * i)
          site(tp0)(
            go { case e(_) ⇒ }
          )

        }
      )
      begin()
    }
    Thread.sleep(1000)
    tp1.shutdownNow()

    val errors = memLog1.messages.count(_ contains "Molecule e is not bound to any reaction site, cannot emit")
    // Sometimes (on fast machines) the reaction always produces an exception, in which case the result is `total` exceptions.
    // Since this is expected and correct behavior, we should not fail the test when the reaction starts too fast to avoid the exception.
    println(s"unbound molecule exceptions, test 2: $errors errors out of $total runs")
    globalLogHas(memLog1, "xception", "In Site{a → ...}: Reaction {a(s) → } with inputs [a/P(e)] produced an exception internal to Chymyst Core. Retry run was not scheduled. Message: Molecule e") 
    // Possible errors here are: "Molecule e is not bound to any reaction site, cannot emit" and "Molecule e(123) cannot be emitted because reaction site is inactive".
    errors should be > 0
    errors should be < total
  }

  it should "start reactions without errors when molecule emitters are passed to nested reactions after they are bound" in {
    val results = (1 to 100).map { _ =>
      val q = m[M[Int]]
      val p = m[Int]
      val f = b[Unit, Int]
      site(tp0)(
        go { case q(s) => s(123) },
        go { case p(x) + f(_, r) => r(x) }
      )
      val begin = m[Unit]
      site(tp0)(
        go { case begin(_) =>
          val x = 123
          val e = m[Int]
          site(tp0)(
            go { case e(y) => p(x + y) }
          )
          q(e) // The reaction for `a` will emit `e(123)`, unless it crashes due to `e` being unbound.
        }
      )
      begin()
      f()
    }
    println(s"results for test 3: ${results.groupBy(identity).mapValues(_.size)}")
    memLog.messages.exists(_ contains "In Site{q → ...}: Reaction {q(s) → } with inputs [q/P(e)] produced an exception internal to Chymyst Core. Retry run was not scheduled. Message: Molecule e is not bound to any reaction site, cannot emit") should be(false)
    memLog.messages.count(_ contains "Molecule e is not bound to any reaction site, cannot emit") shouldEqual 0
    results should contain(246)
    results should not contain 0
  }

  it should "start reaction but throw exception when unbound molecule emitter is passed on input molecule" in {
    val a = m[M[Int]]
    val c = m[Int]
    val f = b[Unit, Int]
    site(tp0)(
      go { case a(s) + f(_, r) => s(123); r(123) }
    )
    // `c` is unbound, emitting `a(c)` will cause the reaction to fail.
    a(c)
    f.timeout()(300.millis) shouldEqual None // This should not pass.
    globalLogHas(memLog, "finished without replying", "In Site{a + f/B → ...}: Reaction {a(s) + f/B(_) → } with inputs [a/P(c) + f/BP()] finished without replying to f/B. Reported error: In Site{a + f/B → ...}: Reaction {a(s) + f/B(_) → } with inputs [a/P(c) + f/BP()] produced an exception internal to Chymyst Core. Retry run was not scheduled. Message: Molecule c is not bound to any reaction site, cannot emit")
  }

  behavior of "basic functionality"

  it should "implement the non-blocking single-access counter" in {
    val c = new M[Int]("c")
    val d = new M[Unit]("decrement")
    val g = new B[Unit, Int]("getValue")
    site(tp0)(
      go { case c(n) + d(_) => c(n - 1) },
      go { case c(0) + g(_, r) => r(0) }
    )
    c(2) + d() + d()
    g.timeout()(1.second) shouldEqual Some(0)
  }

  it should "use only one thread for concurrent computations" in {
    val c = new M[Int]("counter")
    val d = new M[Unit]("decrement")
    val f = new M[Unit]("finished")
    val a = new M[Int]("all_finished")
    val g = new B[Unit, Int]("getValue")

    val tp = FixedPool(1)

    site(tp0)(
      go { case c(x) + d(_) => Thread.sleep(300); c(x - 1) + f() } onThreads tp,
      go { case a(x) + g(_, r) => a(x); r(x) },
      go { case f(_) + a(x) => a(x + 1) }
    )
    a(0) + c(1) + c(1) + d() + d()
    g.timeout()(1.second) shouldEqual Some(0)
    Thread.sleep(150) // This is less than 300ms, so we have not yet finished the first computation.
    g.timeout()(1.second) shouldEqual Some(0)
    Thread.sleep(300) // Now we should have finished the first computation.
    g.timeout()(1.second) shouldEqual Some(1)
    Thread.sleep(300) // Now we should have finished the second computation.
    g.timeout()(1.second) shouldEqual Some(2)

    tp.shutdownNow()
  }

  it should "use two threads for concurrent computations" in {
    val c = m[Long]
    val d = m[Long]
    val s = m[Unit]
    val f = b[Unit, Long]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case c(x) ⇒ s(); Thread.sleep(x); d(x) },
        go { case s(_) ⇒ d(0) },
        go { case d(x) + f(_, r) ⇒ r(x) }
      )
      c(10000L)
      f()
    }.get shouldEqual 0
  }

  it should "disallow logSite() on reaction threads" in {
    val c = m[String]
    val g = b[Unit, String]

    site(tp0)(
      go { case c(_) + g(_, r) ⇒ r(c.logSite) }
    )
    c("xyz")

    val result = g.timeout()(1500 millis)
    result shouldEqual Some("<logSite is disabled on reaction threads!>")
  }

  behavior of "fault-tolerant resume facility"

  // This will certainly return `true` for x = 5 the first time it encounters x = 5.
  // For other values of x, it will return `true` with given probability.
  class CrashChooser(probabilityOfCrash: Double) {
    var alreadyCrashed = true

    def apply(x: Int): Boolean = x == 5 && alreadyCrashed && {
      alreadyCrashed = false
      true
    } || scala.util.Random.nextDouble < probabilityOfCrash
  }

  it should "fail to finish job if 1 out of 2 processes crash while retry is not set" in {
    val n = 20

    val chooser = new CrashChooser(probabilityOfCrash = 0.5)

    val c = new M[Int]("counter")
    val d = new M[Unit]("decrement")
    val g = new B[Unit, Unit]("getValue")
    val tp = FixedPool(2)

    site(tp0)(
      go { case c(x) + d(_) =>
        if (chooser(x)) throw new Exception("crash! (it's OK, ignore this)")
        c(x - 1)
      }.noRetry onThreads tp,
      go { case c(0) + g(_, r) => r() }
    )
    c(n)
    (1 to n).foreach { _ => d() }

    val result = g.timeout()(1500 millis)
    tp.shutdownNow()
    result shouldEqual None
  }

  it should "finish job by retrying reactions even if 1 out of 2 processes crash" in {
    val n = 20

    val chooser = new CrashChooser(probabilityOfCrash = 0.5)

    val c = new M[Int]("counter")
    val d = new M[Unit]("decrement")
    val g = new B[Unit, Unit]("getValue")
    val tp = FixedPool(2)

    site(tp0)(
      go { case c(x) + d(_) =>
        if (chooser(x)) throw new Exception("crash! (it's OK, ignore this)")
        c(x - 1)
      }.withRetry onThreads tp,
      go { case c(0) + g(_, r) => r() }
    )
    c(n)
    (1 to n).foreach { _ => d() }

    val result = g.timeout()(1500 millis)
    tp.shutdownNow()
    result shouldEqual Some(())
    logShouldHave(memLog, "In Site{counter + decrement → .../R; counter + getValue/B → ...}: Reaction {counter(x) + decrement(_) → counter(?)} with inputs [counter(5) + decrement/P()] produced Exception. Retry run was scheduled. Message: crash! (it's OK, ignore this)")
  }

  it should "finish job by retrying reactions with static molecules even if 1 out of 2 processes crash" in {
    val n = 20

    val chooser = new CrashChooser(probabilityOfCrash = 0.5)

    val c = new M[Int]("counter")
    val d = new M[Unit]("decrement")
    val g = new B[Unit, Unit]("getValue")
    val tp = FixedPool(2)

    site(tp0)(
      go { case c(x) + d(_) =>
        if (chooser(x)) throw new Exception("crash! (it's OK, ignore this)")
        c(x - 1)
      }.withRetry onThreads tp,
      go { case c(0) + g(_, r) => r(); c(0) },
      go { case _ => c(n) }
    )
    (1 to n).foreach { _ => d() }

    val result = g.timeout()(1500 millis)
    tp.shutdownNow()
    result shouldEqual Some(())
    logShouldHave(memLog, "In Site{counter + decrement → .../R; counter + getValue/B → ...}: Reaction {counter(x) + decrement(_) → counter(?)} with inputs [counter(5) + decrement/P()] produced Exception. Retry run was scheduled. Message: crash! (it's OK, ignore this)")
  }

  it should "retry reactions that contain blocking molecules" in {
    val n = 20

    val chooser = new CrashChooser(probabilityOfCrash = 0.5)

    val c = new M[Int]("counter")
    val d = new B[Unit, Unit]("decrement")
    val g = new B[Unit, Unit]("getValue")
    val tp = FixedPool(2)

    site(tp0)(
      go { case c(x) + d(_, r) =>
        if (chooser(x)) throw new Exception("crash! (it's OK, ignore this)") else c(x - 1)
        r()
      }.withRetry onThreads tp,
      go { case c(0) + g(_, r) => r() }
    )
    c(n)
    (1 to n).foreach { _ =>
      if (d.timeout()(1500 millis).isEmpty) {
        println(s"first 50 items from global log for test 4:\n${memLog.messages.take(50).toList}") // this should not happen, but will be helpful for debugging
      }
    }

    val result = g.timeout()(1500 millis)
    tp.shutdownNow()
    result shouldEqual Some(())
    logShouldHave(memLog, "In Site{counter + decrement/B → .../R; counter + getValue/B → ...}: Reaction {counter(x) + decrement/B(_) → counter(?)} with inputs [counter(5) + decrement/BP()] produced Exception. Retry run was scheduled. Message: crash! (it's OK, ignore this)")
  }

}
