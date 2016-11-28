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

  sealed trait PatternType
  case object Wildcard extends PatternType
  case object SimpleVar extends PatternType
  case object SimpleConst extends PatternType
  case object OtherPattern extends PatternType

  final case class InputMoleculeInfo(molecule: Molecule, flag: PatternType)

  final case class ReactionInfo(inputs: List[InputMoleculeInfo], outputs: List[Molecule], sha1: String)

  /** Obtains statically checkable information about input and output molecules in a reaction.
    *
    * @param reactionBody Reaction body such as {case a(x) + ... => ...} of type UnapplyArg => Unit
    * @return Information obtained about input and output molecules.
    */

  type fmArg = UnapplyArg => Unit // ReactionBody
  def findMolecules(reactionBody: fmArg): ReactionInfo = macro findMoleculesImpl

  def findMoleculesImpl(c: theContext)(reactionBody: c.Expr[fmArg]) = {
    import c.universe._

    sealed trait PatternFlag {
      def notReplyValue: Boolean = this match {
        case ReplyVar(_) => false
        case _ => true
      }

    }

    case object WildcardF extends PatternFlag
    case class  ReplyVar(replyVar: c.Symbol) extends PatternFlag
    case object SimpleVarF extends PatternFlag
    case object SimpleConstF extends PatternFlag
    case object OtherPatternF extends PatternFlag

    def toPatternType(flag: PatternFlag): PatternType = flag match {
      case ReplyVar(_) => Macros.OtherPattern
      case WildcardF => Macros.Wildcard
      case SimpleVarF => Macros.SimpleVar
      case SimpleConstF => Macros.SimpleConst
      case OtherPatternF => Macros.OtherPattern
    }

    object ReactionCases extends Traverser {
      private var info: List[CaseDef] = List()

      override def traverse(tree: Tree): Unit =
        tree match {
          case DefDef(_, TermName("applyOrElse"), _, _, _, Match(_, list)) => info = list
          case Function(List(ValDef(Modifiers(_), TermName(_), TypeTree(), EmptyTree)), Match(Ident(TermName(_)), list)) => info = list
          case _ => super.traverse(tree)
        }

      def from(tree: Tree): List[(Tree, Tree, Tree, String)] = {
        info = List()
        this.traverse(tree)
        info.filter { // PartialFunction automatically adds a default case; we don't want to analyze that CaseDef.
            case CaseDef(Bind(TermName("defaultCase$"), Ident(termNames.WILDCARD)), EmptyTree, _) => false
            case _ => true
          }.map { case c@CaseDef(aPattern, aGuard, aBody) => (aPattern, aGuard, aBody, shaSum(c)) }
      }
    }

    object MoleculeInfo extends Traverser {

      def from(reactionPart: Tree): (List[(c.Symbol, PatternFlag, Option[PatternFlag])], List[c.Symbol], List[c.Symbol]) = {
        inputMolecules = mutable.ArrayBuffer()
        outputMolecules = mutable.ArrayBuffer()
        replyMolecules = mutable.ArrayBuffer()
        traverse(reactionPart)
        (inputMolecules.toList, outputMolecules.toList, replyMolecules.toList)
      }

      private var inputMolecules: mutable.ArrayBuffer[(c.Symbol, PatternFlag, Option[PatternFlag])] = mutable.ArrayBuffer()
      private var outputMolecules: mutable.ArrayBuffer[c.Symbol] = mutable.ArrayBuffer()
      private var replyMolecules: mutable.ArrayBuffer[c.Symbol] = mutable.ArrayBuffer()

      private def getSimpleVar(binderTerm: Tree): c.Symbol = binderTerm match {
        case Bind(t@TermName(_), Ident(termNames.WILDCARD)) => Ident(t).symbol
      }

      private def getFlag(binderTerm: Tree): PatternFlag = binderTerm match {
        case Ident(termNames.WILDCARD) => WildcardF
        case Bind(TermName(_), Ident(termNames.WILDCARD)) => SimpleVarF
        case Literal(_) => SimpleConstF
        case _ => OtherPatternF
      }
      // TODO: gather info about all "apply" operations originating from molecules or reply actions
      // TODO: filter by type signature of t, check consistency of the type of t vs. one or two binders used
      // TODO: support multiple "case" expressions, check consistency (all case expressions should involve the same set of molecules)
      override def traverse(tree: Tree): Unit = {
        tree match {
          case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder)) if t.tpe <:< typeOf[Molecule] =>
            inputMolecules.append((t.symbol, getFlag(binder), None))

          case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder1, binder2)) if t.tpe <:< typeOf[Molecule] =>
            val flag2 = getFlag(binder2) match {
              case SimpleVarF => ReplyVar(getSimpleVar(binder2))
              case f@_ => f
            }
            inputMolecules.append((t.symbol, getFlag(binder1), Some(flag2)))

          case Apply(Select(t@Ident(TermName(_)), TermName("apply")), _) =>
            if (t.tpe <:< typeOf[Molecule])
              outputMolecules.append(t.symbol)
            if (t.tpe <:< weakTypeOf[ReplyValue[_]])
              replyMolecules.append(t.symbol)
          case _ => super.traverse(tree)
        }
      }
    }
//    c.abort(c.enclosingPosition, "")
    // TODO:
    // gather (molecule injector, flag, optionally the partial function that matches the pattern, possible output injectors including RV's, whether guards inject any molecules / any blocking molecules)

    implicit val lift = Liftable[PatternType] {
      // Select(This(TypeName("Macros")), TermName("Wildcard"))
      case Macros.Wildcard => q"_root_.code.winitzki.jc.Macros.Wildcard"
      case Macros.SimpleConst => q"_root_.code.winitzki.jc.Macros.SimpleConst"
      case Macros.SimpleVar => q"_root_.code.winitzki.jc.Macros.SimpleVar"
      case Macros.OtherPattern => q"_root_.code.winitzki.jc.Macros.OtherPattern"
    }

    val caseDefs = ReactionCases.from(reactionBody.tree)
    // for now, only look at the first case
    // TODO: check other caseDef's if any
    val Some((pattern, guard, body, sha1)) = caseDefs.headOption

    val (patternIn, patternOut, patternReply) = MoleculeInfo.from(pattern) // patternOut and patternReply should be empty
    val (guardIn, guardOut, guardReply) = MoleculeInfo.from(guard) // guardIn should be empty
    val (bodyIn, bodyOut, bodyReply) = MoleculeInfo.from(body) // bodyIn should be empty

    if (patternOut.nonEmpty) c.abort(c.enclosingPosition, s"Error in reaction: input molecules should not contain a pattern that injects output molecules")
    if (patternReply.nonEmpty) c.abort(c.enclosingPosition, s"Error in reaction: input molecules should not contain a pattern that injects reply molecules")
    if (guardIn.nonEmpty) c.abort(c.enclosingPosition, s"Error in reaction: input guard should not contain a pattern that matches on additional input molecules")
    if (bodyIn.nonEmpty) c.abort(c.enclosingPosition, s"Error in reaction: reaction body should not contain a pattern that matches on additional input molecules")

    val outputMolecules = (guardOut ++ bodyOut).map { m => q"${m.asTerm}" }

    val inputMolecules = patternIn.map { case (s, p, op) => q"InputMoleculeInfo(${s.asTerm}, ${toPatternType(p)})" }

    val result = q"ReactionInfo($inputMolecules, List(..$outputMolecules), $sha1)"
//    println(s"debug: ${show(result)}")
    result
  }

}
