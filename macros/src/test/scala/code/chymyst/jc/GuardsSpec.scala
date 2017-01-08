package code.chymyst.jc

import Macros.{m, b, go}
import org.scalatest.{FlatSpec, Matchers}

class GuardsSpec extends FlatSpec with Matchers {

  behavior of "guard conditions"

  it should "correctly recognize a guard condition with captured variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) if x > n => }

    result.info.hasGuard shouldEqual GuardPresent(List(List('x))) // `n` should not be among the guard variables
  }

  it should "correctly recognize a guard condition with no variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) if 1 > n => }

    result.info.hasGuard shouldEqual GuardPresent(List(List())) // `n` should not be among the guard variables
  }

  it should "correctly split a guard condition with three clauses" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]
    val f = b[Unit, Unit]

    val result = go {
      case a(p) + a(y) + a(1) + bb((1, z)) + bb((t, Some(q))) + f(_, r) if y > 0 && q > 0 && t == p => r()
    }
    result.info.hasGuard shouldEqual GuardPresent(List(List('y), List('q), List('t, 'p)))
  }

  it should "correctly flatten a guard condition with nested clauses" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]

    val result = go {
      case a(p) + a(y) + a(1) + bb((1, z)) + bb((t, Some(q))) if p == 3 && (y > 0 && q > 0) && (t == p && y == q) =>
    }
    result.info.hasGuard shouldEqual GuardPresent(List(List('p), List('y), List('q), List('t, 'p), List('y, 'q)))
  }


}
