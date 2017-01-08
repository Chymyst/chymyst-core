package code.chymyst.jc

import Macros.{m, b, go}
import org.scalatest.{FlatSpec, Matchers}

class GuardsSpec extends FlatSpec with Matchers {

  behavior of "guard conditions"

  it should "correctly recognize a guard condition with captured variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) if x > n => }

    (result.info.hasGuard match {
      case GuardPresent(List(List('x)), None, List()) => // `n` should not be among the guard variables

        true
      case _ => false
    }) shouldEqual true
  }

  it should "correctly recognize a guard condition with no variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) if 1 > n => }

    (result.info.hasGuard match {
      case GuardPresent(List(), Some(staticGuard), List()) => // `n` should not be among the guard variables
        staticGuard() shouldEqual false
        true
      case _ => false
    }) shouldEqual true
  }

  it should "correctly split a guard condition when some clauses contain no pattern variables" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]
    val f = b[Unit, Unit]

    val k = 5
    val n = 10

    val result = go {
      case a(p) + a(y) + a(1) + bb((1, z)) + bb((t, Some(q))) + f(_, r) if y > 0 && n == 10 && q == n && t > p && k < n => r()
    }
    (result.info.hasGuard match {
      case GuardPresent(List(List('y), List('q), List('t, 'p)), Some(staticGuard), List()) =>
        staticGuard() shouldEqual true
        true
      case _ => false
    }) shouldEqual true
  }

  it should "correctly flatten a guard condition with complicated nested clauses" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]

    val result = go {
      case a(p) + a(y) + a(1) + bb((1, z)) + bb((t, Some(q))) if p == 3 && ((t == q && y > 0) && q > 0) && (t == p && y == q) =>
    }
    (result.info.hasGuard match {
      case GuardPresent(List(List('p), List('t, 'q), List('y), List('q), List('t, 'p), List('y, 'q)), None, List((List('t, 'p), guard_t_p), (List('y,'q), guard_y_q))) =>

        true
      case _ => false
    }) shouldEqual true
  }

}
