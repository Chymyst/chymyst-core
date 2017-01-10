package code.chymyst.jc

import Macros.{m, b, go}
import org.scalatest.{FlatSpec, Matchers}

class GuardsSpec extends FlatSpec with Matchers {

  behavior of "guard conditions"

  it should "correctly recognize a trivial true guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(Int,Option[Int])]

    val result = go { case a(Some(1)) + a(None) + bb( (2, Some(3)) ) if true => }
    result.info.guardPresence shouldEqual GuardAbsent

    result.info.inputs should matchPattern {
      case List(
        InputMoleculeInfo(`a`, SimpleConst(Some(1)), _),
        InputMoleculeInfo(`a`, SimpleConst(None), _),
        InputMoleculeInfo(`bb`, SimpleConst((2, Some(3))), _)
      ) =>
    }
    result.info.toString shouldEqual "a(None) + a(Some(1)) + bb((2,Some(3))) => "
  }

  it should "correctly recognize an indentically false guard condition" in {
    val a = m[Int]
    val n = 10
    a.isInstanceOf[M[Int]] shouldEqual true
    n shouldEqual n

    "val result = go { case a(x) if false => }" shouldNot compile
    "val result = go { case a(x) if false ^ false => }" shouldNot compile
    "val result = go { case a(x) if !(false ^ !false) => }" shouldNot compile
    "val result = go { case a(x) if false || (true && false) || !true && n > 0 => }" shouldNot compile
    "val result = go { case a(x) if false ^ (true && false) || !true && n > 0 => }" shouldNot compile
  }

  it should "correctly simplify guard condition using true and false" in {
    val a = m[Int]
    val n = 10
    val result = go { case a(x) if false || (true && false) || !false || n > 0 => }

    result.info.guardPresence shouldEqual AllMatchersAreTrivial

    result.info.inputs should matchPattern { case List(InputMoleculeInfo(`a`, SimpleVar('x, None), _)) => }
    result.info.toString shouldEqual "a(x) => "
  }

  it should "correctly recognize a guard condition with no variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) if 1 > n => }

    (result.info.guardPresence match {
      case GuardPresent(List(), Some(staticGuard), List()) => // `n` should not be among the guard variables
        staticGuard() shouldEqual false
        true
      case _ => false
    }) shouldEqual true

    result.info.inputs should matchPattern { case List(InputMoleculeInfo(`a`, SimpleVar('x, None), _)) => }
    result.info.toString shouldEqual "a(x) if(?) => "
  }

  it should "correctly compute reaction info" in {
    val a = m[Int]
    val b = m[Int]

    val reaction = go { case a(1) + b(x) if x > 1 => }

    (reaction.info.inputs(1).flag match {
      case SimpleVar(v, Some(cond)) =>
        cond.isDefinedAt(1) shouldEqual false
        cond.isDefinedAt(2) shouldEqual true
        true
      case _ => false
    }) shouldEqual true
  }

  it should "correctly recognize a guard condition with captured variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(xyz) if xyz > n => }

    result.info.guardPresence should matchPattern { case GuardPresent(List(List('xyz)), None, List()) => // `n` should not be among the guard variables
    }
    result.info.toString shouldEqual "a(xyz if ?) => "
    (result.info.inputs match {
      case List(InputMoleculeInfo(`a`, SimpleVar('xyz, Some(cond)), _)) =>
        cond.isDefinedAt(n + 1) shouldEqual true
        cond.isDefinedAt(n) shouldEqual false
        true
      case _ => false
    }) shouldEqual true
  }

  it should "correctly split a guard condition with several clauses" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if 1 > n && x > n && y > n => }

    (result.info.guardPresence match {
      case GuardPresent(List(List('x), List('y)), Some(staticGuard), List()) =>
        staticGuard() shouldEqual false
        true
      case _ => false
    }) shouldEqual true
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) if(?) => "
  }

  it should "correctly handle a guard condition with ||" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if (1 > n || x > n) && y > n => }

    result.info.guardPresence should matchPattern { case GuardPresent(List(List('x), List('y)), None, List()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) => "
  }

  it should "perform Boolean transformation on a guard condition to eliminate cross dependency" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n && y > n || 1 > n => }

    result.info.guardPresence should matchPattern { case GuardPresent(List(List('x), List('y)), None, List()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) => "
  }

  it should "correctly handle a guard condition with nontrivial matcher" in {
    val a = m[(Int, Int, Int)]

    val result = go { case a((x: Int, y: Int, z: Int)) if x > y => }

    result.info.guardPresence shouldEqual GuardPresent(List(List('x, 'y)), None, List())
    result.info.toString should fullyMatch regex "a\\(<[A-F0-9]{4}\\.\\.\\.>\\) => "

  }

  behavior of "cross-molecule guards"

  it should "handle a guard condition with cross dependency that cannot be eliminated by Boolean transformations" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x: Int) + a(y: Int) if x > n + y => }

    (result.info.guardPresence match {
      case GuardPresent(List(List('x, 'y)), None, List((List('x, 'y), guard_x_y))) =>
        guard_x_y.isDefinedAt(List(10, 0)) shouldEqual false
        guard_x_y.isDefinedAt(List(11, 0)) shouldEqual true
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
      case a(p: Int) + a(y: Int) + a(1) + bb((1, z)) + bb((t: Int, Some(qwerty))) + f(_, r) if y > 0 && n == 10 && qwerty == n && t > p && k < n => r()
    }
    (result.info.guardPresence match {
      case GuardPresent(List(List('y), List('qwerty), List('t, 'p)), Some(staticGuard), List((List('t, 'p), guard_t_p))) =>
        staticGuard() shouldEqual true
        true
      case _ => false
    }) shouldEqual true
  }

  it should "correctly flatten a guard condition with complicated nested clauses" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]

    val result = go {
      case a(p: Int) + a(y: Int) + a(1) + bb((1, z)) + bb((t: Int, Some(q: Int))) if p == 3 && ((t == q && y > 0) && q > 0) && (t == p && y == q) =>
    }
    (result.info.guardPresence match {
      case GuardPresent(List(List('p), List('t, 'q), List('y), List('q), List('t, 'p), List('y, 'q)), None, List((List('t, 'p), guard_t_p), (List('y, 'q), guard_y_q))) =>

        true
      case _ => false
    }) shouldEqual true
  }

}
