package code.winitzki.jc

import org.scalatest.{FlatSpec, Matchers}
import JoinRun._
import Macros._

class MacrosSpec extends FlatSpec with Matchers {

  behavior of "JoinRun macro utilities"

  it should "compute invocation names for molecule injectors" in {
    val a = m[Int]

    a.toString shouldEqual "a"

    val s = b[Map[(Boolean,Unit),Seq[Int]], Option[List[(Int,Option[Map[Int,String]])]]] // complicated type

    s.toString shouldEqual "s/B"
  }

  it should "inspect reaction body" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit,Int]
    val bb = m[(Int, Option[Int])]

    // reaction contains all kinds of pattern-matching constructions, blocking molecule in a guard, and unit values in molecules
    val result = findMolecules{
      case a(x) => a(x+1)
      case a(p) + a(y) + a(1) + bb(_) + bb((1,z)) + bb((_, None)) + bb((t, Some(q))) + s(_, r) if y > 0 && s() > 0 => a(p+1) + qq() + r(p)
    }

    println(s"debug: got $result")
    // desugared expression tree:
    //  Expr[Nothing](((x0$1: code.winitzki.jc.JoinRun.UnapplyArg) => x0$1 match {
    // case JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (a.unapply(<unapply-selector>) <unapply> ((p @ _)), a.unapply(<unapply-selector>) <unapply> ((y @ _))), a.unapply(<unapply-selector>) <unapply> (1)), b.unapply(<unapply-selector>) <unapply> (_)), b.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])(1, (z @ _)))), b.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])(_, scala.None))), b.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])((t @ _), (x: Int)Some[Int]((q @ _))))), s.unapply(<unapply-selector>) <unapply> (_, (r @ _))) => JoinRun.JoinableUnit(a.apply(p.+(1))).+(r.apply(p))
//      }))
    // raw tree:
    // Expr(Function(List(ValDef(Modifiers(PARAM | SYNTHETIC), TermName("x0$1"), TypeTree(), EmptyTree)), Match(Ident(TermName("x0$1")), List(CaseDef(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("p"), Ident(termNames.WILDCARD)))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("y"), Ident(termNames.WILDCARD)))))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Literal(Constant(1)))))), UnApply(Apply(Select(Ident(TermName("b")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD))))), UnApply(Apply(Select(Ident(TermName("b")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Literal(Constant(1)), Bind(TermName("z"), Ident(termNames.WILDCARD)))))))), UnApply(Apply(Select(Ident(TermName("b")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Ident(termNames.WILDCARD), Select(Ident(scala), scala.None))))))), UnApply(Apply(Select(Ident(TermName("b")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Bind(TermName("t"), Ident(termNames.WILDCARD)), Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Bind(TermName("q"), Ident(termNames.WILDCARD)))))))))), UnApply(Apply(Select(Ident(TermName("s")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD), Bind(TermName("r"), Ident(termNames.WILDCARD)))))), EmptyTree, Apply(Select(Apply(Select(Ident(code.winitzki.jc.JoinRun), TermName("JoinableUnit")), List(Apply(Select(Ident(TermName("a")), TermName("apply")), List(Apply(Select(Ident(TermName("p")), TermName("$plus")), List(Literal(Constant(1)))))))), TermName("$plus")), List(Apply(Select(Ident(TermName("r")), TermName("apply")), List(Ident(TermName("p")))))))))))

    //    result shouldEqual "blah"

    // example tree, somewhat trimmed. More precisely, we have a List() of CaseDef's.
    /*
    val _ = CaseDef(
          UnApply(
            Apply(Select(Select(Ident("code.winitzki.jc.JoinRun"), "code.winitzki.jc.JoinRun.$plus"), TermName("unapply")),
              List(Ident(TermName("<unapply-selector>")))
            ),
            List(UnApply(
              Apply(Select(Select(Ident("code.winitzki.jc.JoinRun"), "code.winitzki.jc.JoinRun.$plus"), TermName("unapply")),
                List(Ident(TermName("<unapply-selector>")))
              ),
              List(UnApply(Apply(Select(Select(Ident("code.winitzki.jc.JoinRun"), "code.winitzki.jc.JoinRun.$plus"), TermName("unapply")),
                List(Ident(TermName("<unapply-selector>")))
              ),
                List(UnApply(Apply(Select(Select(Ident("code.winitzki.jc.JoinRun"), "code.winitzki.jc.JoinRun.$plus"), TermName("unapply")),
                  List(Ident(TermName("<unapply-selector>")))
                ),
                  List(UnApply(Apply(Select(Select(Ident("code.winitzki.jc.JoinRun"), "code.winitzki.jc.JoinRun.$plus"), TermName("unapply")),
                    List(Ident(TermName("<unapply-selector>")))
                  ),
                    List(UnApply(Apply(Select(Select(Ident("code.winitzki.jc.JoinRun"), "code.winitzki.jc.JoinRun.$plus"), TermName("unapply")),
                      List(Ident(TermName("<unapply-selector>")))
                    ),
                      List(UnApply(Apply(Select(Select(Ident("code.winitzki.jc.JoinRun"), "code.winitzki.jc.JoinRun.$plus"), TermName("unapply")),
                        List(Ident(TermName("<unapply-selector>")))
                      ),
                        List(UnApply(Apply(
                          Select(Ident(TermName("a")), TermName("unapply")),
                          List(Ident(TermName("<unapply-selector>")))
                        ),
                          List(Bind(TermName("p"), Ident(termNames.WILDCARD)))
                        ),
                          UnApply(Apply(
                            Select(Ident(TermName("a")), TermName("unapply")),
                            List(Ident(TermName("<unapply-selector>")))
                          ),
                            List(Bind(TermName("y"), Ident(termNames.WILDCARD)))
                          )
                        )
                      ),
                        UnApply(Apply(
                          Select(Ident(TermName("a")), TermName("unapply")),
                          List(Ident(TermName("<unapply-selector>")))
                        ),
                          List(Literal(Constant(1)))
                        )
                      )
                    ),
                      UnApply(Apply(Select(Ident(TermName("b")), TermName("unapply")),
                        List(Ident(TermName("<unapply-selector>")))
                      ),
                        List(Ident(termNames.WILDCARD))
                      )
                    )
                  ),
                    UnApply(Apply(Select(Ident(TermName("b")), TermName("unapply")),
                      List(Ident(TermName("<unapply-selector>")))
                    ),
                      List(Apply(TypeTree().setOriginal(Select(Ident("scala"), "scala.Tuple2")),
                          List(Literal(Constant(1)), Bind(TermName("z"), Ident(termNames.WILDCARD)))
                        )
                      )
                    )
                  )
                ),
                  UnApply(Apply(Select(Ident(TermName("b")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident("scala"), "scala.Tuple2")), List(Ident(termNames.WILDCARD), Select(Ident("scala"), "scala.None"))))))), UnApply(Apply(Select(Ident(TermName("b")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident("scala"), "scala.Tuple2")), List(Bind(TermName("t"), Ident(termNames.WILDCARD)), Apply(TypeTree().setOriginal(Select(Ident("scala"), "scala.Some")), List(Bind(TermName("q"), Ident(termNames.WILDCARD)))))))))), UnApply(Apply(Select(Ident(TermName("s")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD), Bind(TermName("r"), Ident(termNames.WILDCARD)))))), EmptyTree, Apply(Select(Apply(Select(Ident("code.winitzki.jc.JoinRun"), TermName("JoinableUnit")), List(Apply(Select(Ident(TermName("a")), TermName("apply")), List(Apply(Select(Ident(TermName("p")), TermName("$plus")), List(Literal(Constant(1)))))))), TermName("$plus")), List(Apply(Select(Ident(TermName("r")), TermName("apply")), List(Ident(TermName("p")))))))

*/

    /*
    * Partial function typing produces the following tree:

    Typed(Block(List(ClassDef(Modifiers(FINAL | SYNTHETIC), TypeName("$anonfun"), List(), Template(List(TypeTree(), TypeTree()), noSelfType, List(DefDef(Modifiers(), termNames.CONSTRUCTOR, List(), List(List()), TypeTree(), Block(List(Apply(Select(Super(This(TypeName("$anonfun")), typeNames.EMPTY), termNames.CONSTRUCTOR), List())), Literal(Constant(())))),

    DefDef(
      Modifiers(OVERRIDE | FINAL | METHOD),
      TermName("applyOrElse"),
      List(TypeDef(Modifiers(DEFERRED | PARAM), TypeName("A1"), List(), TypeTree().setOriginal(TypeBoundsTree(TypeTree(), TypeTree()))), TypeDef(Modifiers(DEFERRED | PARAM), TypeName("B1"), List(), TypeTree().setOriginal(TypeBoundsTree(TypeTree(), TypeTree())))),
      List(List(ValDef(Modifiers(PARAM | SYNTHETIC | TRIEDCOOKING), TermName("x1"), TypeTree().setOriginal(Ident(TypeName("A1"))), EmptyTree), ValDef(Modifiers(PARAM | SYNTHETIC), TermName("default"), TypeTree().setOriginal(AppliedTypeTree(Select(This(TypeName("scala")), scala.Function1), List(TypeTree().setOriginal(Ident(TypeName("A1"))), TypeTree().setOriginal(Ident(TypeName("B1")))))), EmptyTree))),
     TypeTree(),

    Match(
      Typed(
        Typed(TypeApply(Select(Ident(TermName("x1")), TermName("asInstanceOf")), List(TypeTree())), TypeTree()),
        TypeTree.setOriginal(Annotated(Apply(Select(New(Select(Ident(scala), scala.unchecked)), termNames.CONSTRUCTOR), List()), Typed(TypeApply(Select(Ident(TermName("x1")), TermName("asInstanceOf")), List(TypeTree())), TypeTree())))
        ),

    List(

    CaseDef(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("p"), Ident(termNames.WILDCARD)))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("y"), Ident(termNames.WILDCARD)))))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Literal(Constant(1)))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Literal(Constant(1)), Bind(TermName("z"), Ident(termNames.WILDCARD)))))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Ident(termNames.WILDCARD), Select(Ident(scala), scala.None))))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Bind(TermName("t"), Ident(termNames.WILDCARD)), Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Bind(TermName("q"), Ident(termNames.WILDCARD)))))))))), UnApply(Apply(Select(Ident(TermName("s")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD), Bind(TermName("r"), Ident(termNames.WILDCARD)))))), Apply(Select(Apply(Select(Ident(TermName("y")), TermName("$greater")), List(Literal(Constant(0)))), TermName("$amp$amp")), List(Apply(Select(Apply(Select(Ident(TermName("s")), TermName("apply")), List(Literal(Constant(())))), TermName("$greater")), List(Literal(Constant(0)))))), Apply(Select(Apply(Select(Ident(code.winitzki.jc.JoinRun), TermName("InjectMultiple")), List(Apply(Select(Apply(Select(Ident(code.winitzki.jc.JoinRun), TermName("InjectMultiple")), List(Apply(Select(Ident(TermName("a")), TermName("apply")), List(Apply(Select(Ident(TermName("p")), TermName("$plus")), List(Literal(Constant(1)))))))), TermName("$plus")), List(Apply(Select(Ident(TermName("qq")), TermName("apply")), List(Literal(Constant(())))))))), TermName("$plus")), List(Apply(Select(Ident(TermName("r")), TermName("apply")), List(Ident(TermName("p"))))))), CaseDef(Bind(TermName("defaultCase$"), Ident(termNames.WILDCARD)), EmptyTree, Apply(Select(Ident(TermName("default")), TermName("apply")), List(Ident(TermName("x1")))))))),

    DefDef(Modifiers(FINAL | METHOD), TermName("isDefinedAt"), List(), List(List(ValDef(Modifiers(PARAM | SYNTHETIC | TRIEDCOOKING), TermName("x1"), TypeTree(), EmptyTree))), TypeTree(),

    Match(Typed(Typed(TypeApply(Select(Ident(TermName("x1")), TermName("asInstanceOf")), List(TypeTree())), TypeTree()), TypeTree().setOriginal(Annotated(Apply(Select(New(Select(Ident(scala), scala.unchecked)), termNames.CONSTRUCTOR), List()), Typed(TypeApply(Select(Ident(TermName("x1")), TermName("asInstanceOf")), List(TypeTree())), TypeTree())))), List(CaseDef(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("p"), Ident(termNames.WILDCARD)))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("y"), Ident(termNames.WILDCARD)))))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Literal(Constant(1)))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Literal(Constant(1)), Bind(TermName("z"), Ident(termNames.WILDCARD)))))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Ident(termNames.WILDCARD), Select(Ident(scala), scala.None))))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Bind(TermName("t"), Ident(termNames.WILDCARD)), Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Bind(TermName("q"), Ident(termNames.WILDCARD)))))))))), UnApply(Apply(Select(Ident(TermName("s")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD), Bind(TermName("r"), Ident(termNames.WILDCARD)))))), Apply(Select(Apply(Select(Ident(TermName("y")), TermName("$greater")), List(Literal(Constant(0)))), TermName("$amp$amp")), List(Apply(Select(Apply(Select(Ident(TermName("s")), TermName("apply")), List(Literal(Constant(())))), TermName("$greater")), List(Literal(Constant(0)))))), Literal(Constant(true))), CaseDef(Bind(TermName("defaultCase$"), Ident(termNames.WILDCARD)), EmptyTree, Literal(Constant(false)))))))))), Apply(Select(New(Ident(TypeName("$anonfun"))), termNames.CONSTRUCTOR), List())), TypeTree())


which is equivalent to:

({
  @SerialVersionUID(value = 0) final <synthetic> class $anonfun extends scala.runtime.AbstractPartialFunction[code.winitzki.jc.JoinRun.UnapplyArg,Unit] with Serializable {
    def <init>(): <$anon: code.winitzki.jc.JoinRun.UnapplyArg => Unit> = {
      $anonfun.super.<init>();
      ()
    };
    final override def applyOrElse[A1 <: code.winitzki.jc.JoinRun.UnapplyArg, B1 >: Unit](x1: A1, default: A1 => B1): B1 = ((x1.asInstanceOf[code.winitzki.jc.JoinRun.UnapplyArg]: code.winitzki.jc.JoinRun.UnapplyArg): code.winitzki.jc.JoinRun.UnapplyArg @unchecked) match {
      case a.unapply(<unapply-selector>) <unapply> ((x @ _)) => a.apply(x.+(1))
      case JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (a.unapply(<unapply-selector>) <unapply> ((p @ _)), a.unapply(<unapply-selector>) <unapply> ((y @ _))), a.unapply(<unapply-selector>) <unapply> (1)), bb.unapply(<unapply-selector>) <unapply> (_)), bb.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])(1, (z @ _)))), bb.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])(_, scala.None))), bb.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])((t @ _), (x: Int)Some[Int]((q @ _))))), s.unapply(<unapply-selector>) <unapply> (_, (r @ _))) if y.>(0).&&(s.apply(()).>(0)) => JoinRun.InjectMultiple(JoinRun.InjectMultiple(a.apply(p.+(1))).+(qq.apply(()))).+(r.apply(p))
      case (defaultCase$ @ _) => default.apply(x1)
    };
    final def isDefinedAt(x1: code.winitzki.jc.JoinRun.UnapplyArg): Boolean = ((x1.asInstanceOf[code.winitzki.jc.JoinRun.UnapplyArg]: code.winitzki.jc.JoinRun.UnapplyArg): code.winitzki.jc.JoinRun.UnapplyArg @unchecked) match {
      case a.unapply(<unapply-selector>) <unapply> ((x @ _)) => true
      case JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (a.unapply(<unapply-selector>) <unapply> ((p @ _)), a.unapply(<unapply-selector>) <unapply> ((y @ _))), a.unapply(<unapply-selector>) <unapply> (1)), bb.unapply(<unapply-selector>) <unapply> (_)), bb.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])(1, (z @ _)))), bb.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])(_, scala.None))), bb.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])((t @ _), (x: Int)Some[Int]((q @ _))))), s.unapply(<unapply-selector>) <unapply> (_, (r @ _))) if y.>(0).&&(s.apply(()).>(0)) => true
      case (defaultCase$ @ _) => false
    }
  };
  new $anonfun()
}: PartialFunction[code.winitzki.jc.JoinRun.UnapplyArg,Unit])




With plain function typing:

Function(List(ValDef(Modifiers(PARAM | SYNTHETIC), TermName("x0$1"), TypeTree(), EmptyTree)),

Match(Ident(TermName("x0$1")),

List(
CaseDef(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("p"), Ident(termNames.WILDCARD)))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("y"), Ident(termNames.WILDCARD)))))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Literal(Constant(1)))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Literal(Constant(1)), Bind(TermName("z"), Ident(termNames.WILDCARD)))))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Ident(termNames.WILDCARD), Select(Ident(scala), scala.None))))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Bind(TermName("t"), Ident(termNames.WILDCARD)), Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Bind(TermName("q"), Ident(termNames.WILDCARD)))))))))), UnApply(Apply(Select(Ident(TermName("s")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD), Bind(TermName("r"), Ident(termNames.WILDCARD)))))), Apply(Select(Apply(Select(Ident(TermName("y")), TermName("$greater")), List(Literal(Constant(0)))), TermName("$amp$amp")), List(Apply(Select(Apply(Select(Ident(TermName("s")), TermName("apply")), List(Literal(Constant(())))), TermName("$greater")), List(Literal(Constant(0)))))), Apply(Select(Apply(Select(Ident(code.winitzki.jc.JoinRun), TermName("InjectMultiple")), List(Apply(Select(Apply(Select(Ident(code.winitzki.jc.JoinRun), TermName("InjectMultiple")), List(Apply(Select(Ident(TermName("a")), TermName("apply")), List(Apply(Select(Ident(TermName("p")), TermName("$plus")), List(Literal(Constant(1)))))))), TermName("$plus")), List(Apply(Select(Ident(TermName("qq")), TermName("apply")), List(Literal(Constant(())))))))), TermName("$plus")), List(Apply(Select(Ident(TermName("r")), TermName("apply")), List(Ident(TermName("p"))))))))))


With two cases and plain function typing:

Function(
List(
 ValDef(Modifiers(PARAM | SYNTHETIC), TermName("x0$1"), TypeTree(), EmptyTree)
 ),

Match(
  Ident(TermName("x0$1")),

 List(
  CaseDef(UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("x"), Ident(termNames.WILDCARD)))), EmptyTree, Apply(Select(Ident(TermName("a")), TermName("apply")), List(Apply(Select(Ident(TermName("x")), TermName("$plus")), List(Literal(Constant(1))))))),

  CaseDef(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Select(Ident(code.winitzki.jc.JoinRun), code.winitzki.jc.JoinRun.$plus), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("p"), Ident(termNames.WILDCARD)))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Bind(TermName("y"), Ident(termNames.WILDCARD)))))), UnApply(Apply(Select(Ident(TermName("a")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Literal(Constant(1)))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Literal(Constant(1)), Bind(TermName("z"), Ident(termNames.WILDCARD)))))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Ident(termNames.WILDCARD), Select(Ident(scala), scala.None))))))), UnApply(Apply(Select(Ident(TermName("bb")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Tuple2)), List(Bind(TermName("t"), Ident(termNames.WILDCARD)), Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Bind(TermName("q"), Ident(termNames.WILDCARD)))))))))), UnApply(Apply(Select(Ident(TermName("s")), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(Ident(termNames.WILDCARD), Bind(TermName("r"), Ident(termNames.WILDCARD)))))), Apply(Select(Apply(Select(Ident(TermName("y")), TermName("$greater")), List(Literal(Constant(0)))), TermName("$amp$amp")), List(Apply(Select(Apply(Select(Ident(TermName("s")), TermName("apply")), List(Literal(Constant(())))), TermName("$greater")), List(Literal(Constant(0)))))), Apply(Select(Apply(Select(Ident(code.winitzki.jc.JoinRun), TermName("InjectMultiple")), List(Apply(Select(Apply(Select(Ident(code.winitzki.jc.JoinRun), TermName("InjectMultiple")), List(Apply(Select(Ident(TermName("a")), TermName("apply")), List(Apply(Select(Ident(TermName("p")), TermName("$plus")), List(Literal(Constant(1)))))))), TermName("$plus")), List(Apply(Select(Ident(TermName("qq")), TermName("apply")), List(Literal(Constant(())))))))), TermName("$plus")), List(Apply(Select(Ident(TermName("r")), TermName("apply")), List(Ident(TermName("p"))))))))))

which is equivalent to this:

((x0$1: code.winitzki.jc.JoinRun.UnapplyArg) => x0$1 match {
  case a.unapply(<unapply-selector>) <unapply> ((x @ _)) => a.apply(x.+(1))
  case JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (JoinRun.+.unapply(<unapply-selector>) <unapply> (a.unapply(<unapply-selector>) <unapply> ((p @ _)), a.unapply(<unapply-selector>) <unapply> ((y @ _))), a.unapply(<unapply-selector>) <unapply> (1)), bb.unapply(<unapply-selector>) <unapply> (_)), bb.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])(1, (z @ _)))), bb.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])(_, scala.None))), bb.unapply(<unapply-selector>) <unapply> ((_1: Int, _2: Option[Int])(Int, Option[Int])((t @ _), (x: Int)Some[Int]((q @ _))))), s.unapply(<unapply-selector>) <unapply> (_, (r @ _))) if y.>(0).&&(s.apply(()).>(0)) => JoinRun.InjectMultiple(JoinRun.InjectMultiple(a.apply(p.+(1))).+(qq.apply(()))).+(r.apply(p))
})

    *
    * */
  }


}
