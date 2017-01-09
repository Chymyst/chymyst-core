package sample

import MacroTest._
import org.scalatest.{FlatSpec, Matchers}

class MacroTestSpec extends FlatSpec with Matchers {

  behavior of "macro test"

  it should "display raw desugared tree" in {
    val tree = rawTree({ case Blob((1, Some(x)), y) if x > 0 => x + y }: PartialFunction[Blob, Any])

    tree shouldEqual "Typed(Typed(Block(List(ClassDef(Modifiers(FINAL | SYNTHETIC), TypeName(\"$anonfun\"), List(), Template(List(TypeTree(), TypeTree()), noSelfType, List(DefDef(Modifiers(), termNames.CONSTRUCTOR, List(), List(List()), TypeTree(), Block(List(Apply(Select(Super(This(TypeName(\"$anonfun\")), typeNames.EMPTY), termNames.CONSTRUCTOR), List())), Literal(Constant(())))), DefDef(Modifiers(OVERRIDE | FINAL | METHOD), TermName(\"applyOrElse\"), List(TypeDef(Modifiers(DEFERRED | PARAM), TypeName(\"A1\"), List(), TypeTree().setOriginal(TypeBoundsTree(TypeTree(), TypeTree()))), TypeDef(Modifiers(DEFERRED | PARAM), TypeName(\"B1\"), List(), TypeTree().setOriginal(TypeBoundsTree(TypeTree(), TypeTree())))), List(List(ValDef(Modifiers(PARAM | SYNTHETIC | TRIEDCOOKING), TermName(\"x1\"), TypeTree().setOriginal(Ident(TypeName(\"A1\"))), EmptyTree), ValDef(Modifiers(PARAM | SYNTHETIC), TermName(\"default\"), TypeTree().setOriginal(AppliedTypeTree(Select(This(TypeName(\"scala\")), scala.Function1), List(TypeTree().setOriginal(Ident(TypeName(\"A1\"))), TypeTree().setOriginal(Ident(TypeName(\"B1\")))))), EmptyTree))), TypeTree(), Match(Typed(Typed(TypeApply(Select(Ident(TermName(\"x1\")), TermName(\"asInstanceOf\")), List(TypeTree())), TypeTree()), TypeTree().setOriginal(Annotated(Apply(Select(New(Select(Ident(scala), scala.unchecked)), termNames.CONSTRUCTOR), List()), Typed(TypeApply(Select(Ident(TermName(\"x1\")), TermName(\"asInstanceOf\")), List(TypeTree())), TypeTree())))), List(CaseDef(Apply(TypeTree().setOriginal(Ident(sample.Blob)), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Literal(Constant(1)), Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Bind(TermName(\"x\"), Ident(termNames.WILDCARD)))))), Bind(TermName(\"y\"), Ident(termNames.WILDCARD)))), Apply(Select(Ident(TermName(\"x\")), TermName(\"$greater\")), List(Literal(Constant(0)))), Apply(Select(Ident(TermName(\"x\")), TermName(\"$plus\")), List(Ident(TermName(\"y\"))))), CaseDef(Bind(TermName(\"defaultCase$\"), Ident(termNames.WILDCARD)), EmptyTree, Apply(Select(Ident(TermName(\"default\")), TermName(\"apply\")), List(Ident(TermName(\"x1\")))))))), DefDef(Modifiers(FINAL | METHOD), TermName(\"isDefinedAt\"), List(), List(List(ValDef(Modifiers(PARAM | SYNTHETIC | TRIEDCOOKING), TermName(\"x1\"), TypeTree(), EmptyTree))), TypeTree(), Match(Typed(Typed(TypeApply(Select(Ident(TermName(\"x1\")), TermName(\"asInstanceOf\")), List(TypeTree())), TypeTree()), TypeTree().setOriginal(Annotated(Apply(Select(New(Select(Ident(scala), scala.unchecked)), termNames.CONSTRUCTOR), List()), Typed(TypeApply(Select(Ident(TermName(\"x1\")), TermName(\"asInstanceOf\")), List(TypeTree())), TypeTree())))), List(CaseDef(Apply(TypeTree().setOriginal(Ident(sample.Blob)), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Literal(Constant(1)), Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Bind(TermName(\"x\"), Ident(termNames.WILDCARD)))))), Bind(TermName(\"y\"), Ident(termNames.WILDCARD)))), Apply(Select(Ident(TermName(\"x\")), TermName(\"$greater\")), List(Literal(Constant(0)))), Literal(Constant(true))), CaseDef(Bind(TermName(\"defaultCase$\"), Ident(termNames.WILDCARD)), EmptyTree, Literal(Constant(false)))))))))), Apply(Select(New(Ident(TypeName(\"$anonfun\"))), termNames.CONSTRUCTOR), List())), TypeTree()), TypeTree().setOriginal(AppliedTypeTree(Select(Ident(scala), scala.PartialFunction), List(TypeTree().setOriginal(Ident(sample.Blob)), TypeTree().setOriginal(Select(Ident(scala), scala.Any))))))"
  }

  it should "parse a partial function Int => Any" in {
    val testPf: Simple = { case x => x + 1 }
    testPf(2) shouldEqual 3 // passes

    val result = no_problem({ case x => x + 1 })
    result(2) shouldEqual 3

  }

//  it should "parse a partial function Blah => Any" in {
//    val blob = Blah(1, 2)
//    val testPf: Medium = { case Blah(x, y) => x + y }
//    testPf(blob) shouldEqual 3 // passes
//
//    val result = medium_problem({ case Blah(x, y) => x + y })
//    result(blob) shouldEqual 3
//  }

  it should "parse a partial function T => Any with T = Int" in {
    type T = Int
    val blob: T = 2
    val testPf: PartialFunction[T,Any] = { case x => x + 1 }
    testPf(blob) shouldEqual 3 // passes

    val result = par_problem[T]({ case x => x + 1 })
    result(blob) shouldEqual 3
  }
/*
  it should "parse a partial function T => Any with T = Blah" in {
    type T = Blah
    val blob: T = Blah(1, 2)
    val testPf: PartialFunction[T,Any] = { case Blah(x,y) => x + y }
    testPf(blob) shouldEqual 3 // passes

    val result = par_problem[T]({ case Blah(x,y) => x + y })
    result(blob) shouldEqual 3
  }

  it should "parse a partial function Blob => Any" in {
    val blob = Blob((1, Some(2)), 3)
    val testPf: Complicated = { case Blob((1, Some(x)), y) => x + y }
    testPf(blob) shouldEqual 5 // passes

    val result = problem({ case Blob((1, Some(x)), y) => x + y })
    result(blob) shouldEqual 5
  }

  it should "parse the partial function with guard" in {
    val result = problem({ case Blob((1, Some(x)), y) if x > 0 => x + y })

    val blob = Blob((1, Some(2)), 3)

    result.res(blob) shouldEqual 5

  }
*/
//  it should "parse a partial function and create two identical partial functions" in {
//    val result = problem({ case Blob((1, Some(x)), y) if x > 0 => x + y })
//
//    result match {
//      case ResultBlob(left, all) =>
//    }
//  }

//  it should "parse the partial function and create new partial functions" in {
//    val result = problem({ case Blob((1, Some(x)), y) if x > 0 => x + y }: PartialFunction[Any, Any])
//
//    result match {
//      case ResultBlob(left, all) =>
//    }
//  }
}