package code.chymyst.jc

import Core._

import scala.collection.mutable
import scala.reflect.macros.blackbox
import scala.annotation.tailrec
import scala.reflect.api.Trees

class ReactionMacros(override val c: blackbox.Context) extends CommonMacros(c) {

  import c.universe._

  // A singleton reaction must start with _ and must emit some output molecules (which we check later).
  def isSingletonReaction(pattern: Tree, guard: Tree, body: Tree): Boolean = {
    pattern match {
      case Ident(termNames.WILDCARD) => true
      case _ => false
    }
  }

  def getSimpleVar(binderTerm: Tree): Ident = binderTerm match {
    case Bind(t@TermName(n), Ident(termNames.WILDCARD)) => Ident(t)
  }

  private val extractorCodes = Map(
    "scala.Some" -> q"Some",
    "scala.`package`.Left" -> q"Left",
    "scala.`package`.Right" -> q"Right"
  )

  private val applyCodes = Set("scala.Some.apply", "scala.util.Left.apply", "scala.util.Right.apply")

  /** Detect whether an expression tree represents a constant expression.
    * A constant expression is either a literal constant, or Some(), None(), Left(), Right(), and tuples of constant expressions.
    *
    * @param exprTree Binder pattern tree or expression tree.
    * @return {{{Some(tree)}}} if the expression represents a constant of the recognized form. Here {{{tree}}} will be a quoted expression tree (not a binder tree). {{{None}}} otherwise.
    */
  def getConstantTree(exprTree: Trees#Tree): Option[Trees#Tree] = exprTree match {

    case Literal(_) => Some(exprTree)

    case pq"scala.None" | q"scala.None" => Some(q"None")

    case q"$applier[..$ts](..$xs)"
      if (ts.size === 1 || ts.size === 2) && xs.size === 1 &&
        applyCodes.contains(applier.symbol.fullName) =>
      xs.headOption
        .flatMap(getConstantTree)
        .map(t => q"$applier(${t.asInstanceOf[Tree]})")

    case pq"$extr(..$xs)" if xs.size === 1 => for {
      applier <- extractorCodes.get(showCode(extr.asInstanceOf[Tree]))
      x <- xs.headOption
      xConstant <- getConstantTree(x)
    } yield q"$applier(${xConstant.asInstanceOf[Tree]})"

    // Tuples: the pq"" quasiquote covers both the binder and the expression
    case pq"(..$exprs)" if exprs.size > 1 => // Tuples of size 0 are Unit values, tuples of size 1 are ordinary values.
      val trees = exprs.flatMap(getConstantTree).map(_.asInstanceOf[Tree]) // if some exprs are not constant, they will be omitted in this list
      if (trees.size === exprs.size) Some(q"(..$trees)") else None

    case _ => None
  }

  def identToScalaSymbol(ident: Ident): scala.Symbol = ident.name.decodedName.toString.toScalaSymbol

  /** Convert a term to conjunctive normal form (CNF).
    * CNF is represented as a list of lists of Boolean term trees.
    * For example, {{{List( List(q"x>0", q"y<x"), List(q"x>z", q"z<1") )}}} represents {{{( x > 0 || y < x ) && ( x > z || z < 1)}}}.
    *
    * @param term Initial expression tree.
    * @return Equivalent expression in CNF. Terms will be duplicated when necessary. No simplification is performed on terms.
    */
  def convertToCNF(term: Tree): List[List[Tree]] = {

    def disjunctionOneTerm(a: Tree, b: List[List[Tree]]): List[List[Tree]] = b.map(y => (a :: y).distinct).distinct

    def disjunctionOneClause(a: List[Tree], b: List[List[Tree]]): List[List[Tree]] = b.map(y => (a ++ y).distinct).distinct

    def disjunction(a: List[List[Tree]], b: List[List[Tree]]): List[List[Tree]] = a.flatMap(x => disjunctionOneClause(x, b)).distinct

    def conjunction(a: List[List[Tree]], b: List[List[Tree]]): List[List[Tree]] = (a ++ b).distinct

    def negation(a: List[List[Tree]]): List[List[Tree]] = a match {
      case x :: xs =>
        val nxs = negation(xs)
        x.flatMap(t => disjunctionOneTerm(q"! $t", nxs))
      case Nil => List(List()) // negation of true is false
    }

    def normalize(a: Trees#Tree): List[List[Tree]] = convertToCNF(a.asInstanceOf[Tree])

    term match {
      case EmptyTree => List()
      case q"$a && $b" =>
        val aN = normalize(a)
        val bN = normalize(b)
        conjunction(aN, bN)

      case q"$a || $b" =>
        val aN = normalize(a)
        val bN = normalize(b)
        disjunction(aN, bN)

      case q"if ($a) $b else $c" => // (a' + b)(a + c)
        val aN = normalize(a)
        val bN = normalize(b)
        val cN = normalize(c)
        conjunction(disjunction(negation(aN), bN), disjunction(aN, cN))

      case q"$a ^ $b" => // (a+b)(a'+b')
        val aN = normalize(a)
        val bN = normalize(b)
        conjunction(disjunction(aN, bN), disjunction(negation(aN), negation(bN)))

      case q"! $a" => negation(normalize(a))
      case q"true" => List()
      case q"false" => List(List())
      case _ => List(List(term))
    }
  }

  def matcherFunction(binderTerm: Tree, guardTree: Tree, vars: List[Ident]): Tree = {
    if (guardTree.isEmpty)
      q"{ case $binderTerm => () }" // This should not be untypechecked!! Why?
    else {
      // Need to put types on binder variables and remove owner from guard tree symbols.
      c.untypecheck(q"{ case $binderTerm if $guardTree => () }")
    }
  }

  /** Obtain the owner of the current macro call site.
    *
    * @return The owner symbol of the current macro call site.
    */
  def getCurrentSymbolOwner: c.Symbol = {
    val freshName = c.freshName(TypeName("Probe$"))
    val probe = c.typecheck(q""" {class $freshName; ()} """)
    probe match {
      case Block(List(t), r) => t.symbol.owner
    }
  }

  /** Obtain the list of `case` expressions in a reaction.
    * There should be only one `case` expression.
    */
  object GetReactionCases extends Traverser {
    private var info: List[CaseDef] = List()
    private var isFirstReactionCase: Boolean = true

    override def traverse(tree: Tree): Unit =
      tree match {
        // this is matched by the partial function of type ReactionBody
        case DefDef(_, TermName("applyOrElse"), _, _, _, Match(_, list)) if isFirstReactionCase =>
          info = list
          isFirstReactionCase = false

        // this is matched by a closure which is not a partial function. Not used now, because ReactionBody is now a subclass of PartialFunction.
        /*
        case Function(List(ValDef(_, TermName(_), TypeTree(), EmptyTree)), Match(Ident(TermName(_)), list)) if isFirstReactionCase =>
         info = list
         isFirstReactionCase = false
        */
        case _ => super.traverse(tree)
      }

    def from(tree: Tree): List[(Tree, Tree, Tree)] = {
      info = List()
      this.traverse(tree)
      info.filter {
        // PartialFunction automatically adds a default case; we ignore that CaseDef.
        case CaseDef(Bind(TermName("defaultCase$"), Ident(termNames.WILDCARD)), EmptyTree, _) => false
        case _ => true
      }.map { case c@CaseDef(aPattern, aGuard, aBody) => (aPattern, aGuard, aBody) }
    }
  }

  object PatternVars extends Traverser {

    private var vars: mutable.ArrayBuffer[Ident] = _

    override def traverse(tree: Tree): Unit = tree match {
      case Bind(t@TermName(_), pat) =>
        vars.append(Ident(t))
        traverse(pat)
      case _ => super.traverse(tree)
    }

    def from(binderTerm: Tree): List[Ident] = {
      vars = mutable.ArrayBuffer()
      traverse(binderTerm)
      vars.toList
    }
  }

  object ReplaceVars extends Transformer {
    var replacingIdents: List[Ident] = _
    var addTypes: Boolean = _

    override def transform(tree: Tree): Tree = if (addTypes) tree match {
      case Bind(t@TermName(name), Ident(termNames.WILDCARD)) =>
        val newIdentOpt = replacingIdents.find(_.name.toTermName.decodedName.toString === name)
        newIdentOpt.map { newIdent =>
          val newIdentType = newIdent.symbol.typeSignature
          val paramType = newIdentType
          pq"$t : $paramType"
          // TODO: test this separately and debug.
          /* Type params perhaps needs more work?
          val typeParams = newIdentType.typeArgs
          if (typeParams.isEmpty)
            pq"$t : $newIdentType"
          else {
            val typeApplication = tq"$newIdentType[..$typeParams]"
            pq"$t : $typeApplication"
          }
          */
        }.getOrElse(tree)
      case _ => super.transform(tree)
    } else tree match {
      case Ident(name) => replacingIdents.find(_.name === name).getOrElse(tree)
      case _ => super.transform(tree)
    }

    def in(guardTree: Tree, vars: List[Ident], inBinder: Boolean): Tree = {
      replacingIdents = vars
      addTypes = inBinder
      transform(guardTree)
    }
  }

  object GuardVars extends Traverser {
    private var vars: mutable.ArrayBuffer[Ident] = _
    private var givenPatternVars: List[Ident] = _

    override def traverse(tree: Tree): Unit = tree match {
      case t@Ident(TermName(_)) =>
        val identIsPatternVariable = givenPatternVars exists (_.name === t.name)
        if (identIsPatternVariable) vars.append(t)
      case _ => super.traverse(tree)
    }

    def fromFlags(guardTerm: Tree, inputInfos: List[InputPatternFlag]): List[Ident] =
      fromVars(guardTerm, inputInfos.flatMap(_.varNames))

    def fromVars(guardTerm: Tree, givenVars: List[Ident]): List[Ident] = {
      givenPatternVars = givenVars
      vars = mutable.ArrayBuffer()
      traverse(guardTerm)
      vars.toList
    }
  }

  object DetectInvalidInputGrouping extends Traverser {
    var found: Boolean = _

    override def traverse(tree: c.universe.Tree): Unit = tree match {
      case pq"$extr1($_,$extr2($_,$_))"
        if extr1.symbol.fullName === "code.chymyst.jc.$plus" && extr2.symbol.fullName === "code.chymyst.jc.$plus" =>
        found = true
      case _ => super.traverse(tree)
    }

    def in(tree: Tree): Boolean = {
      found = false
      traverse(tree)
      found
    }
  }

  class MoleculeInfo(reactionBodyOwner: c.Symbol) extends Traverser {

    /** Examine an expression tree, looking for molecule expressions.
      *
      * @param reactionPart An expression tree (could be the "case" pattern, the "if" guard, or the reaction body).
      * @return A triple: List of input molecule patterns, list of output molecule patterns, and list of reply action patterns.
      */
    def from(reactionPart: Tree): (List[(c.Symbol, InputPatternFlag, Option[InputPatternFlag])], List[(c.Symbol, OutputPatternFlag)], List[(c.Symbol, OutputPatternFlag)]) = {
      inputMolecules = mutable.ArrayBuffer()
      outputMolecules = mutable.ArrayBuffer()
      replyActions = mutable.ArrayBuffer()
      traverse(reactionPart)
      (inputMolecules.toList, outputMolecules.toList, replyActions.toList)
    }

    private var inputMolecules: mutable.ArrayBuffer[(c.Symbol, InputPatternFlag, Option[InputPatternFlag])] = mutable.ArrayBuffer()
    private var outputMolecules: mutable.ArrayBuffer[(c.Symbol, OutputPatternFlag)] = mutable.ArrayBuffer()
    private var replyActions: mutable.ArrayBuffer[(c.Symbol, OutputPatternFlag)] = mutable.ArrayBuffer()

    /** Detect whether the symbol {{{s}}} is defined inside the scope of the symbol {{{owner}}}.
      * Will return true for code like {{{ val owner = .... { val s = ... }  }}}
      *
      * @param s     Symbol to be examined.
      * @param owner Owner symbol of the scope to be examined.
      * @return True if {{{s}}} is defined inside the scope of {{{owner}}}.
      */
    @tailrec
    private def isOwnedBy(s: c.Symbol, owner: c.Symbol): Boolean = s.owner match {
      case `owner` => owner =!= NoSymbol
      case `NoSymbol` => false
      case o@_ => isOwnedBy(o, owner)
    }

    private def getInputFlag(binderTerm: Tree): InputPatternFlag = binderTerm match {
      case Ident(termNames.WILDCARD) => WildcardF
      case Bind(t@TermName(_), Ident(termNames.WILDCARD)) => SimpleVarF(Ident(t), binderTerm, None)
      case _ => getConstantTree(binderTerm)
        .map(t => SimpleConstF(t.asInstanceOf[Tree]))
        .getOrElse {
          val vars = PatternVars.from(binderTerm)
          OtherPatternF(binderTerm, EmptyTree, vars)
        }
    }

    private def getOutputFlag(binderTerms: List[Tree]): OutputPatternFlag = binderTerms match {
      case List(t) => getConstantTree(t).map(tree => ConstOutputPatternF(tree.asInstanceOf[Tree])).getOrElse(OtherOutputPatternF)
      case Nil => EmptyOutputPatternF
      case _ => OtherOutputPatternF
    }

    override def traverse(tree: Tree): Unit = {
      tree match {
        // avoid traversing nested reactions: check whether this subtree is a Reaction() value
        case q"code.chymyst.jc.Reaction.apply(..$_)" => ()
        case q"Reaction.apply(..$_)" => ()

        // matcher with a single argument: a(x)
        case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder)) if t.tpe <:< typeOf[Molecule] =>
          val flag2Opt = if (t.tpe <:< weakTypeOf[B[_, _]]) Some(WrongReplyVarF) else None
          val flag1 = getInputFlag(binder)
          if (flag1.hasSubtree) traverse(binder)
          inputMolecules.append((t.symbol, flag1, flag2Opt))

        // matcher with two arguments: a(x, y)
        case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder1, binder2)) if t.tpe <:< typeOf[Molecule] =>
          val flag2 = getInputFlag(binder2) match {
            case SimpleVarF(_, _, _) => ReplyVarF(getSimpleVar(binder2))
            case f@_ => WrongReplyVarF // this is an error that we should report later
          }
          val flag1 = getInputFlag(binder1)
          // Perhaps we need to continue to analyze the "binder" (it could be another molecule).
          if (flag1.hasSubtree) traverse(binder1)
          if (flag2.hasSubtree) traverse(binder2)
          // After traversing the subtrees, we append this molecule information.
          inputMolecules.append((t.symbol, flag1, Some(flag2)))

        // Matcher with wrong number of arguments - neither 1 nor 2. This seems to never be called, so let's comment it out.
        /*
        case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), _)
          if t.tpe <:< typeOf[Molecule] =>
            inputMolecules.append((t.symbol, WrongReplyVarF, None, getSha1(t)))
          */

        // possibly a molecule emission
        case Apply(Select(t@Ident(TermName(_)), TermName(f)), binderList)
          if f === "apply" || f === "checkTimeout" =>

          // In the output list, we do not include any molecule emitters defined in the inner scope of the reaction.
          val includeThisSymbol = !isOwnedBy(t.symbol.owner, reactionBodyOwner)

          val flag1 = getOutputFlag(binderList)
          if (flag1.needTraversal)
          // Traverse the tree of the first binder element (molecules should only have one binder element anyway).
            binderList.headOption.foreach(traverse)

          if (includeThisSymbol) {
            if (t.tpe <:< typeOf[Molecule]) {
              outputMolecules.append((t.symbol, flag1))
            }
          }
          if (t.tpe <:< weakTypeOf[AbsReplyValue[_, _]]) {
            replyActions.append((t.symbol, flag1))
          }

        case _ => super.traverse(tree)

      }
    }
  }


  def guardVarsConstrainOnlyThisMolecule(guardVarList: List[Ident], moleculeFlag: InputPatternFlag): Boolean =
    guardVarList.forall(v => moleculeFlag.varNames.exists(mv => mv.name === v.name))

  def guardVarsConstrainThisMolecule(guardVarList: List[Ident], moleculeFlag: InputPatternFlag): Boolean =
    guardVarList.exists(v => moleculeFlag.varNames.exists(mv => mv.name === v.name))

  // This boilerplate is necessary for being able to use PatternType values in quasiquotes.
  implicit val liftableInputPatternFlag: Liftable[InputPatternFlag] = Liftable[InputPatternFlag] {
    case WildcardF => q"_root_.code.chymyst.jc.Wildcard"
    case SimpleConstF(tree) => q"_root_.code.chymyst.jc.SimpleConst($tree)"
    case SimpleVarF(v, binder, cond) =>
      val guardFunction = cond.map(c => matcherFunction(binder, c, List(v)))
      q"_root_.code.chymyst.jc.SimpleVar(${identToScalaSymbol(v)}, $guardFunction)"
    case OtherPatternF(matcherTree, guardTree, vars) => q"_root_.code.chymyst.jc.OtherInputPattern(${matcherFunction(matcherTree, guardTree, vars)}, ${vars.map(identToScalaSymbol)})"
    case _ => q"_root_.code.chymyst.jc.Wildcard" // this case will not be encountered here; we are conflating InputPatternFlag and ReplyInputPatternFlag
  }

  implicit val liftableOutputPatternFlag: Liftable[OutputPatternFlag] = Liftable[OutputPatternFlag] {
    case ConstOutputPatternF(tree) => q"_root_.code.chymyst.jc.SimpleConstOutput($tree)"
    case EmptyOutputPatternF => q"_root_.code.chymyst.jc.SimpleConstOutput(())"
    case _ => q"_root_.code.chymyst.jc.OtherOutputPattern"
  }

  def maybeError[T](what: String, patternWhat: String, molecules: Seq[T], connector: String = "not contain a pattern that", method: (c.Position, String) => Unit = c.error): Unit = {
    if (molecules.nonEmpty)
      method(c.enclosingPosition, s"$what must $connector $patternWhat (${molecules.mkString(", ")})")
  }

  def reportError(message: String): Unit = c.error(c.enclosingPosition, message)

  /* This code has been commented out after a lengthy exploration of valid ways of modifying the reaction body.

//   this fails in weird ways
      def removeGuard(tree: Tree): Tree = tree match {
        case q"{case ..$cases }" =>
          val newCases = cases.map {
          case cq"$pat if $guard => $body" => cq"$pat => $body"
          case _ => tree
        }
          q"{case ..$newCases}"
        case _ => tree
      }


      object RemoveReactionGuardTransformer extends Transformer {
        override def transform(tree: Tree): Tree = tree match {
          case CaseDef(aPattern, aGuard, aBody) => CaseDef(aPattern, EmptyTree, aBody)
          case _ => super.transform(tree)
        }
      }

      object LocateAndTransformReactionInput extends Transformer {
        private var isFirstApplyOrElse: Boolean = _
        private var isFirstIsDefinedAt: Boolean = _
        private var useTransform: Tree => Tree = _

        override def transform(tree: Tree): Tree = tree match {
          case DefDef(modifiers, termName@TermName(termNameString), tparams, vparamss, tpt, Match(matchee, list))
            if isFirstApplyOrElse && termNameString === "applyOrElse" || isFirstIsDefinedAt && termNameString === "isDefinedAt" =>
            if (termNameString === "applyOrElse") isFirstApplyOrElse = false
            if (termNameString === "isDefinedAt") isFirstIsDefinedAt = false
            val newList = list.map(l => useTransform(l).asInstanceOf[CaseDef]) // `object` cannot have type parameters, otherwise we would have done .map(useTransform[CaseDef])
            DefDef(modifiers, termName, tparams, vparamss, tpt, Match(matchee, newList))
          case _ => super.transform(tree)
        }

        def withMap(trans: Tree => Tree)(tree: Tree): Tree = {
          isFirstApplyOrElse = true
          isFirstIsDefinedAt = true
          useTransform = trans
          transform(tree)
        }
      }

      def removeReactionGuard(tree: Tree): Tree =
        LocateAndTransformReactionInput.withMap(l => RemoveReactionGuardTransformer.transform(l))(tree)
  */

}
