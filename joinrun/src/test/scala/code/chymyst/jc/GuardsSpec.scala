package code.chymyst.jc

import org.scalatest.{FlatSpec, Matchers}

class GuardsSpec extends FlatSpec with Matchers {

  behavior of "miscellaneous"

  it should "correctly recognize constants of various kinds" in {
    val a = m[Either[Int, String]]
    val bb = m[scala.Symbol]
    val ccc = m[List[Int]]

    val result = go { case ccc(Nil) + ccc(List()) + ccc(List(1)) + ccc(List(1, 2, 3)) + bb('input) + a(Right("input")) =>
      bb('output); ccc(Nil); ccc(List()); ccc(List(1)); ccc(List(1, 2, 3)); a(Right("output"))
    }

    result.info.toString shouldEqual "a(Right(input)) + bb('input) + ccc(List()) + ccc(List()) + ccc(List(1)) + ccc(List(1, 2, 3)) => bb('output) + ccc(List()) + ccc(List()) + ccc(List(1)) + ccc(List(1, 2, 3)) + a(Right(output))"

    result.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`ccc`, 0, SimpleConst(Nil), _),
      InputMoleculeInfo(`ccc`, 1, SimpleConst(List()), _),
      InputMoleculeInfo(`ccc`, 2, SimpleConst(List(1)), _),
      InputMoleculeInfo(`ccc`, 3, SimpleConst(List(1, 2, 3)), _),
      InputMoleculeInfo(`bb`, 4, SimpleConst('input), _),
      InputMoleculeInfo(`a`, 5, SimpleConst(Right("input")), _)
      ) =>
    }
  }

  behavior of "guard conditions"

  it should "correctly recognize a trivial true guard condition" in {
    val a = m[Either[Int, String]]
    val bb = m[(Int, Option[Int])]

    val result = go { case a(Left(1)) + a(Right("input")) + bb((2, Some(3))) + bb((0, None)) if true => a(Right("output")); bb((1, None)) }
    result.info.guardPresence shouldEqual GuardAbsent

    result.info.toString shouldEqual "a(Left(1)) + a(Right(input)) + bb((0,None)) + bb((2,Some(3))) => a(Right(output)) + bb((1,None))"

    result.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, SimpleConst(Left(1)), _),
      InputMoleculeInfo(`a`, 1, SimpleConst(Right("input")), _),
      InputMoleculeInfo(`bb`, 2, SimpleConst((2, Some(3))), _),
      InputMoleculeInfo(`bb`, 3, SimpleConst((0, None)), _)
      ) =>
    }

  }

  it should "use parameterized types in simple guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(Int, Option[String])]

    val result = go { case a(xOpt) + bb(y) if xOpt.isEmpty && y._2.isEmpty => }
    result.info.guardPresence.effectivelyAbsent shouldEqual true
    result.info.guardPresence should matchPattern { case GuardPresent(Array(Array('xOpt), Array('y)), None, Array()) => }

    result.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, SimpleVar('xOpt, Some(_)), _),
      InputMoleculeInfo(`bb`, 1, SimpleVar('y, Some(_)), _)
      ) =>
    }
    result.info.toString shouldEqual "a(xOpt if ?) + bb(y if ?) => "
  }

  it should "use parameterized types and tuple in simple guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(List[Int], Option[String])]

    val result = go { case a(Some(x)) + bb((list, Some(y))) if x == 1 && list.isEmpty && y == "abc" => }
    result.info.guardPresence.effectivelyAbsent shouldEqual true
    result.info.guardPresence should matchPattern { case GuardPresent(Array(Array('x), Array('list), Array('y)), None, Array()) => }

    result.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, OtherInputPattern(_, List('x), false), _),
      InputMoleculeInfo(`bb`, 1, OtherInputPattern(_, List('list, 'y), false), _)
      ) =>
    }
    result.info.toString shouldEqual "a(?x) + bb(?list,y) => "
  }

  it should "use parameterized types in cross-molecule guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(Int, Option[String])]

    val result = go { case a(xOpt) + bb(y) if xOpt.isEmpty || y._2.isEmpty => }
    result.info.guardPresence.effectivelyAbsent shouldEqual false
    result.info.guardPresence should matchPattern { case GuardPresent(Array(Array('xOpt, 'y)), None, Array(CrossMoleculeGuard(Array(0, 1), Array('xOpt, 'y), _))) => }

    result.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, SimpleVar('xOpt, None), _),
      InputMoleculeInfo(`bb`, 1, SimpleVar('y, None), _)
      ) =>
    }
    result.info.toString shouldEqual "a(xOpt) + bb(y) if(xOpt,y) => "
  }

  it should "use parameterized types and tuple in cross-molecule guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(List[Int], Option[String])]

    val result = go { case a(Some(x)) + bb((list, Some(y))) if x == 1 || list.isEmpty || y == "abc" => }
    result.info.guardPresence.effectivelyAbsent shouldEqual false
    result.info.guardPresence should matchPattern { case GuardPresent(Array(Array('x, 'list, 'y)), None, Array(CrossMoleculeGuard(Array(0, 1), Array('x, 'list, 'y), _))) => }

    result.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, OtherInputPattern(_, List('x), false), _),
      InputMoleculeInfo(`bb`, 1, OtherInputPattern(_, List('list, 'y), false), _)
      ) =>
    }
    result.info.toString shouldEqual "a(?x) + bb(?list,y) if(x,list,y) => "
  }

  it should "correctly recognize an identically false guard condition" in {
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

    result.info.inputs should matchPattern { case Array(InputMoleculeInfo(`a`, 0, SimpleVar('x, None), _)) => }
    result.info.toString shouldEqual "a(x) => "
  }

  it should "correctly recognize a guard condition with no variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) if 1 > n => }

    (result.info.guardPresence match {
      case GuardPresent(Array(), Some(staticGuard), Array()) => // `n` should not be among the guard variables
        staticGuard() shouldEqual false
        true
      case _ => false
    }) shouldEqual true

    result.info.inputs should matchPattern { case Array(InputMoleculeInfo(`a`, 0, SimpleVar('x, None), _)) => }
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

    reaction.info.guardPresence should matchPattern { case GuardPresent(Array(Array('x, 'y)), None, Array()) => }

    reaction.info.inputs.head.flag should matchPattern { case OtherInputPattern(_, _, false) => }
    (reaction.info.inputs.head.flag match {
      case OtherInputPattern(cond, vars, false) =>
        cond.isDefinedAt((1, 2, 0, 0)) shouldEqual false
        cond.isDefinedAt((2, 1, 0, 0)) shouldEqual true
        vars shouldEqual List('x, 'y, 'z, 't)
        true
      case _ => false
    }) shouldEqual true
  }

  it should "compute reaction info with compound irrefutable matcher" in {
    val a = m[(Int, (Int, Int), Int)]

    val reaction = go { case a(x@(_, (y@_, z), t)) => }

    reaction.info.guardPresence should matchPattern { case AllMatchersAreTrivial => }

    reaction.info.inputs.head.flag should matchPattern { case OtherInputPattern(_, List('x, 'y, 'z, 't), true) => }
  }

  it should "compute reaction info with alternative irrefutable matcher" in {
    val a = m[(Int, Int)]

    val reaction = go { case a((_,_) | (_,_)) => }

    reaction.info.guardPresence should matchPattern { case AllMatchersAreTrivial => }

    reaction.info.inputs.head.flag should matchPattern { case OtherInputPattern(_, List(), true) => }
  }

  it should "recognize a guard condition with captured non-molecule variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(xyz) if xyz > n => }

    result.info.guardPresence should matchPattern { case GuardPresent(Array(Array('xyz)), None, Array()) => // `n` should not be among the guard variables
    }
    result.info.toString shouldEqual "a(xyz if ?) => "
    (result.info.inputs match {
      case Array(InputMoleculeInfo(`a`, 0, SimpleVar('xyz, Some(cond)), _)) =>
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
      case GuardPresent(Array(Array('x), Array('y)), Some(staticGuard), Array()) =>
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

    result.info.guardPresence should matchPattern { case GuardPresent(Array(Array('x), Array('y)), None, Array()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) => "
  }

  it should "perform Boolean transformation on a guard condition to eliminate cross dependency" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n && y > n || 1 > n => }

    result.info.guardPresence should matchPattern { case GuardPresent(Array(Array('x), Array('y)), None, Array()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) => "
  }

  it should "correctly handle a guard condition with nontrivial unapply matcher" in {
    val a = m[(Int, Int, Int)]

    val result = go { case a((x, y, z)) if x > y => }

    result.info.guardPresence should matchPattern { case GuardPresent(Array(Array('x, 'y)), None, Array()) => }
    result.info.toString shouldEqual "a(?x,y,z) => "
  }

  it should "compile a guard that references a variable via library functions" in {
    val c = m[(Int, Array[Int])]
    val d = m[Array[Int]]

    /* This works correctly. */
    val reaction1 = go { case c((_, arr)) if arr.nonEmpty => }
    reaction1.info.inputs.head should matchPattern { case InputMoleculeInfo(`c`, 0, OtherInputPattern(_, List('arr), false), _) => }

    /* The guard `if arr.nonEmpty` is not compiled correctly: it generates the partial function
      { case (arr @ _) if scala.Predef.intArrayOps(arr).nonEmpty => () }
      which gives a type error: `arr` is typed as `Any` instead of `Array[Int]` as required.
    */
    val reaction2 = go { case d(arr) if arr.nonEmpty => }
    reaction2.info.inputs.head should matchPattern { case InputMoleculeInfo(`d`, 0, SimpleVar('arr, Some(_)), _) => }
  }

  behavior of "cross-molecule guards"

  it should "handle a cross-molecule guard condition with missing types" in {
    val a = m[Int]

    val result = go { case a(x) + a(y) if x > y => }

    (result.info.guardPresence match {
      case GuardPresent(Array(Array('x, 'y)), None, Array(CrossMoleculeGuard(Array(0, 1), Array('x, 'y), cond))) =>
        cond.isDefinedAt(List(0, 0)) shouldEqual false
        cond.isDefinedAt(List(1, 0)) shouldEqual true
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
      case GuardPresent(Array(Array('x, 'y)), None, Array(CrossMoleculeGuard(Array(0, 1), Array('x, 'y), cond))) =>
        cond.isDefinedAt(List(10, 0)) shouldEqual false
        cond.isDefinedAt(List(11, 0)) shouldEqual true
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
      case GuardPresent(Array(Array('y), Array('qwerty), Array('t, 'p)), Some(staticGuard), Array(CrossMoleculeGuard(Array(0, 4), Array('t, 'p), cond))) =>
        cond.isDefinedAt(List(1, (2, Some(3)))) shouldEqual true
        cond.isDefinedAt(List(2, (2, Some(3)))) shouldEqual false
        staticGuard() shouldEqual true
        true
      case _ => false
    }) shouldEqual true

    result.info.toString shouldEqual "a(1) + a(y if ?) + a(p) + bb(?z) + bb(?t,qwerty) + f/B(_) if(t,p) => "
  }

  it should "correctly flatten a guard condition with complicated nested clauses" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]

    val result = go {
      case a(p) + a(y) + a(1) + bb((1, z)) + bb((t, Some(q))) if p == 3 && ((t == q && y > 0) && q > 0) && (t == p && y == q) =>
    }
    result.info.guardPresence should matchPattern {
      case GuardPresent(Array(Array('p), Array('t, 'q), Array('y), Array('q), Array('t, 'p), Array('y, 'q)), None, Array(CrossMoleculeGuard(Array(0, 4), Array('t, 'p), _), CrossMoleculeGuard(Array(1, 4), Array('y, 'q), _))) =>
    }
    result.info.guardPresence.toString shouldEqual "GuardPresent([['p], ['t,'q], ['y], ['q], ['t,'p], ['y,'q]], None, [CrossMoleculeGuard([0,4], ['t,'p]); CrossMoleculeGuard([1,4], ['y,'q])])"
    result.info.toString shouldEqual "a(1) + a(p if ?) + a(y if ?) + bb(?z) + bb(?t,q) if(t,p,y,q) => "
  }

  it should "simplify a guard with an if clause and a negation of one term" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n && (if (y > n) 1 > n else !(x == y)) => }

    result.info.guardPresence should matchPattern { case GuardPresent(Array(Array('x), Array('y), Array('y, 'x)), None, Array(CrossMoleculeGuard(Array(0, 1), Array('y, 'x), _))) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) if(y,x) => "
  }

  it should "simplify a guard with an if clause into no cross guard" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n || (if (!false) 1 > n else x == y) => }

    result.info.guardPresence should matchPattern { case GuardPresent(Array(Array('x)), None, Array()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y) => "
  }

}
