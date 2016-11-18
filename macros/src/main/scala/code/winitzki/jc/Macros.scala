package code.winitzki.jc

import scala.collection.mutable
import scala.language.experimental.macros
import scala.reflect.macros._
import scala.reflect.NameTransformer.LOCAL_SUFFIX_STRING

import JoinRun._

object Macros {

  type theContext = blackbox.Context

  def getName: String = macro getNameImpl

  def getEnclosingName(c: theContext): String =
    c.internal.enclosingOwner.name.decodedName.toString
      .stripSuffix(LOCAL_SUFFIX_STRING).stripSuffix("$lzy")

  def getNameImpl(c: theContext): c.Expr[String] = {
    import c.universe._

    val s = getEnclosingName(c)

    c.Expr[String](q"$s")
  }

  def jA[T]: JA[T] = macro jAImpl[T]

  def jAImpl[T: c.WeakTypeTag](c: theContext): c.Expr[JA[T]] = {
    import c.universe._
    val s = getEnclosingName(c)

    val t = c.weakTypeOf[T]

    c.Expr[JA[T]](q"new JA[$t](Some($s))")
  }

  def jS[T, R]: JS[T, R] = macro jSImpl[T, R]

  def jSImpl[T: c.WeakTypeTag, R: c.WeakTypeTag](c: blackbox.Context): c.Expr[JS[T, R]] = {
    import c.universe._
    val s = c.internal.enclosingOwner.name.decodedName.toString.stripSuffix(LOCAL_SUFFIX_STRING).stripSuffix("$lzy")

    val t = c.weakTypeOf[T]
    val r = c.weakTypeOf[R]

    c.Expr[JS[T, R]](q"new JS[$t,$r](Some($s))")
  }

  sealed trait PatternFlag
    case object Wildcard extends PatternFlag
    case object SimpleVar extends PatternFlag
    case object SimpleConst extends PatternFlag
    case object OtherPattern extends PatternFlag

  def findInputs(arg: UnapplyArg => Unit): String = macro findInputsImpl

  def findInputsImpl(c: theContext)(arg: c.Expr[UnapplyArg => Unit]) = {
    import c.universe._

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

    /** Check whether the reaction code is of the form that we can recognize and parse.
      *
      * @param term Reaction expression, which must a partial function of the form { case a(x) + b(y) + c(z) => ... }
      * @return Some("error message") if the expression is invalid. Otherwise, if reaction is valid, return None
      */
    def isInvalidReaction(term: Tree): Option[String] = None

    object GatherInfo extends Traverser {
      var info: mutable.ArrayBuffer[(Any, PatternFlag)] = mutable.ArrayBuffer()

      def getFlag(binderTerm: Tree): PatternFlag = binderTerm match {
        case Ident(termNames.WILDCARD) => Wildcard
        case Bind(TermName(_), Ident(termNames.WILDCARD)) => SimpleVar
        case Literal(_) => SimpleConst
        case _ => OtherPattern
      }
      // TODO: gather info about all "apply" operations originating from molecules or reply actions
      // TODO: filter by type signature of t, check consistency of the type of t vs. one or two binders used
      // TODO: support multiple "case" expressions, check consistency (all case expressions involve the same set of molecules)
      override def traverse(tree: c.universe.Tree): Unit = {
        tree match {
          case UnApply(Apply(Select(t@Ident(TermName(name)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder)) =>
            info.append((t.symbol.typeSignature, getFlag(binder)))

          case UnApply(Apply(Select(t@Ident(TermName(name)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder1, binder2)) =>
            info.append((t.symbol.typeSignature, getFlag(binder1)))

          //          case Apply(Select(t@Ident(TermName(name)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))) =>
//            info.append((t.symbol.typeSignature, OtherPattern))

          case _ => super.traverse(tree)
        }
      }
    }
//    c.abort(c.enclosingPosition, "")
    // determine which variables are captured by closure?
    // determine types of symbols that use .apply
    val error = isInvalidReaction(arg.tree)

    GatherInfo.traverse(arg.tree)

    println(s"Gathered info: ${GatherInfo.info}")

    val s = showRaw(arg)
    q"$s"
  }

}
