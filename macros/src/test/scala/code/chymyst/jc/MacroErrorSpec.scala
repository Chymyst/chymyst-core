package code.chymyst.jc

import Macros.{m, b, go}
import org.scalatest.{FlatSpec, Matchers}

class MacroErrorSpec extends FlatSpec with Matchers {

  behavior of "compile-time errors"

  it should "fail to compile reactions with detectable compile-time errors" in {
    val a = b[Unit, Unit]
    val c = b[Unit, Unit]
    val e = m[Unit]

    a.isInstanceOf[B[Unit,Unit]] shouldEqual true
    c.isInstanceOf[B[Unit,Unit]] shouldEqual true
    e.isInstanceOf[M[Unit]] shouldEqual true

    // Note: these tests will produce several warnings "expects 2 patterns to hold but crushing into 2-tuple to fit single pattern".
    // However, it is precisely this crushing that we are testing here, that actually should not compile with our `go` macro.
    // So, these warnings cannot be removed here and should be ignored.
    "val r = go { case e() => }" shouldNot compile // no pattern variable in a non-blocking molecule "e"
    "val r = go { case e(_,_) => }" shouldNot compile // two pattern variables in a non-blocking molecule "e"
    "val r = go { case e(_,_,_) => }" shouldNot compile // two pattern variables in a non-blocking molecule "e"

    "val r = go { case a() => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = go { case a(_) => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = go { case a(_, _) => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = go { case a(_, _, _) => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = go { case a(_, r) => }" shouldNot compile // no reply is performed with r
    "val r = go { case a(_, r) + a(_) + c(_) => r()  }" shouldNot compile // invalid patterns for "a" and "c"
    "val r = go { case a(_, r) + a(_) + c(_) => r(); r() }" shouldNot compile // two replies are performed with r, and invalid patterns for "a" and "c"

    "val r = go { case e(_) if false => c() }" should compile // input guard does not emit molecules
    "val r = go { case e(_) if c() => }" shouldNot compile // input guard emits molecules
    "val r = go { case a(_,r) if r() => }" shouldNot compile // input guard performs reply actions

    "val r = go { case e(_) => { case e(_) => } }" shouldNot compile // reaction body matches on input molecules
  }

  it should "fail to compile reactions with no input molecules" in {
    val bb = m[Int]
    val bbb = m[Int]

    bb.isInstanceOf[M[Int]] shouldEqual true
    bbb.isInstanceOf[M[Int]] shouldEqual true

    "val r = go { case _ => bb(0) }" should compile // declaration of a singleton
    "val r = go { case x => bb(x.asInstanceOf[Int]) }" shouldNot compile // no input molecules
    "val r = go { case x => x }" shouldNot compile // no input molecules
  }

  it should "fail to compile reactions with unconditional livelock" in {
    val a = m[(Int, Int)]
    val bb = m[Int]
    val bbb = m[Int]

    a.isInstanceOf[M[(Int,Int)]] shouldEqual true
    bb.isInstanceOf[M[Int]] shouldEqual true
    bbb.isInstanceOf[M[Int]] shouldEqual true

    "val r = go { case a((x,y)) => a((1,1)) }" should compile // cannot detect unconditional livelock here
    "val r = go { case a((_,x)) => a((x,x)) }" should compile // cannot detect unconditional livelock here
    "val r = go { case a((1,_)) => a((1,1)) }" should compile // cannot detect unconditional livelock here

    "val r = go { case bb(x) => bb(1) }" shouldNot compile // unconditional livelock
    "val r = go { case bb(x) if x > 0 => bb(1) }" should compile // no unconditional livelock due to guard

    "val r = go { case a(_) => a((1,1)) }" shouldNot compile // unconditional livelock

    "val r = go { case bbb(1) => bbb(2) }" should compile // no unconditional livelock

    "val r = go { case bbb(_) => bbb(0) }" shouldNot compile // unconditional livelock
    "val r = go { case bbb(x) => bbb(x) + bb(x) }" shouldNot compile
    "val r = go { case bbb(x) + bb(y) => bbb(x) + bb(x) + bb(y) }" shouldNot compile
  }

}