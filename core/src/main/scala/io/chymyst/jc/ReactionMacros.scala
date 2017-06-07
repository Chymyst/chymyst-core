package io.chymyst.jc

import io.chymyst.jc.Core._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.language.postfixOps
import scala.reflect.api.Trees
import scala.reflect.macros.blackbox

class ReactionMacros(override val c: blackbox.Context) extends CommonMacros(c) {

  import c.universe._

  // Must avoid confusing these types.
  type MacroSymbol = c.Symbol
  type ScalaSymbol = scala.Symbol

  // A static reaction must start with _ and must emit some output molecules (which we check later).
  def isStaticReaction(pattern: Tree, guard: Tree, body: Tree): Boolean = pattern match {
    case Ident(termNames.WILDCARD) => true
    case _ => false
  }

  def getSimpleVar(binderTerm: Tree): Ident = binderTerm match {
    case Bind(t@TermName(_), Ident(termNames.WILDCARD)) => Ident(t)
  }

  private val constantExtractorCodes = Map(
    "scala.Some" -> q"Some"
    , "scala.Symbol" -> q"Symbol"
    , "scala.`package`.Left" -> q"Left"
    , "scala.`package`.Right" -> q"Right"
  )

  private val constantApplierCodes = Set(
    "scala.Some.apply"
    , "scala.util.Left.apply"
    , "scala.util.Right.apply"
    , "scala.Symbol.apply"
    , "scala.collection.immutable.List.apply"
  )

  private val seqConstantExtractorCodes = Map(
    "scala.collection.immutable.List(" -> q"List"
  )

  private val seqConstantExtractorHeads = Set("scala.collection.generic.SeqFactory.unapplySeq")

  private val emitMultipleFunctionCodes = Set(
    "io.chymyst.jc.EmitMultiple"
    , "io.chymyst.jc.EmitMultiple.$plus"
  )

  private val iteratingFunctionCodes = Set(
    "scala.collection.TraversableLike.map",
    "scala.collection.immutable.Range.foreach"
  )

  // These operations constitute the "use" of a reply emitter in a reply action.
  private val replyEmitterActionOps = Set(
    "apply"
  )

  // These operations do not constitute the "use" of a reply emitter in a reply action.
  private val replyEmitterReadOps = Set(
    "noReplyAttemptedYet"
  )

  // These operations constitute the "use" of a molecule emitter to emit molecules.
  private val moleculeEmitterActionOps = Set(
    "apply"
    , "timeout"
    , "futureReply"
  )

  // These operations do not constitute the "use" of a molecule emitter to emit molecules.
  private val moleculeEmitterReadOps = Set(
    "name"
    , "isBound"
    , "isPipelined"
    , "typeSymbol"
  )

  private val emitterReadOps = moleculeEmitterReadOps ++ replyEmitterReadOps

  private val moleculeEmitterAllOps = moleculeEmitterActionOps ++
    moleculeEmitterReadOps ++
    replyEmitterActionOps ++
    replyEmitterReadOps

  /** Detect whether a pattern-matcher expression tree represents an irrefutable pattern.
    * For example, `Some(_)` is refutable because it does not match `None`.
    * The pattern `(_, x, y, (z, _))` is irrefutable.
    * Patterns with single-case-classes are irrefutable.
    *
    * @param binderTerm Binder pattern tree.
    * @return `true` or `false`
    */
  def isIrrefutablePattern(binderTerm: Tree): Boolean = binderTerm match {
    case Ident(termNames.WILDCARD) =>
      true
    case pq"$x @ $y" => // Matching by a simple variable `x` is actually `x @ _` and is detected here.
      isIrrefutablePattern(y.asInstanceOf[Tree])
    case pq"(..$exprs)" // Tuple: all elements must be irrefutable.
      if exprs.size >= 2 =>
      exprs.forall(t => isIrrefutablePattern(t.asInstanceOf[Tree]))
    case pq"$extr(..$args)" => // Case class with exactly one case?
      val typeSymbolOfExtr: Symbol = extr.tpe.asInstanceOf[Type].finalResultType.typeSymbol // Note: extr.tpe.symbol is NoSymbol since it's a pattern matcher tree.
      typeSymbolOfExtr.isClass && {
        val classSymbolOfExtr = typeSymbolOfExtr.asClass
        classSymbolOfExtr.isCaseClass && {
          val candidateBaseSealedTraits = classSymbolOfExtr.baseClasses.filter(b => b.asClass.isSealed && b.asClass.knownDirectSubclasses.contains(classSymbolOfExtr))
          candidateBaseSealedTraits.nonEmpty &&
            candidateBaseSealedTraits.forall(_.asClass.knownDirectSubclasses.size === 1)
        } && args.forall(t => isIrrefutablePattern(t.asInstanceOf[Tree]))
      }
    case pq"$first | ..$rest"
      if rest.nonEmpty => // At least one of the alternatives must be irrefutable.
      (first :: rest.toList).exists(t => isIrrefutablePattern(t.asInstanceOf[Tree]))

    case _ => false
  }

  /** Detect whether an expression tree represents a constant expression.
    * A constant expression is either a literal constant (Int, String, Symbol, etc.), (), None, Nil, or Some(...), Left(...), Right(...), List(...), and tuples of constant expressions.
    *
    * @param exprTree Binder pattern tree or expression tree.
    * @return `Some(tree)` if the expression represents a constant of the recognized form. Here `tree` will be a quoted expression tree (not a binder tree). `None` otherwise.
    */
  def getConstantTree(exprTree: Trees#Tree): Option[Trees#Tree] = exprTree match {

    case Literal(_) => Some(exprTree)

    case pq"scala.None"
         | q"scala.None" =>
      Some(q"None")

    case pq"immutable.this.Nil"
         | pq"scala.collection.immutable.Nil"
         | q"immutable.this.Nil"
         | q"scala.collection.immutable.Nil" =>
      Some(q"Nil")

    // Tuples: the pq"" quasiquote covers both the binder and the expression!
    case pq"(..$exprs)" if exprs.size >= 2 => // Tuples of size 0 are Unit values, tuples of size 1 are ordinary values.
      val trees = exprs.flatMap(getConstantTree).map(_.asInstanceOf[Tree]) // if some exprs are not constant, they will be omitted in this list
      if (trees.size === exprs.size)
        Some(q"(..$trees)")
      else None

    case q"$applier[..$_](..$xs)" if constantApplierCodes.contains(applier.symbol.fullName) =>
      val trees = xs.flatMap(getConstantTree).map(_.asInstanceOf[Tree]) // if some exprs are not constant, they will be omitted in this list
      if (trees.size === xs.size)
        Some(q"$applier(..$trees)")
      else None

    case pq"$extr(..$xs)" =>
      val extrCode = showCode(extr.asInstanceOf[Tree])
      constantExtractorCodes.get(extrCode).flatMap { extractor =>
        val trees = xs.flatMap(getConstantTree).map(_.asInstanceOf[Tree]) // if some exprs are not constant, they will be omitted in this list
        if (trees.size === xs.size)
          Some(q"$extractor(..$trees)")
        else None
      }

    // Unapply with List() doesn't work - can't be matched with pq"$extr(..$xs)" or pq"$extr(...$xs)". Do it by hand via exprTree.children and its children.
    case _ => exprTree.children match {
      case firstChild :: restOfChildren => for {
        extractorHead <- firstChild.children.headOption
        if seqConstantExtractorHeads.contains(extractorHead.symbol.fullName)
        unapplySelector <- firstChild.children.zipWithIndex.find(_._2 === 1).map(_._1) // safe on empty lists
        if unapplySelector.symbol.toString === "value <unapply-selector>"
        extrCode = showCode(exprTree.asInstanceOf[Tree])
        cleanedCode = extrCode.substring(0, 1 + extrCode.indexOf("("))
        extractor <- seqConstantExtractorCodes.get(cleanedCode)
        trees = for {
          child <- restOfChildren
          childConst <- getConstantTree(child).map(_.asInstanceOf[Tree])
        } yield childConst.asInstanceOf[Tree]
        if trees.size === restOfChildren.size

      } yield q"$extractor(..$trees)"

      case Nil => None
    }
  }

  def identToScalaSymbol(ident: Ident): ScalaSymbol = ident.name.decodedName.toString.toScalaSymbol

  /** Convert a term to conjunctive normal form (CNF).
    * CNF is represented as a list of lists of Boolean term trees.
    * For example, `List( List(q"x>0", q"y<x"), List(q"x>z", q"z<1") )` represents `( x > 0 || y < x ) && ( x > z || z < 1)`.
    *
    * @param term Initial expression tree.
    * @return Equivalent expression in CNF. Terms will be duplicated when necessary. No simplification is performed on terms.
    */
  def convertToCNF(term: Tree): List[List[Tree]] = {

    import io.chymyst.util.ConjunctiveNormalForm._

    def normalize(a: Trees#Tree): List[List[Tree]] = convertToCNF(a.asInstanceOf[Tree])

    val ourNegation: (List[List[Tree]]) => List[List[Tree]] = negation((t: Tree) => q"! $t")

    term match {
      case EmptyTree =>
        trueConstant[Tree]
      case q"$a && $b" =>
        conjunction(normalize(a), normalize(b))

      case q"$a || $b" =>
        disjunction(normalize(a), normalize(b))

      case q"if ($a) $b else $c" => // (a' + b)(a + c)
        val aN = normalize(a)
        val bN = normalize(b)
        val cN = normalize(c)
        conjunction(disjunction(ourNegation(aN), bN), disjunction(aN, cN))

      case q"$a ^ $b" => // (a+b)(a'+b')
        val aN = normalize(a)
        val bN = normalize(b)
        conjunction(disjunction(aN, bN), disjunction(ourNegation(aN), ourNegation(bN)))

      case q"! $a" =>
        ourNegation(normalize(a))

      case q"true" =>
        trueConstant[Tree]

      case q"false" =>
        falseConstant[Tree]

      case _ =>
        oneTerm(term)
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
  def getCurrentSymbolOwner: MacroSymbol = {
    val freshName = c.freshName(TypeName("Probe$"))
    val probe = c.typecheck(q""" {class $freshName; ()} """)
    probe match {
      case Block(List(t), _) => t.symbol.owner
    }
  }

  /** Detect whether the symbol `s` is defined inside the scope of the symbol `owner`.
    * Will return true for code like ` val owner = .... { val s = ... }  `
    *
    * @param s     Symbol to be examined.
    * @param owner Owner symbol of the scope to be examined.
    * @return True if `s` is defined inside the scope of `owner`.
    */
  @tailrec
  final def isOwnedBy(s: MacroSymbol, owner: MacroSymbol): Boolean = s.owner match {
    case `owner` =>
      owner =!= NoSymbol
    case `NoSymbol` =>
      false
    case o@_ =>
      isOwnedBy(o, owner)
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
      }.map { case CaseDef(aPattern, aGuard, aBody) => (aPattern, aGuard, aBody) }
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

  /** Input molecules in a reaction must be given using a chemical notation such as `a(x) + b(y) + c(z) => ...`.
    * It is an error to group input molecules such as `a(x) + (b(y) + c(z)) => ...`, or to use pattern grouping such as `q @ a(x) + ...`
    */
  object DetectInvalidInputGrouping extends Traverser {
    var found: Boolean = _

    override def traverse(tree: c.universe.Tree): Unit = tree match {
      case pq"$_ @ $extr1(..$_)"
        if extr1.symbol.fullName === "io.chymyst.jc.$plus" || extr1.tpe <:< typeOf[MolEmitter] =>
        found = true
      case pq"$extr1($_, $extr2($_, $_))"
        if extr1.symbol.fullName === "io.chymyst.jc.$plus" && extr2.symbol.fullName === "io.chymyst.jc.$plus" =>
        found = true
      case _ =>
        super.traverse(tree)
    }

    /** Detect invalid groupings in a pattern matching tree.
      *
      * @param tree A pattern matching tree.
      * @return `true` if an invalid grouping is detected, `false` otherwise.
      */
    def in(tree: Tree): Boolean = {
      found = false
      traverse(tree)
      found
    }
  }

  /** This is the main traverser that gathers information about molecule inputs and outputs in a reaction. */
  class MoleculeInfo(reactionBodyOwner: MacroSymbol) extends Traverser {

    /** Examine an expression tree, looking for molecule expressions.
      *
      * @param reactionPart An expression tree (could be the "case" pattern, the "if" guard, or the reaction body).
      * @return A 4-tuple: List of input molecule patterns, list of output molecule patterns, list of reply action patterns, and list of molecules erroneously used inside pattern matching expressions.
      */
    def from(reactionPart: Tree): (List[(MacroSymbol, InputPatternFlag, Option[InputPatternFlag])], List[(MacroSymbol, OutputPatternFlag, List[OutputEnvironment])], List[(MacroSymbol, OutputPatternFlag, List[OutputEnvironment])], List[MacroSymbol]) = synchronized {
      inputMolecules = mutable.ArrayBuffer()
      outputMolecules = mutable.ArrayBuffer()
      replyActions = mutable.ArrayBuffer()
      moleculesInBinder = mutable.ArrayBuffer()
      traversingBinderNow = false

      lastOutputEnvId = 0
      outputEnv = List()

      traverse(reactionPart)

      (inputMolecules.toList, outputMolecules.toList, replyActions.toList, moleculesInBinder.toList)
    }

    private def renewOutputEnvId(): Unit = {
      lastOutputEnvId += 1
      currentOutputEnvId = lastOutputEnvId
    }

    private var traversingBinderNow: Boolean = _
    private var moleculesInBinder: mutable.ArrayBuffer[MacroSymbol] = _
    private var inputMolecules: mutable.ArrayBuffer[(MacroSymbol, InputPatternFlag, Option[InputPatternFlag])] = _
    private var outputMolecules: mutable.ArrayBuffer[(MacroSymbol, OutputPatternFlag, List[OutputEnvironment])] = _
    private var replyActions: mutable.ArrayBuffer[(MacroSymbol, OutputPatternFlag, List[OutputEnvironment])] = _

    private var lastOutputEnvId: Int = 0
    private var currentOutputEnvId: Int = 0
    private var outputEnv: List[OutputEnvironment] = _

    private def pushEnv(env: OutputEnvironment) =
      outputEnv = env :: outputEnv

    private def getInputFlag(binderTerm: Tree): InputPatternFlag = binderTerm match {
      case Ident(termNames.WILDCARD) =>
        WildcardF
      case Bind(t@TermName(_), Ident(termNames.WILDCARD)) =>
        SimpleVarF(Ident(t), binderTerm, None)
      case _ =>
        getConstantTree(binderTerm)
          .map(t => ConstantPatternF(t.asInstanceOf[Tree]))
          .getOrElse {
            // If we are here, we do not have a constant pattern. It could be either an irrefutable compound pattern such as (_, x, (a,b,_)), or a general other pattern.
            val vars = PatternVars.from(binderTerm)
            val guardTreeOpt = if (isIrrefutablePattern(binderTerm))
              None
            else
              Some(EmptyTree)
            OtherInputPatternF(binderTerm, guardTreeOpt, vars)
          }
    }

    /** We only support one-argument molecules, so here we only inspect the first element in the list of terms. */
    private def getOutputFlag(outputTerms: List[Tree]): OutputPatternFlag = outputTerms match {
      case List(t) => // match a one-element list
        getConstantTree(t).map(tree => ConstOutputPatternF(tree.asInstanceOf[Tree])).getOrElse(OtherOutputPatternF)
      case Nil =>
        ConstOutputPatternF(q"()")
      case _ =>
        OtherOutputPatternF
    }

    private def traverseWithOutputEnv(tree: Trees#Tree, env: OutputEnvironment): Unit = {
      pushEnv(env)
      traverse(tree.asInstanceOf[Tree])
      finishTraverseWithOutputEnv()
    }

    private def finishTraverseWithOutputEnv(): Unit = {
      val currentEnvOption = outputEnv.headOption
      currentEnvOption.foreach { env =>
        currentOutputEnvId = env.id
        outputEnv = outputEnv.drop(1)
      }
    }

    private def isMolecule(t: Trees#Tree): Boolean = t.asInstanceOf[Tree].tpe <:< typeOf[MolEmitter]

    private def isReplyEmitter(t: Trees#Tree): Boolean = t.asInstanceOf[Tree].tpe <:< weakTypeOf[ReplyEmitter[_, _]]

    @SuppressWarnings(Array("org.wartremover.warts.Equals")) // For some reason, q"while($cond) $body" triggers wartremover's error about disabled `==` operator.
    override def traverse(tree: Tree): Unit = {
      tree match {
        // avoid traversing nested reactions: check whether this subtree is a Reaction() value
        case q"io.chymyst.jc.Reaction.apply(..$_)" =>
          ()
        case q"Reaction.apply(..$_)" => // Is this clause ever used?
          ()

        // matcher with a single argument: a(x)
        case UnApply(Apply(Select(t, TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder)) if t.tpe <:< typeOf[MolEmitter] =>
          val flag2Opt = if (t.tpe <:< weakTypeOf[B[_, _]])
            Some(WrongReplyVarF)
          else None
          val flag1 = getInputFlag(binder)
          if (traversingBinderNow) {
            moleculesInBinder.append(t.symbol)
          } else {
            if (flag1.needTraversing) {
              traversingBinderNow = true
              traverse(binder)
              traversingBinderNow = false
            }
            inputMolecules.append((t.symbol, flag1, flag2Opt))
          }

        // matcher with two arguments: a(x, y)
        case UnApply(Apply(Select(t, TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder1, binder2)) if t.tpe <:< typeOf[MolEmitter] =>
          val flag2 = getInputFlag(binder2) match {
            case SimpleVarF(_, _, _) =>
              ReplyVarF(getSimpleVar(binder2))
            case f@_ =>
              WrongReplyVarF // this is an error that we should report later
          }
          val flag1 = getInputFlag(binder1)
          // Perhaps we need to continue to analyze the "binder" (it could be another molecule, which is an error).
          if (traversingBinderNow) {
            moleculesInBinder.append(t.symbol)
          } else {
            if (flag1.needTraversing) {
              traversingBinderNow = true
              traverse(binder1)
              traversingBinderNow = false
            }
            inputMolecules.append((t.symbol, flag1, Some(flag2)))
          }
        // We do not need to traverse binder2 since it's an error (WrongReplyVarF) to have anything other than a SimpleVarF there.

        // After traversing the subtree, we append this molecule information.

        // Matcher with wrong number of arguments - neither 1 nor 2. This seems to never be called, so let's comment it out.
        /*
        case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), _)
          if t.tpe <:< typeOf[Molecule] =>
            inputMolecules.append((t.symbol, WrongReplyVarF, None, getSha1(t)))
          */

        // try-catch-finally construction
        case q"try $a catch { case ..$b } finally $c" =>
          renewOutputEnvId()
          traverseWithOutputEnv(a, FuncBlock(currentOutputEnvId, "try"))
          b.foreach(casedef => traverseWithOutputEnv(casedef, FuncBlock(currentOutputEnvId, "catch-case")))
          traverse(c.asInstanceOf[Tree]) // The `finally` clause will be always evaluated exactly once, so no special environment needed for it.

        // do-while construction
        case q"do $body while($cond)" =>
          renewOutputEnvId()
          traverseWithOutputEnv(body, AtLeastOneEmitted(currentOutputEnvId, "do while"))
          traverseWithOutputEnv(cond, AtLeastOneEmitted(currentOutputEnvId, "condition of do while"))

        // while construction
        case q"while($cond) $body" =>
          renewOutputEnvId()
          traverseWithOutputEnv(cond, AtLeastOneEmitted(currentOutputEnvId, "condition of while"))
          traverseWithOutputEnv(body, FuncBlock(currentOutputEnvId, "while"))

        // Anonymous function
        case q"(..$_) => $body" =>
          renewOutputEnvId()
          traverseWithOutputEnv(body, FuncLambda(currentOutputEnvId))

        // if-then-else
        case q"if($cond) $clause0 else $clause1" =>
          renewOutputEnvId()
          traverseWithOutputEnv(cond, NotLastBlock(currentOutputEnvId))
          renewOutputEnvId()
          traverseWithOutputEnv(clause0, ChooserBlock(currentOutputEnvId, 0, 2))
          traverseWithOutputEnv(clause1, ChooserBlock(currentOutputEnvId, 1, 2))

        // match-case
        case q"$matchExpr match { case ..$cases }" if cases.nonEmpty =>
          renewOutputEnvId()
          traverseWithOutputEnv(matchExpr, NotLastBlock(currentOutputEnvId))
          val total = cases.length
          val haveChooser = total >= 2
          if (haveChooser)
            renewOutputEnvId()
          cases.zipWithIndex.foreach { case (cq"$pat if $guardExpr => $bodyExpr", index) =>
            if (haveChooser) pushEnv(ChooserBlock(currentOutputEnvId, index, total))
            traversingBinderNow = true
            traverse(pat.asInstanceOf[Tree])
            traverse(guardExpr.asInstanceOf[Tree])
            traversingBinderNow = false
            traverse(bodyExpr.asInstanceOf[Tree])
            if (haveChooser) finishTraverseWithOutputEnv()
          }

        // Anonymous partial function
        case q"{ case ..$cases }" =>
          renewOutputEnvId()
          pushEnv(FuncLambda(currentOutputEnvId))
          renewOutputEnvId()
          val total = cases.size
          cases.zipWithIndex.foreach {
            case (cq"$pat if $expr1 => $expr2", index) =>
              pushEnv(ChooserBlock(currentOutputEnvId, index, total))
              traversingBinderNow = true
              traverse(pat.asInstanceOf[Tree])
              traverse(expr1.asInstanceOf[Tree])
              traversingBinderNow = false
              traverse(expr2.asInstanceOf[Tree])
              finishTraverseWithOutputEnv()
          }
          finishTraverseWithOutputEnv()

        /* The expression is `t.f(argumentList)`.
         * This expression could be a molecule emission, but could be any call to `apply` or `timeout`.
         * Other function calls will be analyzed in a later `case` clause.
         *
         * For example, if `c` is a molecule then `c(123)` is matched as `c.apply(List(123))`.
         * Then `t` will be `c` and `f` will be `apply`.
         *
         * When calling emitters with implicit unit arguments, we get Apply((Apply((Select(t@Ident(TermName(_)), TermName(f))), List(jc.UnitArgImplicit)))
         * Note that variables defined inside a reaction have the owner "method applyOrElse", which in turn has the owner the partial function of type "ReactionBody". We don't have a good way of distinguishing them from local variables that copy one of the input emitters!
         * E.g. {case a(x) => val c = a; c() } has the symbol "c" whose owner is the partial function;
         *  but { case a(x) => val c = m[Unit]; site(...); c() } also has a symbol "c" with the same owner.
         */
        case Apply(Select(t@Ident(TermName(_)), TermName(f)), argumentList)
          if moleculeEmitterAllOps contains f ⇒

          // In the output list, we do not include any molecule emitters defined in the inner scope of the reaction.
          val includeThisSymbol = !isOwnedBy(t.symbol.owner, reactionBodyOwner)
          val thisSymbolIsAMolecule = isMolecule(t)
          val thisSymbolIsAReply = isReplyEmitter(t)
          val thisIsAReplyAction = replyEmitterActionOps contains f
          val flag1 = getOutputFlag(argumentList)
          lazy val funcName = s"${t.symbol.fullName}.$f"
          if (flag1.needTraversal) {
            // Traverse the trees of the argument list elements (molecules should only have one argument anyway).
            renewOutputEnvId()
            val newEnv = if (thisSymbolIsAMolecule || thisSymbolIsAReply)
              NotLastBlock(currentOutputEnvId)
            // Molecules and replies are a once-only output environment, so no need to push another environment. However, we need to mark this with NotLastBlock.
            else FuncBlock(currentOutputEnvId, name = funcName)

            pushEnv(newEnv)
            argumentList.foreach(traverse)
            finishTraverseWithOutputEnv()
          }
          if (includeThisSymbol && thisSymbolIsAMolecule) {
            outputMolecules.append((t.symbol, flag1, outputEnv))
          }
          if (thisSymbolIsAReply && thisIsAReplyAction) {
            replyActions.append((t.symbol, flag1, outputEnv))
          }

        /* Select() without Apply(): call zero-argument methods on a reply emitter or molecule emitter.
         * For example, f.name or reply.noReplyAttemptedYet
         * These are not considered as "using" the emitters and do not affect the linearity.
          * We should not add them to any of the output lists.
          */
        case Select(t@Ident(TermName(_)), TermName(f))
          if (isMolecule(t) || isReplyEmitter(t)) && emitterReadOps.contains(f) ⇒
          ()

        // tuple
        case q"(..$args)"
          if args.size >= 2 =>
          args.foreach(t => traverse(t.asInstanceOf[Tree]))

        // other function applications
        case q"$f[..$_](..$args)"
          if args.nonEmpty =>
          val fullName = f.asInstanceOf[Tree].symbol.fullName
          if (constantApplierCodes.contains(fullName)) {
            // The function is one of the known once-only evaluating functions such as Some(), List(), etc.
            // In that case, we don't need to set a special environment, since a once-only environment is equivalent to no environment.
            // We just traverse the tree and harvest the molecules normally.
            // However, NotLastBlock() must be set, because molecule emission is not the last computation.
            renewOutputEnvId()
            pushEnv(NotLastBlock(currentOutputEnvId))
            super.traverse(tree) // avoid infinite loop -- we are not destructuring this function application
            finishTraverseWithOutputEnv()
          } else if (emitMultipleFunctionCodes.contains(fullName)) {
            // We are under the a() + b() emission construct.
            // In that case, we don't need to set NotLastBlock on any of the emitted molecules.
            // We just traverse the tree and harvest the molecules normally.
            super.traverse(tree) // avoid infinite loop -- we are not destructuring this function application
          } else {
            renewOutputEnvId()
            traverseWithOutputEnv(f, NotLastBlock(currentOutputEnvId))
            renewOutputEnvId()
            pushEnv(FuncBlock(currentOutputEnvId, name = fullName))
            val isIterating = iteratingFunctionCodes.contains(fullName)
            args.foreach { t =>
              // Detect whether the function takes a function type, and whether `t` is a molecule emitter.
              if (isIterating && isMolecule(t)) {
                // This is an iterator that takes a molecule emitter, in a short syntax,
                // e.g. `(0 until n).foreach(a)` where `a : M[Int]`.
                // In that case, the molecule could be emitted zero or more times.
                outputMolecules.append((t.asInstanceOf[Tree].symbol, EmptyOutputPatternF, outputEnv))
              }
              traverse(t.asInstanceOf[Tree])
            }
            finishTraverseWithOutputEnv()
          }

        // This term is a bare identifier.
        case Ident(TermName(_)) if isReplyEmitter(tree) =>
          // All other use of reply emitters must be logged, including just copying an emitter itself.
          replyActions.append((tree.asInstanceOf[Tree].symbol, EmptyOutputPatternF, outputEnv))

        // Statement block with several statements. We will mark all but the last statement in the block with a NotLastBlock() environment.
        case q"{..$statements}" if statements.length > 1 ⇒
          // Set the output environment while traversing statements of the block, except for the last statement.
          renewOutputEnvId()
          pushEnv(NotLastBlock(currentOutputEnvId))
          statements.indices.foreach { i ⇒
            val s = statements(i)
            if (i + 1 === statements.length)
              finishTraverseWithOutputEnv()
            traverse(s.asInstanceOf[Tree])
          }

        case _ => super.traverse(tree)
      }
    }
  }

  def moleculeIndicesConstrainedByGuard(guardVarList: List[Ident], inputMoleculeFlags: List[InputPatternFlag]): List[Int] = {
    inputMoleculeFlags.zipWithIndex.filter { case (flag, _) ⇒
      guardVarList.exists(flag.containsVar)
    }
      .map(_._2)
      .sorted
      .distinct
  }

  def guardVarsConstrainOnlyThisMolecule(guardVarList: List[Ident], moleculeFlag: InputPatternFlag): Boolean =
    guardVarList.forall(moleculeFlag.containsVar)

  def mergeGuards(treeVarsSeq: List[(Tree, List[Ident])]): Option[Tree] =
    treeVarsSeq
      .map(_._1)
      .reduceOption { (g1, g2) => q"$g1 && $g2" }

  // This boilerplate is necessary for being able to use PatternType values in quasiquotes.
  implicit val liftableInputPatternFlag: Liftable[InputPatternFlag] = Liftable[InputPatternFlag] {
    case WildcardF =>
      q"_root_.io.chymyst.jc.WildcardInput"
    case ConstantPatternF(tree) =>
      q"_root_.io.chymyst.jc.ConstInputPattern($tree)"
    case SimpleVarF(v, binder, cond) =>
      val guardFunction = cond.map(c => matcherFunction(binder, c, List(v)))
      q"_root_.io.chymyst.jc.SimpleVarInput(${identToScalaSymbol(v)}, $guardFunction)"
    case OtherInputPatternF(matcherTree, guardTreeOpt, vars) =>
      q"_root_.io.chymyst.jc.OtherInputPattern(${matcherFunction(matcherTree, guardTreeOpt.getOrElse(EmptyTree), vars)}, ${vars.map(identToScalaSymbol)}, ${guardTreeOpt.isEmpty})"
    case _ =>
      q"_root_.io.chymyst.jc.WildcardInput" // this case will not be encountered here; we are conflating InputPatternFlag and ReplyInputPatternFlag
  }

  implicit val liftableOutputPatternType: Liftable[OutputPatternType] = Liftable[OutputPatternType] {
    case ConstOutputPattern(v) =>
      q"_root_.io.chymyst.jc.ConstOutputPattern(${v.asInstanceOf[Tree]})" // When we lift it here, `v` always has type `Tree`.
    case OtherOutputPattern =>
      q"_root_.io.chymyst.jc.OtherOutputPattern"
  }

  implicit val liftableOutputEnvironment: Liftable[OutputEnvironment] = Liftable[OutputEnvironment] {
    case ChooserBlock(id, clause, total) =>
      q"_root_.io.chymyst.jc.ChooserBlock($id, $clause, $total)"
    case FuncBlock(id, name) =>
      q"_root_.io.chymyst.jc.FuncBlock($id, $name)"
    case FuncLambda(id) =>
      q"_root_.io.chymyst.jc.FuncLambda($id)"
    case AtLeastOneEmitted(id, name) =>
      q"_root_.io.chymyst.jc.AtLeastOneEmitted($id, $name)"
    case NotLastBlock(id) =>
      q"_root_.io.chymyst.jc.NotLastBlock($id)"
  }

  /** Build an error message about incorrect usage of chemical notation.
    * The phrase looks like this: (Beginning of phrase) must (Phrase connector) (What was incorrect) (molecule list)
    *
    * @param what        Beginning of phrase.
    * @param patternWhat What was incorrect about the molecule usage.
    * @param molecules   List of molecules (or other objects) that were incorrectly used.
    * @param connector   Phrase connector. By default: `"not contain a pattern that"`.
    * @param method      How to report the error; by default, will use `c.error`.
    * @tparam T Type of molecule or other object.
    */
  def maybeError[T](what: String, patternWhat: String, molecules: Seq[T], connector: String = "not contain a pattern that", method: (c.Position, String) => Unit = c.error): Unit = {
    if (molecules.nonEmpty)
      method(c.enclosingPosition, s"$what must $connector $patternWhat (${molecules.mkString(", ")})")
  }

  def reportError(message: String): Unit = c.error(c.enclosingPosition, message)

  // This method helps move `shrink` out of the macro namespace, by making the type `Any` available.
  // But it will be always called on a pair of Trees.
  def equalsToTree(a: Any, b: Any): Boolean = a match {
    case x: Tree => x.equalsStructure(b.asInstanceOf[Tree])
  }

  class ReplaceStaticEmits(reactionBodyOwner: MacroSymbol) extends Transformer {
    override def transform(tree: Tree): Tree = tree match {

      // avoid traversing nested reactions: check whether this subtree is a Reaction() value
      case q"io.chymyst.jc.Reaction.apply(..$_)" =>
        tree
      case q"Reaction.apply(..$_)" => // Is this clause ever used?
        tree

      case q"$f.apply[..$t](...$arg)" if arg.nonEmpty &&
        f.tpe <:< typeOf[M[_]] &&
        !isOwnedBy(f.asInstanceOf[Tree].symbol.owner, reactionBodyOwner)
      =>
        c.typecheck(q"$f.applyStatic[..$t](...$arg)")

      // Replace `isDefinedAt` by `true` in the reaction body since the reaction scheduler
      // will check the molecule values before running a reaction.
      // This fails due to `Error: scalac: Error: Position.point on NoPosition. UnsupportedOperationException. Position.scala:95`
      /*
    case q"$mods def isDefinedAt(..$args) = $body" ⇒
      c.typecheck(q"$mods def isDefinedAt(..$args) = true")
      */
      case _ => super.transform(tree)
    }
  }

  /* This code has been commented out after a lengthy but fruitless exploration of valid ways of modifying the reaction body.

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
