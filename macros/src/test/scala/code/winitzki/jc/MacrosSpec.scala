package code.winitzki.jc

import org.scalatest.{FlatSpec, Matchers}
import JoinRun._
import Macros._

class MacrosSpec extends FlatSpec with Matchers {

  it should "compute invocation names for molecule injectors" in {
    val a = jA[Int]

    a.toString shouldEqual "a"

    val s = jS[Map[(Boolean,Unit),Seq[Int]], Option[List[(Int,Option[Map[Int,String]])]]] // complicated type

    s.toString shouldEqual "s/S"
  }

  it should "inspect reaction body" in {
    val a = jA[Int]
    val b = jA[(Int,Int)]
    val s = jS[Unit,Int]

    val result = findInputs({ case a(x) + a(y) + b((1,z)) + s(_, r) => a(x+1) + r(x) })

    println(s"debug: got $result")
    // desugared expression tree:
    //  Expr[Nothing](((x0$1: code.winitzki.jc.JoinRun.UnapplyArg) => x0$1 match {
    //    case JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (a.unapply(<unapply-selector>) <unapply> ((x @ _)), a.unapply(<unapply-selector>) <unapply> ((y @ _))), b.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Int)(Int, Int)(1, (z @ _)))), s.unapply(<unapply-selector>) <unapply> (_, (r @ _))) => JoinRun.JoinableUnit(a.apply(x.+(1))).+(r.apply(x))
    //  }))
    // raw tree:
    // Expr(Function(List(ValDef(Modifiers(PARAM | SYNTHETIC), TermName("x0$1"), TypeTree(), EmptyTree)), Match(Ident(TermName("x0$1")), List(CaseDef(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("x"), Ident(termNames.WILDCARD)))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("y"), Ident(termNames.WILDCARD)))))), UnApply(Apply(Select(Ident(TermName("b")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Literal(Constant(1)), Bind(TermName("z"), Ident(termNames.WILDCARD)))))))), UnApply(Apply(Select(Ident(TermName("s")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD), Bind(TermName("r"), Ident(termNames.WILDCARD)))))), EmptyTree, Apply(Select(Apply(Select(Ident(code.winitzki.jc.JoinRun), TermName("JoinableUnit")), List(Apply(Select(Ident(TermName("a")), TermName("apply")), List(Apply(Select(Ident(TermName("x")), TermName("$plus")), List(Literal(Constant(1)))))))), TermName("$plus")), List(Apply(Select(Ident(TermName("r")), TermName("apply")), List(Ident(TermName("x")))))))))))


    //    result shouldEqual "blah"
  }
}
