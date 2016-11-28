package code.winitzki.jc

import scala.collection.mutable
import scala.language.experimental.macros
import scala.reflect.macros._
import scala.reflect.NameTransformer.LOCAL_SUFFIX_STRING

import JoinRun._

object Macros {

  val sha1 = java.security.MessageDigest.getInstance("SHA-1")

  def shaSum(c: Any): String = sha1.digest(c.toString.getBytes("UTF-8")).map("%02X".format(_)).mkString

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

  def m[T]: M[T] = macro mImpl[T]

  def mImpl[T: c.WeakTypeTag](c: theContext): c.Expr[M[T]] = {
    import c.universe._
    val s = getEnclosingName(c)

    val t = c.weakTypeOf[T]

    c.Expr[M[T]](q"new M[$t]($s)")
  }

  def b[T, R]: B[T, R] = macro bImpl[T, R]

  def bImpl[T: c.WeakTypeTag, R: c.WeakTypeTag](c: blackbox.Context): c.Expr[B[T, R]] = {
    import c.universe._
    val s = c.internal.enclosingOwner.name.decodedName.toString.stripSuffix(LOCAL_SUFFIX_STRING).stripSuffix("$lzy")

    val t = c.weakTypeOf[T]
    val r = c.weakTypeOf[R]

    c.Expr[B[T, R]](q"new B[$t,$r]($s)")
  }

  sealed trait PatternFlag
    case object Wildcard extends PatternFlag
    case object SimpleVar extends PatternFlag
    case object SimpleConst extends PatternFlag
    case object OtherPattern extends PatternFlag

  final case class InputMoleculeInfo(molecule: Molecule, flag: PatternFlag)

  final case class ReactionInfo(inputs: List[InputMoleculeInfo], outputs: List[Molecule], sha1: String)

  /** Obtains statically checkable information about input and output molecules in a reaction.
    *
    * @param reactionBody Reaction body such as {case a(x) + ... => ...} of type UnapplyArg => Unit
    * @return Information obtained about input and output molecules.
    */
  def findMolecules(reactionBody: ReactionBody): ReactionInfo = macro findMoleculesImpl

  def findMoleculesImpl(c: theContext)(reactionBody: c.Expr[ReactionBody]) = {
    import c.universe._

    object ReactionCases extends Traverser {
      private var info: List[CaseDef] = List()

      override def traverse(tree: Tree): Unit =
        tree match {
          case DefDef(_, TermName("applyOrElse"), _, _, _, Match(_, list)) => info = list
          case Function(List(ValDef(Modifiers(_), TermName("x0$1"), TypeTree(), EmptyTree)), Match(Ident(TermName("x0$1")), list)) => info = list
          case _ => super.traverse(tree)
        }

      def from(tree: Tree): List[(Tree, Tree, Tree, String)] = {
        info = List()
        this.traverse(tree)
        info.map { case c@CaseDef(aPattern, aGuard, aBody) => (aPattern, aGuard, aBody, shaSum(c)) }
      }
    }

    object MoleculeInfo extends Traverser {

      def from(reactionPart: Tree): (List[(c.Symbol, PatternFlag, Option[PatternFlag])], List[c.Symbol]) = {
        inputMolecules = mutable.ArrayBuffer()
        outputMolecules = mutable.ArrayBuffer()
        traverse(reactionPart)
        (inputMolecules.toList, outputMolecules.toList)
      }

      private var inputMolecules: mutable.ArrayBuffer[(c.Symbol, PatternFlag, Option[PatternFlag])] = mutable.ArrayBuffer()
      private var outputMolecules: mutable.ArrayBuffer[c.Symbol] = mutable.ArrayBuffer()

      private def getFlag(binderTerm: Tree): PatternFlag = binderTerm match {
        case Ident(termNames.WILDCARD) => Wildcard
        case Bind(TermName(_), Ident(termNames.WILDCARD)) => SimpleVar
        case Literal(_) => SimpleConst
        case _ => OtherPattern
      }
      // TODO: gather info about all "apply" operations originating from molecules or reply actions
      // TODO: filter by type signature of t, check consistency of the type of t vs. one or two binders used
      // TODO: support multiple "case" expressions, check consistency (all case expressions should involve the same set of molecules)
      override def traverse(tree: Tree): Unit = {
        tree match {
          case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder)) =>
            inputMolecules.append((t.symbol, getFlag(binder), None))

          case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder1, binder2)) =>
            inputMolecules.append((t.symbol, getFlag(binder1), Some(getFlag(binder2))))
// type.typeSymbol can be NoSymbol or a Symbol that can be cast using asType. Similarly for term.termSymbol
          case List(Apply(Select(t@Ident(TermName(_)), TermName("apply")), _))
            if t.tpe <:< typeOf[Molecule] || t.tpe =:= weakTypeOf[ReplyValue[_]]
          => outputMolecules.append(t.symbol)
          case _ => super.traverse(tree)
        }
      }
    }
//    c.abort(c.enclosingPosition, "")
    // TODO:
    // determine which variables are captured by closure?
    // determine types of symbols that use .apply and .unapply, check correctness of M / B / RV types
    // gather (molecule injector, flag, optionally the partial function that matches the pattern, possible output injectors including RV's)

    val patternFlagSymbol = symbolOf[PatternFlag].companion
    implicit val lift = Liftable[PatternFlag] { p =>
      q"$patternFlagSymbol"
    }

    println(s"debug: reactionbody tree is ${show(reactionBody.tree)}")
    val caseDefs = ReactionCases.from(reactionBody.tree)
    // for now, only look at the first case
    // TODO: check other caseDef's if any
    val Some((pattern, guard, body, sha1)) = caseDefs.headOption

    val (patternIn, patternOut) = MoleculeInfo.from(pattern) // patternOut should be empty
    val (guardIn, guardOut) = MoleculeInfo.from(guard) // guardIn should be empty
    val (bodyIn, bodyOut) = MoleculeInfo.from(body) // bodyIn should be empty

    val outputMolecules = (guardOut ++ bodyOut)
      .map {
        m => {
          val mt = m.asTerm
          q"$mt"
        }
      }

    val inputMolecules = patternIn.map { case (s, p, op) => q"InputMoleculeInfo(${s.asTerm}, $p)" }

//    reify {
//      ReactionInfo(???, outputMolecules, sha1)
//    }

    q"ReactionInfo($inputMolecules, List(...$outputMolecules), $sha1)"

//    println(s"Gathered info: ${MoleculeInfo.inputMolecules}")
//
//    val t1 = MoleculeInfo.inputMolecules(0)._1.dealias.typeSymbol
//    val s = showRaw(arg)
//    q"$s"
  }

}
