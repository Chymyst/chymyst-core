package io.chymyst.jc

import io.chymyst.test.LogSpec

import scala.concurrent.duration._

class GuardsSpec extends LogSpec {

  behavior of "miscellaneous"

  it should "correctly recognize List() and Nil() constants" in {
    val aaa = m[List[Int]]
    val bbb = m[List[Int]]
    val result = go { case aaa(Nil) + aaa(List()) => bbb(List()) + bbb(Nil) }
    result.info.inputs.toList should matchPattern {
      case List(
      InputMoleculeInfo(`aaa`, 'aaa, 0, ConstInputPattern(List()), _, Symbol("List[Int]")),
      InputMoleculeInfo(`aaa`, 'aaa, 1, ConstInputPattern(List()), _, _)
      ) =>
    }
    result.info.outputs.toList should matchPattern {
      case List(
      OutputMoleculeInfo(`bbb`, 'bbb, ConstOutputPattern(List()), _),
      OutputMoleculeInfo(`bbb`, 'bbb, ConstOutputPattern(List()), _)
      ) ⇒
    }
    result.info.outputs.toList.map(_.environments) should matchPattern {
      case List(
      List(),
      List()
      ) ⇒
    }
    result.info.toString shouldEqual "aaa(List()) + aaa(List()) → bbb(List()) + bbb(List())"
  }

  it should "correctly recognize constants of various kinds" in {
    val a = m[Either[Int, String]]
    val bb = m[scala.Symbol]
    val ccc = m[List[Int]]

    val result = go { case ccc(Nil) + ccc(List()) + ccc(List(1)) + ccc(List(1, 2, 3)) + bb('input) + a(Right("input")) =>
      bb('output); ccc(Nil); ccc(List()); ccc(List(1)); ccc(List(1, 2, 3)); a(Right("output"))
    }

    result.info.inputs.toList should matchPattern {
      case List(
      InputMoleculeInfo(`ccc`, 'ccc, 0, ConstInputPattern(Nil), _, Symbol("List[Int]")),
      InputMoleculeInfo(`ccc`, 'ccc, 1, ConstInputPattern(List()), _, _),
      InputMoleculeInfo(`ccc`, 'ccc, 2, ConstInputPattern(List(1)), _, _),
      InputMoleculeInfo(`ccc`, 'ccc, 3, ConstInputPattern(List(1, 2, 3)), _, _),
      InputMoleculeInfo(`bb`, 'bb, 4, ConstInputPattern('input), _, 'Symbol),
      InputMoleculeInfo(`a`, 'a, 5, ConstInputPattern(Right("input")), _, Symbol("scala.util.Either[Int,String]"))
      ) =>
    }

    result.info.toString shouldEqual "a(Right(input)) + bb('input) + ccc(List()) + ccc(List()) + ccc(List(1)) + ccc(List(1, 2, 3)) → bb('output) + ccc(List()) + ccc(List()) + ccc(List(1)) + ccc(List(1, 2, 3)) + a(Right(output))"
  }

  behavior of "guard conditions"

  it should "correctly recognize a trivial true guard condition" in {
    val a = m[Either[Int, String]]
    val bb = m[(Int, Option[Int])]

    val result = go { case a(Left(1)) + a(Right("input")) + bb((2, Some(3))) + bb((0, None)) if true => a(Right("output")); bb((1, None)) }
    result.info.guardPresence shouldEqual GuardAbsent

    result.info.toString shouldEqual "a(Left(1)) + a(Right(input)) + bb((0,None)) + bb((2,Some(3))) → a(Right(output)) + bb((1,None))"

    result.info.inputs.toList should matchPattern {
      case List(
      InputMoleculeInfo(`a`, 'a, 0, ConstInputPattern(Left(1)), _, _),
      InputMoleculeInfo(`a`, 'a, 1, ConstInputPattern(Right("input")), _, _),
      InputMoleculeInfo(`bb`, 'bb, 2, ConstInputPattern((2, Some(3))), _, _),
      InputMoleculeInfo(`bb`, 'bb, 3, ConstInputPattern((0, None)), _, _)
      ) =>
    }

  }

  it should "use parameterized types in simple guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(Int, Option[String])]

    val result = go { // ignore warning about "non-variable type argument"
      case a(xOpt) + bb(y) // ignore warning about "class M expects 2 patterns"
        if xOpt.isEmpty && y._2.isEmpty =>
    }
    result.info.guardPresence.noCrossGuards shouldEqual true
    result.info.guardPresence should matchPattern { case GuardPresent(None, Array()) => }

    result.info.inputs.toList should matchPattern {
      case List(
      InputMoleculeInfo(`a`, 'a, 0, SimpleVarInput('xOpt, Some(_)), _, _),
      InputMoleculeInfo(`bb`, 'bb, 1, SimpleVarInput('y, Some(_)), _, _)
      ) =>
    }
    result.info.toString shouldEqual "a(xOpt if ?) + bb(y if ?) → "
  }

  it should "use parameterized types and tuple in simple guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(List[Int], Option[String])]

    val result = go { // ignore warning about "non-variable type argument"
      case a(Some(x)) + bb((list, Some(y)))
        if x == 1 && list.isEmpty && y == "abc" =>
    }
    result.info.guardPresence.noCrossGuards shouldEqual true
    result.info.guardPresence should matchPattern { case GuardPresent(None, Array()) => }

    result.info.inputs.toList should matchPattern {
      case List(
      InputMoleculeInfo(`a`, 'a, 0, OtherInputPattern(_, List('x), false), _, _),
      InputMoleculeInfo(`bb`, 'bb, 1, OtherInputPattern(_, List('list, 'y), false), _, _)
      ) =>
    }
    result.info.toString shouldEqual "a(?x) + bb(?list,y) → "
  }

  it should "use parameterized types in cross-molecule guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(Int, Option[String])]

    val result = go { // ignore warnings about "non-variable type argument"
      case a(xOpt) + bb(y) // ignore warning about "class M expects 2 patterns"
        if xOpt.isEmpty || y._2.isEmpty =>
    }
    result.info.guardPresence.noCrossGuards shouldEqual false
    result.info.guardPresence should matchPattern { case GuardPresent(None, Array(CrossMoleculeGuard(Array(0, 1), Array('xOpt, 'y), _))) => }

    result.info.inputs.toList should matchPattern {
      case List(
      InputMoleculeInfo(`a`, 'a, 0, SimpleVarInput('xOpt, None), _, Symbol("Option[Int]")),
      InputMoleculeInfo(`bb`, 'bb, 1, SimpleVarInput('y, None), _, Symbol("(Int, Option[String])"))
      ) =>
    }
    result.info.toString shouldEqual "a(xOpt) + bb(y) if(xOpt,y) → "
  }

  it should "use parameterized types and tuple in cross-molecule guard condition" in {
    val a = m[Option[Int]]
    val bb = m[(List[Int], Option[String])]

    val result = go { // ignore warning about "non-variable type argument"
      case a(Some(x)) + bb((list, Some(y)))
        if x == 1 || list.isEmpty || y == "abc" =>
    }
    result.info.guardPresence.noCrossGuards shouldEqual false
    result.info.guardPresence should matchPattern { case GuardPresent(None, Array(CrossMoleculeGuard(Array(0, 1), Array('x, 'list, 'y), _))) => }

    result.info.inputs.toList should matchPattern {
      case List(
      InputMoleculeInfo(`a`, 'a, 0, OtherInputPattern(_, List('x), false), _, Symbol("Option[Int]")),
      InputMoleculeInfo(`bb`, 'bb, 1, OtherInputPattern(_, List('list, 'y), false), _, Symbol("(List[Int], Option[String])"))
      ) =>
    }
    result.info.toString shouldEqual "a(?x) + bb(?list,y) if(x,list,y) → "
  }

  it should "correctly simplify guard condition using true and false" in {
    val a = m[Int]
    val n = 10
    val result = go { case a(x) if false || (true && false) || !false || n > 0 => }

    result.info.guardPresence shouldEqual AllMatchersAreTrivial

    result.info.inputs.toList should matchPattern { case List(InputMoleculeInfo(`a`, 'a, 0, SimpleVarInput('x, None), _, 'Int)) => }
    result.info.toString shouldEqual "a(x) → "
  }

  it should "correctly recognize a guard condition with no variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) if 1 > n => }

    (result.info.guardPresence match {
      case GuardPresent(Some(staticGuard), Array()) => // `n` should not be among the guard variables
        staticGuard() shouldEqual false
        true
      case _ => false
    }) shouldEqual true

    result.info.inputs.toList should matchPattern { case List(InputMoleculeInfo(`a`, 'a, 0, SimpleVarInput('x, None), _, _)) => }
    result.info.toString shouldEqual "a(x) if(?) → "
  }

  it should "compute reaction info with condition matcher" in {
    val a = m[Int]
    val b = m[Int]

    val reaction = go { case a(1) + b(x) if x > 1 => }

    (reaction.info.inputs(1).flag match {
      case SimpleVarInput(v, Some(cond)) =>
        cond.isDefinedAt(1) shouldEqual false
        cond.isDefinedAt(2) shouldEqual true
        true
      case _ => false
    }) shouldEqual true
  }

  it should "compute reaction info with condition matcher on a compound type" in {
    val a = m[(Int, Int, Int, Int)]

    val reaction = go { case a((x, y, z, t)) if x > y => }

    reaction.info.guardPresence should matchPattern { case GuardPresent(None, Array()) => }

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

  behavior of "detecting irrefutable patterns"

  it should "compute reaction info with compound irrefutable matcher" in {
    val a = m[(Int, (Int, Int), Int)]

    val reaction = go { case a(x@(_, (y@_, z), t)) => }

    reaction.info.guardPresence should matchPattern { case AllMatchersAreTrivial => }
    reaction.info.inputs.head.flag should matchPattern { case OtherInputPattern(_, List('x, 'y, 'z, 't), true) => }
  }

  it should "compute reaction info with alternative irrefutable matcher" in {
    val a = m[(Int, Int)]

    val reaction = go { case a((_, _) | (1, 2)) => } // ignore warning about "class M expects 2 patterns to hold"

    reaction.info.guardPresence should matchPattern { case AllMatchersAreTrivial => }
    reaction.info.inputs.head.flag should matchPattern { case OtherInputPattern(_, List(), true) => }
  }

  it should "compute reaction info with tuple irrefutable matcher" in {
    val a = m[(Int, (Int, (Boolean, Boolean), (String, String, String)))]

    val reaction = go { case a((x, (_, (y, _), (z, _, t)))) => }

    reaction.info.guardPresence should matchPattern { case AllMatchersAreTrivial => }
    reaction.info.inputs.head.flag should matchPattern { case OtherInputPattern(_, List('x, 'y, 'z, 't), true) => }
  }

  it should "compute reaction info with tuple refutable matcher" in {
    val a = m[(Int, (Int, (Boolean, Boolean), (String, String, String)))]

    val reaction = go { case a((x, (_, (y, true), (z, _, t)))) => }

    reaction.info.guardPresence should matchPattern { case GuardAbsent => }
    reaction.info.inputs.head.flag should matchPattern { case OtherInputPattern(_, List('x, 'y, 'z, 't), false) => }
  }

  it should "recognize irrefutable case class matcher" in {
    sealed trait MyTrait
    case class A(x: Int) extends MyTrait
    A(1).x shouldEqual 1

    val a = m[MyTrait]
    val reaction = go { case a(A(x)) => }

    reaction.info.guardPresence should matchPattern { case AllMatchersAreTrivial => }
    reaction.info.inputs.head.flag should matchPattern { case OtherInputPattern(_, List('x), true) => }
  }

  it should "recognize refutable case class matcher" in {
    sealed trait MyTrait
    case class A(x: Int) extends MyTrait
    case class B(y: Int) extends MyTrait
    A(1).x shouldEqual 1
    B(1).y shouldEqual 1

    val a = m[MyTrait]
    val reaction = go { case a(A(x)) => }
    reaction.info.guardPresence should matchPattern { case GuardAbsent => }
    reaction.info.inputs.head.flag should matchPattern { case OtherInputPattern(_, List('x), false) => }
  }

  behavior of "guard conditions without cross-molecule dependencies"

  it should "recognize a guard condition with captured non-molecule variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(xyz) if xyz > n => }

    result.info.guardPresence should matchPattern { case GuardPresent(None, Array()) => // `n` should not be among the guard variables
    }
    result.info.toString shouldEqual "a(xyz if ?) → "
    (result.info.inputs.toList match {
      case List(InputMoleculeInfo(`a`, 'a, 0, SimpleVarInput('xyz, Some(cond)), _, _)) =>
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
      case GuardPresent(Some(staticGuard), Array()) =>
        staticGuard() shouldEqual false
        true
      case _ => false
    }) shouldEqual true
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) if(?) → "
  }

  it should "correctly handle a guard condition with ||" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if (1 > n || x > n) && y > n => }

    result.info.guardPresence should matchPattern { case GuardPresent(None, Array()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) → "
  }

  it should "perform Boolean transformation on a guard condition to eliminate cross dependency" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n && y > n || 1 > n => }

    result.info.guardPresence should matchPattern { case GuardPresent(None, Array()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) → "
  }

  it should "correctly handle a guard condition with nontrivial unapply matcher" in {
    val a = m[(Int, Int, Int)]

    val result = go { case a((x, y, z)) if x > y => }

    result.info.guardPresence should matchPattern { case GuardPresent(None, Array()) => }
    result.info.toString shouldEqual "a(?x,y,z) → "
  }

  it should "compile a guard that references a variable via library functions" in {
    val c = m[(Int, Array[Int])]
    val d = m[Array[Int]]

    val reaction1 = go { case c((_, arr)) if arr.nonEmpty => }
    reaction1.info.inputs.head should matchPattern { case InputMoleculeInfo(`c`, 'c, 0, OtherInputPattern(_, List('arr), false), _, Symbol("(Int, Array[Int])")) => }

    val reaction2 = go { case d(arr) if arr.nonEmpty => }
    reaction2.info.inputs.head should matchPattern { case InputMoleculeInfo(`d`, 'd, 0, SimpleVarInput('arr, Some(_)), _, Symbol("Array[Int]")) => }
  }

  behavior of "cross-molecule guards"

  it should "handle a cross-molecule guard condition with missing types" in {
    val a = m[Int]

    val result = go { case a(x) + a(y) if x > y => }

    (result.info.guardPresence match {
      case GuardPresent(None, Array(CrossMoleculeGuard(Array(0, 1), Array('x, 'y), cond))) =>
        cond.isDefinedAt(List(0, 0)) shouldEqual false
        cond.isDefinedAt(List(1, 0)) shouldEqual true
        true
      case _ => false
    }) shouldEqual true

    result.info.toString shouldEqual "a(x) + a(y) if(x,y) → "
  }

  it should "handle a guard condition with cross dependency that cannot be eliminated by Boolean transformations" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n + y => }

    (result.info.guardPresence match {
      case GuardPresent(None, Array(CrossMoleculeGuard(Array(0, 1), Array('x, 'y), cond))) =>
        cond.isDefinedAt(List(10, 0)) shouldEqual false
        cond.isDefinedAt(List(11, 0)) shouldEqual true
        true
      case _ => false
    }) shouldEqual true

    result.info.toString shouldEqual "a(x) + a(y) if(x,y) → "
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
      case GuardPresent(Some(staticGuard), Array(CrossMoleculeGuard(Array(0, 4), Array('t, 'p), cond))) =>
        cond.isDefinedAt(List(1, (2, Some(3)))) shouldEqual true
        cond.isDefinedAt(List(2, (2, Some(3)))) shouldEqual false
        staticGuard() shouldEqual true
        true
      case _ => false
    }) shouldEqual true

    result.info.toString shouldEqual "a(1) + a(y if ?) + a(p) + bb(?z) + bb(?t,qwerty) + f/B(_) if(t,p) → "
  }

  it should "correctly flatten a guard condition with complicated nested clauses" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]

    val result = go {
      case a(p) + a(y) + a(1) + bb((1, z)) + bb((t, Some(q))) if p == 3 && ((t == q && y > 0) && q > 0) && (t == p && y == q) =>
    }
    result.info.guardPresence should matchPattern {
      case GuardPresent(None, Array(CrossMoleculeGuard(Array(0, 4), Array('t, 'p), _), CrossMoleculeGuard(Array(1, 4), Array('y, 'q), _))) =>
    }
    result.info.guardPresence.toString shouldEqual "GuardPresent(None, [CrossMoleculeGuard([0,4], ['t,'p]); CrossMoleculeGuard([1,4], ['y,'q])])"
    result.info.toString shouldEqual "a(1) + a(p if ?) + a(y if ?) + bb(?z) + bb(?t,q) if(t,p,y,q) → "
  }

  it should "simplify a guard with an if clause and a negation of one term" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n && (if (y > n) 1 > n else !(x == y)) => }

    result.info.guardPresence should matchPattern { case GuardPresent(None, Array(CrossMoleculeGuard(Array(0, 1), Array('y, 'x), _))) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) if(y,x) → "
  }

  it should "simplify a guard with an if clause into no cross guard" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n || (if (!false) 1 > n else x == y) => }

    result.info.guardPresence should matchPattern { case GuardPresent(None, Array()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y) → "
  }

  it should "merge cross guard with simple pattern variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n && x > y && y + 2 > x ⇒ }

    result.info.guardPresence should matchPattern { case GuardPresent(None, Array(CrossMoleculeGuard(Array(0, 1), Array('x, 'y), _))) ⇒ }
    result.info.toString shouldEqual "a(x if ?) + a(y) if(x,y) → "
  }

  it should "merge cross guard with multiple pattern variables" in {
    val a = m[(Int, Int)]

    val n = 10

    val result = go { case a((p, q)) + a((x, y)) if x > n && x > y && x + 1 > p + 1 && y > q ⇒ }

    result.info.guardPresence should matchPattern { case GuardPresent(None, Array(CrossMoleculeGuard(Array(0, 1), Array('x, 'p, 'y, 'q), _))) ⇒ }
    result.info.toString shouldEqual "a(?x,y) + a(p,q) if(x,p,y,q) → "
    (result.info.guardPresence match {
      case GuardPresent(None, Array(CrossMoleculeGuard(Array(0, 1), Array('x, 'p, 'y, 'q), cond))) ⇒
        cond.isDefinedAt(List((1, 2), (4, 3))) shouldEqual true // x > n is not part of the cross-molecule guard
        cond.isDefinedAt(List((1, 3), (4, 3))) shouldEqual false
        cond.isDefinedAt(List((4, 2), (4, 3))) shouldEqual false
        cond.isDefinedAt(List((1, 2), (3, 4))) shouldEqual true // x > y is not part of the cross-molecule guard
        cond.isDefinedAt(List((1, 3), (4, 3))) shouldEqual false
        true
      case _ ⇒ false
    }) shouldEqual true
  }

  it should "merge cross guard for Game of Life reaction" in {
    case class Cell(x: Int, y: Int, t: Int, state: Int, label: (Int, Int))
    val c = m[Cell]

    Cell(0, 0, 0, 0, (0, 0)).x shouldEqual 0

    // One reaction with one molecule `c` implements the entire Game of Life computation.
    // Shifted positions are represented by the "Cell#label" field.
    val result = go { case
      c(Cell(x0, y0, t0, state0, (0, 0))) +
        c(Cell(x1, y1, t1, state1, (1, 0))) +
        c(Cell(x2, y2, t2, state2, (-1, 0))) +
        c(Cell(x3, y3, t3, state3, (0, 1))) +
        c(Cell(x4, y4, t4, state4, (1, 1))) +
        c(Cell(x5, y5, t5, state5, (-1, 1))) +
        c(Cell(x6, y6, t6, state6, (0, -1))) +
        c(Cell(x7, y7, t7, state7, (1, -1))) +
        c(Cell(x8, y8, t8, state8, (-1, -1)))
      if x0 == x1 && x0 == x2 && x0 == x3 && x0 == x4 && x0 == x5 && x0 == x6 && x0 == x7 && x0 == x8 &&
        y0 == y1 && y0 == y2 && y0 == y3 && y0 == y4 && y0 == y5 && y0 == y6 && y0 == y7 && y0 == y8 &&
        t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 ⇒
    }
    result.info.guardPresence should matchPattern { case GuardPresent(None, Array(
    CrossMoleculeGuard(Array(0, 1), Array('x0, 'x1, 'y0, 'y1, 't0, 't1), _)
    , CrossMoleculeGuard(Array(0, 2), Array('x0, 'x2, 'y0, 'y2, 't0, 't2), _)
    , CrossMoleculeGuard(Array(0, 3), Array('x0, 'x3, 'y0, 'y3, 't0, 't3), _)
    , CrossMoleculeGuard(Array(0, 4), Array('x0, 'x4, 'y0, 'y4, 't0, 't4), _)
    , CrossMoleculeGuard(Array(0, 5), Array('x0, 'x5, 'y0, 'y5, 't0, 't5), _)
    , CrossMoleculeGuard(Array(0, 6), Array('x0, 'x6, 'y0, 'y6, 't0, 't6), _)
    , CrossMoleculeGuard(Array(0, 7), Array('x0, 'x7, 'y0, 'y7, 't0, 't7), _)
    , CrossMoleculeGuard(Array(0, 8), Array('x0, 'x8, 'y0, 'y8, 't0, 't8), _)
    )) ⇒
    }
  }

  it should "handle reactions with conditionals and cross-conditionals with constants" in {
    val a = m[Option[Int]]
    val bb = m[Option[Int]]
    val f = b[Unit, Boolean]

    withPool(FixedPool(4)) { tp ⇒
      site(tp)(go { case a(Some(px@(1))) + bb(py@Some(y)) + f(_, r) // ignore warning about "non-variable type argument Int"
        if py.get > px ⇒ r(true)
      })
      // TODO: fix type breakage if we write z + px.get instead of z + x: the reason is that `px` is inferred to be Some[Any] instead of Some[Int]

      a(Some(1)) + bb(Some(2))
      f.timeout()(1.second)
    }.get shouldEqual Some(true)
  }

  behavior of "guard conditions with repeated molecules"

  it should "correctly order input molecules and run reaction" in {
    val a = m[Int]
    val f = b[Unit, Unit]

    val status = withPool(FixedPool(4)) { tp =>
      site(tp)(go { case a(x) + a(y) + f(_, r) if x == y + 1 => r() })

      (1 to 3).foreach(_ => a(0) + a(1))
      f() shouldEqual (())
      f() shouldEqual (())
      f() shouldEqual (())
    }
    if (status.isFailure) println(s"Test failed with message: ${status.failed.get.getMessage}")
    status.isFailure shouldEqual false
  }

  it should "correctly apply conditions to values" in {
    val a = m[Int]
    val f = b[Unit, Unit]

    val result = go { case a(x) + a(y) + a(z) + f(_, r) if x == y + 1 && y == z + 1 => r() }
    (result.info.guardPresence match {
      case GuardPresent(None, Array(c1, c2)) =>
        c1.indices.toList shouldEqual List(0, 1)
        c1.cond.isDefinedAt(List(2, 1)) shouldEqual true
        c1.cond.isDefinedAt(List(2, 2)) shouldEqual false
        c2.indices.toList shouldEqual List(1, 2)
        c2.cond.isDefinedAt(List(0, 0)) shouldEqual false
        true
      case _ => false
    }) shouldEqual true

  }

  it should "handle reactions with constant values and cross-conditionals on repeated molecules" in {
    val a = m[Int]
    val f = b[Unit, Boolean]

    withPool(FixedPool(4)) { tp ⇒
      site(tp)(go { case a(1) + a(y) + a(z) + f(_, r) if y > z ⇒ r(true) })

      (1 to 3).foreach { i ⇒ a(i) }
      f.timeout()(1.second)
    }.get shouldEqual Some(true)
  }

  it should "handle reactions with conditionals and cross-conditionals on repeated molecules" in {
    val a = m[Option[Int]]
    val f = b[Unit, Boolean]

    withPool(FixedPool(4)) { tp ⇒
      site(tp)(go { case a(Some(1)) + a(Some(y)) + a(Some(z)) + f(_, r) if y > z && z > 1 ⇒ r(true) })
      (1 to 3).foreach(_ ⇒ (1 to 3).foreach { i ⇒ a(Some(i)) })
      (1 to 3).map(_ ⇒ f.timeout()(1.second)).map(_.get).reduce(_ && _)
    }.get shouldEqual true
  }

  it should "handle reactions with conditionals and cross-conditionals on repeated molecules with constants" in {
    val a = m[Option[Int]]
    val f = b[Unit, Boolean]

    withPool(FixedPool(4)) { tp ⇒
      site(tp)(go { case a(Some(px@(1))) + a(py@Some(y)) + a(Some(z)) + f(_, r) // ignore warning about "non-variable type argument Int"
        if py.get >= z + px ⇒ r(true)
      })
      // TODO: fix type breakage if we write z + px.get instead of z + x: the reason is that `px` is inferred to be Some[Any] instead of Some[Int]

      (1 to 3).foreach { i ⇒ a(Some(i)) }
      f.timeout()(1.second)
    }.get shouldEqual Some(true)
  }

}
