package code.chymyst.jc

import Chymyst.withPool
import Core._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest._

import scala.language.postfixOps
import scala.concurrent.duration.DurationInt

/** More unit tests for blocking molecule functionality.
  *
  */
class BlockingMoleculesSpec extends FlatSpec with Matchers with TimeLimitedTests with BeforeAndAfterEach {

  var testNumber = 0

  override def runTest(testName: String, args: Args): Status = {
    testNumber += 1
    println(s"Starting test $testNumber: $testName")
    super.runTest(testName, args)
    //(1 to 100).map {i => println(s"Iteration $i"); super.runTest(testName, args)}.reduce{ (s1, s2) => if (s1.succeeds) s2 else s1 }
  }

  var tp0: Pool = _

  override def beforeEach(): Unit = {
    tp0 = new SmartPool(12)
  }

  override def afterEach(): Unit = {
    tp0.shutdownNow()
  }

  val timeLimit = Span(6000, Millis)

  behavior of "blocking molecule"

  it should "block for a blocking molecule" in {

    val a = new E("a")
    val f = new EB[Int]("f")
    site(tp0)( _go { case a(_) + f(_, r) => r(3) })
    a()
    a()
    a()
    f() shouldEqual 3
    f() shouldEqual 3
    f() shouldEqual 3
  }

  it should "not timeout when a blocking molecule is responding" in {

    (1 to 1000).map { _ =>
      val f = new EB[Int]("f")
      site(tp0)( _go { case f(_, r) => r(0) })

      f.timeout(500 millis)().getOrElse(1)
    }.sum shouldEqual 0 // we used to have about 4% failure rate here!
  }

  it should "timeout when a blocking molecule is not responding at all" in {

    val a = new E("a")
    val f = new EB[Int]("f")
    site(tp0)( _go { case a(_) + f(_, r) => r(3) })
    a()
    f() shouldEqual 3 // now the a() molecule is gone
    f.timeout(500 millis)() shouldEqual None
  }

  it should "not timeout when a blocking molecule is responding quickly enough" in {

    val a = new E("a")
    val f = new EB[Int]("f")
    site(tp0)( _go { case a(_) + f(_, r) => Thread.sleep(100); r(3) })
    a()
    f.timeout(500 millis)() shouldEqual Some(3)
  }

  it should "timeout when a blocking molecule is not responding quickly enough" in {

    val a = new E("a")
    val f = new EB[Int]("f")
    site(tp0)( _go { case a(_) + f(_, r) => Thread.sleep(500); r(3) })
    a()
    f.timeout(200 millis)() shouldEqual None
  }

  behavior of "syntax of blocking molecules with unit values / replies"

  it should "allow non-unit values and non-unit replies" in {
    val f = new B[Int,Int]("f")
    site(tp0)( _go { case f(x, r) if x == 123 => r(456) })
    f(123) shouldEqual 456
  }

  it should "allow non-unit values and non-unit replies with timeout" in {
    val f = new B[Int,Int]("f")
    site(tp0)( _go { case f(x, r) if x != 123 => r(456) })
    f.timeout(200 millis)(123) shouldEqual None
  }

  it should "allow non-unit values and unit replies" in {
    val f = new BE[Int]("f")
    site(tp0)( _go { case f(x, r) if x == 123 => r() })
    f(123) shouldEqual (())
  }

  it should "allow non-unit values and unit replies with timeout" in {
    val f = new BE[Int]("f")
    site(tp0)( _go { case f(x, r) if x != 123 => r() })
    f.timeout(200 millis)(123) shouldEqual None
  }

  it should "allow unit values and unit replies" in {
    val f = new EE("f")
    site(tp0)( _go { case f(x, r) if x == (()) => r() })
    f() shouldEqual (())
  }

  it should "allow unit values and unit replies with timeout" in {
    val f = new EE("f")
    site(tp0)( _go { case f(x, r) if x != (()) => r() })
    f.timeout(200 millis)() shouldEqual None
  }

  behavior of "reaction sites with invalid replies"

  it should "use the first reply when a reaction attempts to reply twice" in {
    val c = new M[Int]("c")
    val d = new E("d")
    val e = new E("e")
    val f = new EE("f")
    val g = new EB[Int]("g")
    val g2 = new EB[Int]("g")
    site(tp0)(
      _go { case e(_) => c(g()) },
      _go { case g(_,r) => r.checkTimeout(123); r.checkTimeout(0); d() },
      _go { case g2(_,r) + c(x) => r(x) },
      _go { case d(_) + f(_,r) => r() }
    )
    e()
    f() // make sure "r(0)" was called
    g2() shouldEqual 123
  }

  it should "get all errors when a reaction attempts to reply twice to more than one molecule" in {

    val iterations = 100

    def check(i: Int) = {
      val c = new E("c")
      val d = new E("d")
      val d2 = new M[Int]("d2")
      val e = new EB[Int]("e")
      val g = new EB[Int]("g")
      val g2 = new EB[Int]("g2")
      val h = new EE("h")
      val tp1 = new FixedPool(4)
      site(tp1)(
        _go { case c(_) => h() },
        _go { case d(_) => d2(g()) },
        _go { case d2(x) + e(_, r) => r(x) },
        _go { case g(_, r) + g2(_, r2) + h(_, r3) => r(123)+ r3() + r(0) + r3() } // no answer to g2, two answers to g and h
      )
      c() + d()

      val thrown = intercept[Exception] {
        val res = g2()
        println(s"Iteration $i: got result $res but should not have printed this!")
      }
      thrown.getMessage shouldEqual "Error: In Site{c => ...; d => ...; d2 + e/B => ...; g/B + g2/B + h/B => ...}: Reaction {g/B(?) + g2/B(?) + h/B(?) ? => ?} with inputs [g/B(), g2/B(), h/B()] finished without replying to g2/B; Error: In Site{c => ...; d => ...; d2 + e/B => ...; g/B + g2/B + h/B => ...}: Reaction {g/B(?) + g2/B(?) + h/B(?) ? => ?} with inputs [g/B(), g2/B(), h/B()] replied to g/B, h/B more than once"

      e() shouldEqual 123
      tp1.shutdownNow()
    }

    (1 to iterations).foreach(check) // Make sure there is no race condition.
  }

  it should "get all errors when a reaction attempts to reply many times to more than one molecule" in {

    val replies = 20
    val iterations = 100

    def check(i: Int) = {
      val c = new E("c")
      val d = new E("d")
      val d2 = new M[Int]("d2")
      val e = new EB[Int]("e")
      val g = new EB[Int]("g")
      val g2 = new EB[Int]("g2")
      val h = new EE("h")
      val tp1 = new FixedPool(4)
      site(tp1)(
        _go { case c(_) => h() },
        _go { case d(_) => d2(g()) },
        _go { case d2(x) + e(_, r) => r(x) },
        _go { case g(_, r) + g2(_, r2) + h(_, r3) => (1 to replies).foreach{ _ => r(123); r3() } } // no answer to g2, many answers to g and h
      )
      c() + d()

      val thrown = intercept[Exception] {
        val res = g2()
        println(s"Iteration $i: got result $res but should not have printed this!")
      }
      thrown.getMessage shouldEqual "Error: In Site{c => ...; d => ...; d2 + e/B => ...; g/B + g2/B + h/B => ...}: Reaction {g/B(?) + g2/B(?) + h/B(?) ? => ?} with inputs [g/B(), g2/B(), h/B()] finished without replying to g2/B; Error: In Site{c => ...; d => ...; d2 + e/B => ...; g/B + g2/B + h/B => ...}: Reaction {g/B(?) + g2/B(?) + h/B(?) ? => ?} with inputs [g/B(), g2/B(), h/B()] replied to g/B, h/B more than once"

      e() shouldEqual 123
      tp1.shutdownNow()
    }

    (1 to iterations).foreach(check) // Make sure there is no race condition.
  }

  it should "throw exception when a reaction does not reply to one blocking molecule" in {
    val c = new E("c")
    val g = new EB[Int]("g")
    site(tp0)(
      _go { case c(_) + g(_,r) => c() }
    )
    c()

    val thrown = intercept[Exception] {
      println(s"got result: ${g()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Site{c + g/B => ...}: Reaction {c(?) + g/B(?) ? => ?} with inputs [c(), g/B()] finished without replying to g/B"
  }

  it should "throw exception when a reaction does not reply to one blocking molecule, with timeout" in {
    val c = new E("c")
    val g = new EB[Int]("g")
    site(tp0)(
      _go { case c(_) + g(_,r) => c() }
    )
    c()

    val thrown = intercept[Exception] {
      println(s"got result: ${g.timeout(1 second)()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Site{c + g/B => ...}: Reaction {c(?) + g/B(?) ? => ?} with inputs [c(), g/B()] finished without replying to g/B"
  }

  it should "throw exception when a reaction does not reply to two blocking molecules)" in {
    val c = new E("c")
    val d = new E("d")
    val g = new EB[Int]("g")
    val g2 = new EB[Int]("g2")
    val tp = new FixedPool(4)
    site(tp)(
      _go { case d(_) => g2() } onThreads tp,
      _go { case c(_) + g(_,_) + g2(_,_) => c() }
    )
    c() + d()

    val thrown = intercept[Exception] {
      println(s"got result2: ${g()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Site{c + g/B + g2/B => ...; d => ...}: Reaction {c(?) + g/B(?) + g2/B(?) ? => ?} with inputs [c(), g/B(), g2/B()] finished without replying to g/B, g2/B"

    tp.shutdownNow()
  }

  it should "get a reply when a reaction does not reply to one blocking molecule but does reply to another" in {
    val c = new E("c")
    val d = new E("d")
    val g = new EB[Int]("g")
    val g2 = new EB[Int]("g2")
    val tp = new FixedPool(4)
    site(tp)(
      _go { case d(_) => g() } onThreads tp,
      _go { case c(_) + g(_,r) + g2(_,_) => c(); r(0) }
    )
    c() + d()

    val thrown = intercept[Exception] {
      println(s"got result2: ${g2()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Site{c + g/B + g2/B => ...; d => ...}: Reaction {c(?) + g/B(?) + g2/B(?) ? => ?} with inputs [c(), g/B(), g2/B()] finished without replying to g2/B"

    tp.shutdownNow()
  }

  behavior of "deadlocked threads with blocking molecules"

  it should "not produce deadlock when two blocking molecules are emitted from different threads" in {
    val c = new E("c")
    val d = new E("d")
    val e = new M[Int]("e")
    val g = new EB[Int]("g")
    val g2 = new EB[Int]("g2")
    val h = new EB[Int]("h")
    val tp = new FixedPool(4)
    site(tp0)(
      _go { case c(_) => e(g2()) }, // e(0) should be emitted now
      _go { case d(_) + g(_,r) + g2(_,r2) => r(0) + r2(0) } onThreads tp,
      _go { case e(x) + h(_,r) =>  r(x) }
    )
    c()+d()
    g() shouldEqual 0
    // now we should also have e(0)
    h() shouldEqual 0

    tp.shutdownNow()

  }

  it should "produce deadlock when two blocking molecules are emitted from the same thread" in {
    val c = new E("c")
    val d = new E("d")
    val e = new M[Int]("e")
    val f = new E("f")
    val g = new EB[Int]("g")
    val g2 = new EB[Int]("g2")
    val h = new EB[Int]("h")
    val tp = new FixedPool(4)
    site(tp0)(
      _go { case c(_) => val x = g(); g2(); e(x) }, // e(0) should never be emitted because this thread is deadlocked
      _go { case g(_,r) + g2(_,r2) => r(0) + r2(0) } onThreads tp,
      _go { case e(x) + h(_,r) =>  r(x) },
      _go { case d(_) + f(_) => e(2) },
      _go { case f(_) + e(_) => e(1) }
    )

    c()
    // if e(0) exists now, it will react with f() and produce e(1)
    f()
    h.timeout(200 millis)() shouldEqual None   // make sure we still have neither e(0) nor e(1)
    // if e(0) did not appear, f() is still available and will now react with d and produce e(2)
    d()
    h() shouldEqual 2

    tp.shutdownNow()
  }

  it should "not block the 2-thread threadpool when one thread is blocked" in {
    val c = new E("c")
    val d = new E("d")
    val g = new EB[Int]("g")
    val g2 = new EB[Int]("g2")
    val tp = new FixedPool(2)
    site(tp)(
      _go { case d(_) => g() }, // this will be used to emit g() and blocked
      _go { case c(_) + g(_,r) => r(0) }, // this will not start because we have no c()
      _go { case g2(_, r) => r(1) } // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // do not emit c(). Now the first reaction is blocked because second reaction cannot start.
    g2() shouldEqual 1 // this should continue to work
    tp.shutdownNow()
  }

  it should "block the 1-thread threadpool when one thread is blocked" in {
    val c = new E("c")
    val d = new E("d")
    val g = new EB[Int]("g")
    val g2 = new EB[Int]("g2")
    val tp = new FixedPool(1)
    val tp1 = new FixedPool(1)
    site(tp,tp1)(
      _go { case d(_) => g() }, // this will be used to emit g() and block one thread
      _go { case c(_) + g(_,r) => r(0) }, // this will not start because we have no c()
      _go { case g2(_, r) => r(1) } // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // do not emit c(). Now the first reaction is blocked because second reaction cannot start.
    g2.timeout(300 millis)() shouldEqual None // this should be blocked now
    tp.shutdownNow()
    tp1.shutdownNow()
  }

  def makeBlockingCheck(sleeping: => Unit, tp1: Pool): (EE, EB[Int]) = {
    val c = new E("c")
    val d = new E("d")
    val g = new EE("g")
    val g2 = new EB[Int]("g2")

    site(tp0)( _go { case c(_) + g(_, r) => r() } ) // we will use this to monitor the d() reaction

    site(tp1,tp0)(
      _go { case d(_) => c(); sleeping; c() }, // this thread is blocked by sleeping
      _go { case g2(_, r) => r(1) } // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work, since d() has not yet been emitted.
    d() // Now the first reaction will be starting soon.
    g() // Now we know that the first reaction has started and is sleeping.
    (g, g2)
  }

  it should "block the fixed threadpool when one thread is sleeping with Thread.sleep" in {
    val tp = new FixedPool(1)
    val res = makeBlockingCheck(Thread.sleep(500), tp)
    res._2.timeout(150 millis)() shouldEqual None // this should be blocked
    tp.shutdownNow()
  }

  it should "block the fixed threadpool when one thread is sleeping with BlockingIdle(Thread.sleep)" in {
    val tp = new FixedPool(1)
    val res = makeBlockingCheck(BlockingIdle{Thread.sleep(500)}, tp)
    res._2.timeout(150 millis)() shouldEqual None // this should be blocked
    tp.shutdownNow()
  }

  it should "not block the smart threadpool with BlockingIdle(Thread.sleep)" in {
    val tp = new SmartPool(1)
    val (g, g2) = makeBlockingCheck(BlockingIdle{Thread.sleep(500)}, tp)
    g2.timeout(150 millis)() shouldEqual Some(1) // this should not be blocked
    tp.currentPoolSize shouldEqual 2
    g() // now we know that the first reaction has finished
    tp.currentPoolSize shouldEqual 1
    tp.shutdownNow()
  }

  it should "implement BlockingIdle(BlockingIdle()) as BlockingIdle()" in {
    val tp = new SmartPool(1)
    val (g, g2) = makeBlockingCheck(BlockingIdle{BlockingIdle{Thread.sleep(300)}}, tp)
    g2.timeout(150 millis)() shouldEqual Some(1) // this should not be blocked
    tp.currentPoolSize shouldEqual 2
    g() // now we know that the first reaction has finished
    tp.currentPoolSize shouldEqual 1
    tp.shutdownNow()
  }

  behavior of "thread starvation with different threadpools"

  def blockThreadsDueToBlockingMolecule(tp1: Pool): EE = {
    val c = new E("c")
    val cStarted = new E("cStarted")
    val never = new E("never")
    val f = new EB[Int]("forever")
    val f2 = new EB[Int]("forever2")
    val g = new EE("g")
    val started = new EE("started")

    site(tp1,tp0)(
      _go { case g(_, r) => r() }, // and so this reaction will be blocked forever
      _go { case c(_) => cStarted(); println(f()) }, // this reaction is blocked forever because f() does not reply
      _go { case cStarted(_) + started(_, r) => r(); println(f2()) }, // this reaction is blocked forever because f2() does not reply
      _go { case f(_, r) + never(_) => r(0)}, // this will never reply since "never" is never emitted
      _go { case f2(_, r) + never(_) => r(0)} // this will never reply since "never" is never emitted
    )

    c()
    started.timeout(500 millis)() shouldEqual Some(()) // now we are sure that both reactions are running and stuck
    g
  }

  it should "block the fixed threadpool when all threads are waiting for new reactions" in {
    withPool(new FixedPool(2)) { tp =>
      val g = blockThreadsDueToBlockingMolecule(tp)
      g.timeout(200 millis)() shouldEqual None
    }
  }

  it should "not block the fixed threadpool when more threads are available" in {
    withPool(new FixedPool(3)) { tp =>
      val g = blockThreadsDueToBlockingMolecule(tp)
      g.timeout(200 millis)() shouldEqual Some(())
    }
  }

  it should "not block the smart threadpool when all threads are waiting for new reactions" in {
    val tp = new SmartPool(2)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g.timeout(200 millis)() shouldEqual Some(())
    tp.shutdownNow()
  }

  it should "not block the smart threadpool when more threads are available" in {
    val tp = new SmartPool(3)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g.timeout(200 millis)() shouldEqual Some(())
    tp.shutdownNow()
  }

}
