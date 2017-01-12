package code.chymyst.jc

import Macros.{m, b, go}
import org.scalatest.{FlatSpec, Matchers}

class GuardsSpec extends FlatSpec with Matchers {

  behavior of "guard conditions"

  it should "correctly recognize a trivial true guard condition" in {
    val a = m[Either[Int, String]]
    val bb = m[(Int, Option[Int])]

    val result = go { case a(Left(1)) + a(Right("input")) + bb((2, Some(3))) if true => a(Right("output")) }
    result.info.guardPresence shouldEqual GuardAbsent

    result.info.inputs should matchPattern {
      case List(
      InputMoleculeInfo(`a`, SimpleConst(Left(1)), _),
      InputMoleculeInfo(`a`, SimpleConst(Right("input")), _),
      InputMoleculeInfo(`bb`, SimpleConst((2, Some(3))), _)
      ) =>
    }
    result.info.toString shouldEqual "a(Left(1)) + a(Right(input)) + bb((2,Some(3))) => a(Right(output))"
  }

  it should "use parameterized types in simple guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(Int, Option[String])]

    val result = go { case a(xOpt) + bb(y) if xOpt.isEmpty && y._2.isEmpty => }
    result.info.guardPresence.effectivelyAbsent shouldEqual true
    result.info.guardPresence should matchPattern { case GuardPresent(List(List('xOpt), List('y)), None, List()) => }

    result.info.inputs should matchPattern {
      case List(
      InputMoleculeInfo(`a`, SimpleVar('xOpt, Some(_)), _),
      InputMoleculeInfo(`bb`, SimpleVar('y, Some(_)), _)
      ) =>
    }
    result.info.toString shouldEqual "a(xOpt if ?) + bb(y if ?) => "
  }

  it should "use parameterized types and tuple in simple guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(List[Int], Option[String])]

    val result = go { case a(Some(x)) + bb((list, Some(y))) if x == 1 && list.isEmpty && y == "abc" => }
    result.info.guardPresence.effectivelyAbsent shouldEqual true
    result.info.guardPresence should matchPattern { case GuardPresent(List(List('x), List('list), List('y)), None, List()) => }

    result.info.inputs should matchPattern {
      case List(
      InputMoleculeInfo(`a`, OtherInputPattern(_, List('x)), _),
      InputMoleculeInfo(`bb`, OtherInputPattern(_, List('list, 'y)), _)
      ) =>
    }
    result.info.toString shouldEqual "a(<FD6F...>) + bb(<DBA9...>) => "
  }

  it should "use parameterized types in cross-guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(Int, Option[String])]

    val result = go { case a(xOpt) + bb(y) if xOpt.isEmpty || y._2.isEmpty => }
    result.info.guardPresence.effectivelyAbsent shouldEqual false
    result.info.guardPresence should matchPattern { case GuardPresent(List(List('xOpt, 'y)), None, List((List('xOpt, 'y), _))) => }

    result.info.inputs should matchPattern {
      case List(
      InputMoleculeInfo(`a`, SimpleVar('xOpt, None), _),
      InputMoleculeInfo(`bb`, SimpleVar('y, None), _)
      ) =>
    }
    result.info.toString shouldEqual "a(xOpt) + bb(y) if(xOpt,y) => "
  }

  it should "use parameterized types and tuple in cross-guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(List[Int], Option[String])]

    val result = go { case a(Some(x)) + bb((list, Some(y))) if x == 1 || list.isEmpty || y == "abc" => }
    result.info.guardPresence.effectivelyAbsent shouldEqual false
    result.info.guardPresence should matchPattern { case GuardPresent(List(List('x, 'list, 'y)), None, List((List('x, 'list, 'y), _))) => }

    result.info.inputs should matchPattern {
      case List(
      InputMoleculeInfo(`a`, OtherInputPattern(_, List('x)), _),
      InputMoleculeInfo(`bb`, OtherInputPattern(_, List('list, 'y)), _)
      ) =>
    }
    result.info.toString shouldEqual "a(<643A...>) + bb(<9D3F...>) if(x,list,y) => "
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

  it should "compute reaction info with condition matcher" in {
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

  it should "compute reaction info with condition matcher on a compound type" in {
    val a = m[(Int, Int, Int, Int)]

    val reaction = go { case a((x, y, z, t)) if x > y => }

    reaction.info.guardPresence shouldEqual GuardPresent(List(List('x, 'y)), None, List())

    (reaction.info.inputs.head.flag match {
      case OtherInputPattern(cond, vars) =>
        cond.isDefinedAt((1, 2, 0, 0)) shouldEqual false
        cond.isDefinedAt((2, 1, 0, 0)) shouldEqual true
        vars shouldEqual List('x, 'y, 'z, 't)
        true
      case _ => false
    }) shouldEqual true
  }

  it should "recognize a guard condition with captured non-molecule variables" in {
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

  it should "correctly handle a guard condition with nontrivial unapply matcher" in {
    val a = m[(Int, Int, Int)]

    val result = go { case a((x, y, z)) if x > y => }

    result.info.guardPresence shouldEqual GuardPresent(List(List('x, 'y)), None, List())
    result.info.toString should fullyMatch regex "a\\(<[A-F0-9]{4}\\.\\.\\.>\\) => "
  }

  behavior of "cross-molecule guards"

  it should "handle a cross-molecule guard condition with missing types" in {
    val a = m[Int]

    val result = go { case a(x) + a(y) if x > y => }

    (result.info.guardPresence match {
      case GuardPresent(List(List('x, 'y)), None, List((List('x, 'y), guard_x_y))) =>
        guard_x_y.isDefinedAt(List(0, 0)) shouldEqual false
        guard_x_y.isDefinedAt(List(1, 0)) shouldEqual true
        true
      case _ => false
    }) shouldEqual true

    result.info.toString shouldEqual "a(x) + a(y) if(x,y) => "
  }

  it should "handle a guard condition with cross dependency that cannot be eliminated by Boolean transformations" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n + y => }

    (result.info.guardPresence match {
      case GuardPresent(List(List('x, 'y)), None, List((List('x, 'y), guard_x_y))) =>
        guard_x_y.isDefinedAt(List(10, 0)) shouldEqual false
        guard_x_y.isDefinedAt(List(11, 0)) shouldEqual true
        true
      case _ => false
    }) shouldEqual true

    result.info.toString shouldEqual "a(x) + a(y) if(x,y) => "
  }

  it should "correctly split a guard condition when some clauses contain no pattern variables" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]
    val f = b[Unit, Unit]

    val k = 5
    val n = 10

    val result = go {
      case a(p) + a(y) + a(1) + bb((1, z)) + bb((t, Some(qwerty))) + f(_, r) if y > 0 && n == 10 && qwerty == n && t > p && k < n => r()
    }
    (result.info.guardPresence match {
      case GuardPresent(List(List('y), List('qwerty), List('t, 'p)), Some(staticGuard), List((List('t, 'p), guard_t_p))) =>
        staticGuard() shouldEqual true
        true
      case _ => false
    }) shouldEqual true

    result.info.toString shouldEqual "a(1) + a(y if ?) + a(p) + bb(<26CD...>) + bb(<85B4...>) + f/B(_) if(t,p) => "
  }

  it should "correctly flatten a guard condition with complicated nested clauses" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]

    val result = go {
      case a(p) + a(y) + a(1) + bb((1, z)) + bb((t, Some(q))) if p == 3 && ((t == q && y > 0) && q > 0) && (t == p && y == q) =>
    }
    result.info.guardPresence should matchPattern {
      case GuardPresent(List(List('p), List('t, 'q), List('y), List('q), List('t, 'p), List('y, 'q)), None, List((List('t, 'p), guard_t_p), (List('y, 'q), guard_y_q))) =>
    }
    result.info.toString shouldEqual "a(1) + a(p if ?) + a(y if ?) + bb(<26CD...>) + bb(<E0BD...>) if(t,p,y,q) => "
  }

  it should "simplify a guard with an if clause and a negation of one term" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n && (if (y > n) 1 > n else !(x == y)) => }

    result.info.guardPresence should matchPattern { case GuardPresent(List(List('x), List('y), List('y, 'x)), None, List((List('y, 'x), _))) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) if(y,x) => "
  }

  it should "simplify a guard with an if clause into no cross guard" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n || (if (!false) 1 > n else x == y) => }

    result.info.guardPresence should matchPattern { case GuardPresent(List(List('x)), None, List()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y) => "
  }

}
