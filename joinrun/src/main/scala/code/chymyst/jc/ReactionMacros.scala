package code.chymyst.jc

import Core._

import scala.collection.mutable
import scala.reflect.macros.blackbox
import scala.annotation.tailrec
import scala.reflect.api.Trees

class ReactionMacros(override val c: blackbox.Context) extends CommonMacros(c) {

  import c.universe._

  // Must avoid confusing these types.
  type MacroSymbol = c.Symbol
  type ScalaSymbol = scala.Symbol

  // A singleton reaction must start with _ and must emit some output molecules (which we check later).
  def isSingletonReaction(pattern: Tree, guard: Tree, body: Tree): Boolean = pattern match {
    case Ident(termNames.WILDCARD) => true
    case _ => false
  }

  def getSimpleVar(binderTerm: Tree): Ident = binderTerm match {
    case Bind(t@TermName(n), Ident(termNames.WILDCARD)) => Ident(t)
  }

  private val constantExtractorCodes = Map(
    "scala.Some" -> q"Some",
    "scala.Symbol" -> q"Symbol",
    "scala.`package`.Left" -> q"Left",
    "scala.`package`.Right" -> q"Right"
  )

  private val constantApplierCodes = Set(
    "scala.Some.apply",
    "scala.util.Left.apply",
    "scala.util.Right.apply",
    "scala.Symbol.apply",
    "scala.collection.immutable.List.apply"
  )

  private val seqConstantExtractorCodes = Map(
    "scala.collection.immutable.List(" -> q"List"
  )

  private val seqConstantExtractorHeads = Set("scala.collection.generic.SeqFactory.unapplySeq")

  private val onceOnlyFunctionCodes = constantApplierCodes ++ Set(
    "code.chymyst.jc.EmitMultiple",
    "code.chymyst.jc.EmitMultiple.$plus"
  )

  private val iteratingFunctionCodes = Set(
    "scala.collection.TraversableLike.map",
    "scala.collection.immutable.Range.foreach"
  )

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
      val typeSymbolOfExtr = extr.tpe.resultType.typeSymbol // Note: extr.tpe.symbol is NoSymbol since it's a pattern matcher tree.
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
         | q"immutable.this.Nil" =>
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

    import ConjunctiveNormalForm._

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

  /** Input molecules in a reaction must be given using a chemical notation such as `a(x) + b(y) + c(z) => ...`.
    * It is an error to group input molecules such as `a(x) + (b(y) + c(z)) => ...`, or to use pattern grouping such as `q @ a(x) + ...`
    */
  object DetectInvalidInputGrouping extends Traverser {
    var found: Boolean = _

    override def traverse(tree: c.universe.Tree): Unit = tree match {
      case pq"$_ @ $extr1(..$_)"
        if extr1.symbol.fullName === "code.chymyst.jc.$plus" || extr1.tpe <:< typeOf[Molecule] =>
        found = true
      case pq"$extr1($_, $extr2($_, $_))"
        if extr1.symbol.fullName === "code.chymyst.jc.$plus" && extr2.symbol.fullName === "code.chymyst.jc.$plus" =>
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

  class MoleculeInfo(reactionBodyOwner: MacroSymbol) extends Traverser {

    /** Examine an expression tree, looking for molecule expressions.
      *
      * @param reactionPart An expression tree (could be the "case" pattern, the "if" guard, or the reaction body).
      * @return A 4-tuple: List of input molecule patterns, list of output molecule patterns, list of reply action patterns, and list of molecules erroneously used inside pattern matching expressions.
      */
    def from(reactionPart: Tree): (List[(MacroSymbol, InputPatternFlag, Option[InputPatternFlag])], List[(MacroSymbol, OutputPatternFlag, List[OutputEnvironment])], List[(MacroSymbol, OutputPatternFlag, List[OutputEnvironment])], List[MacroSymbol]) = {
      inputMolecules = mutable.ArrayBuffer()
      outputMolecules = mutable.ArrayBuffer()
      replyActions = mutable.ArrayBuffer()
      moleculesInBinder = mutable.ArrayBuffer()
      traversingBinderNow = false

      lastOutputEnvId = 0
      outputEnv = mutable.Stack()

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
    private var outputEnv: mutable.Stack[OutputEnvironment] = _

    /** Detect whether the symbol `s` is defined inside the scope of the symbol `owner`.
      * Will return true for code like ` val owner = .... { val s = ... }  `
      *
      * @param s     Symbol to be examined.
      * @param owner Owner symbol of the scope to be examined.
      * @return True if `s` is defined inside the scope of `owner`.
      */
    @tailrec
    private def isOwnedBy(s: MacroSymbol, owner: MacroSymbol): Boolean = s.owner match {
      case `owner` =>
        owner =!= NoSymbol
      case `NoSymbol` =>
        false
      case o@_ =>
        isOwnedBy(o, owner)
    }

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
      case List(t) =>
        getConstantTree(t).map(tree => ConstOutputPatternF(tree.asInstanceOf[Tree])).getOrElse(OtherOutputPatternF)
      case Nil =>
        ConstOutputPatternF(q"()")
      case _ =>
        OtherOutputPatternF
    }

    private def traverseWithOutputEnv(tree: Trees#Tree, env: OutputEnvironment): Unit = {
      outputEnv.push(env)
      traverse(tree.asInstanceOf[Tree])
      finishTraverseWithOutputEnv()
    }

    private def finishTraverseWithOutputEnv(): Unit = {
      currentOutputEnvId = outputEnv.pop().id
    }

    private def isMolecule(t: Trees#Tree): Boolean = t.asInstanceOf[Tree].tpe <:< typeOf[Molecule]

    private def isReplyValue(t: Trees#Tree): Boolean = t.asInstanceOf[Tree].tpe <:< weakTypeOf[AbsReplyValue[_, _]]

    @SuppressWarnings(Array("org.wartremover.warts.Equals"))
    override def traverse(tree: Tree): Unit = {
      tree match {
        // avoid traversing nested reactions: check whether this subtree is a Reaction() value
        case q"code.chymyst.jc.Reaction.apply(..$_)" =>
          ()
        case q"Reaction.apply(..$_)" => // Is this clause ever used?
          ()

        // matcher with a single argument: a(x)
        case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder)) if t.tpe <:< typeOf[Molecule] =>
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
        case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder1, binder2)) if t.tpe <:< typeOf[Molecule] =>
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
          traverse(cond.asInstanceOf[Tree])
          renewOutputEnvId()
          traverseWithOutputEnv(clause0, ChooserBlock(currentOutputEnvId, 0, 2))
          traverseWithOutputEnv(clause1, ChooserBlock(currentOutputEnvId, 1, 2))

        // match-case
        case q"$matchExpr match { case ..$cases }" if cases.size >= 2 =>
          traverse(matchExpr.asInstanceOf[Tree])
          renewOutputEnvId()
          val total = cases.size
          cases.zipWithIndex.foreach { case (cq"$pat if $guardExpr => $bodyExpr", index) =>
            outputEnv.push(ChooserBlock(currentOutputEnvId, index, total))
            traversingBinderNow = true
            traverse(pat.asInstanceOf[Tree])
            traverse(guardExpr.asInstanceOf[Tree])
            traversingBinderNow = false
            traverse(bodyExpr.asInstanceOf[Tree])
            finishTraverseWithOutputEnv()
          }

        // Anonymous partial function
        case q"{ case ..$cases }" =>
          renewOutputEnvId()
          outputEnv.push(FuncLambda(currentOutputEnvId))
          renewOutputEnvId()
          val total = cases.size
          cases.zipWithIndex.foreach {
            case (cq"$pat if $expr1 => $expr2", index) =>
              outputEnv.push(ChooserBlock(currentOutputEnvId, index, total))
              traversingBinderNow = true
              traverse(pat.asInstanceOf[Tree])
              traverse(expr1.asInstanceOf[Tree])
              traversingBinderNow = false
              traverse(expr2.asInstanceOf[Tree])
              finishTraverseWithOutputEnv()
          }
          finishTraverseWithOutputEnv()

        // possibly a molecule emission, but could be any function call
        case Apply(Select(t@Ident(TermName(name)), TermName(f)), argumentList)
          if f === "apply" || f === "checkTimeout" || f === "timeout" =>

          // In the output list, we do not include any molecule emitters defined in the inner scope of the reaction.
          val includeThisSymbol = !isOwnedBy(t.symbol.owner, reactionBodyOwner)
          val thisSymbolIsAMolecule = isMolecule(t)
          val thisSymbolIsAReply = isReplyValue(t)
          val flag1 = getOutputFlag(argumentList)
          if (flag1.needTraversal) {
            // Traverse the trees of the argument list elements (molecules should only have one argument anyway).
            if (thisSymbolIsAMolecule || thisSymbolIsAReply) {
              argumentList.foreach(traverse)
            } else {
              renewOutputEnvId()
              outputEnv.push(FuncBlock(currentOutputEnvId, name = s"${t.symbol.fullName}.$f"))
              argumentList.foreach(traverse)
              finishTraverseWithOutputEnv()
            }
          }

          if (includeThisSymbol) {
            if (thisSymbolIsAMolecule) {
              outputMolecules.append((t.symbol, flag1, outputEnv.toList))
            }
          }
          if (thisSymbolIsAReply) {
            replyActions.append((t.symbol, flag1, outputEnv.toList))
          }

        // tuple
        case q"(..$args)"
          if args.size >= 2 =>
          args.foreach(t => traverse(t.asInstanceOf[Tree]))

        // other function applications
        case q"$f[..$_](..$args)"
          if args.nonEmpty =>
          val fullName = f.asInstanceOf[Tree].symbol.fullName
          if (onceOnlyFunctionCodes.contains(fullName))
          // The function is one of the known once-only evaluating functions such as Some(), List(), etc.
          // In that case, we don't need to do anything special - just traverse the tree and harvest the molecules normally.
            super.traverse(tree)

          else {
            traverse(f.asInstanceOf[Tree])
            renewOutputEnvId()
            val isIterating = iteratingFunctionCodes.contains(fullName)
            val newEnv =
              if (isIterating) {
                FuncBlock(currentOutputEnvId, name = fullName)
              } else {
                FuncBlock(currentOutputEnvId, name = fullName)
              }
            outputEnv.push(newEnv)

            args.foreach { t =>
              // Detect whether the function takes a function type, and whether `t` is a molecule emitter.
              if (isIterating && (isMolecule(t) || isReplyValue(t))) {
                // In that case, the molecule could be emitted zero or more times.
                outputMolecules.append((t.asInstanceOf[Tree].symbol, OtherOutputPatternF, outputEnv.toList))
              } else
                traverse(t.asInstanceOf[Tree])
            }
            finishTraverseWithOutputEnv()
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
    case WildcardF =>
      q"_root_.code.chymyst.jc.WildcardInput"
    case ConstantPatternF(tree) =>
      q"_root_.code.chymyst.jc.SimpleConstInput($tree)"
    case SimpleVarF(v, binder, cond) =>
      val guardFunction = cond.map(c => matcherFunction(binder, c, List(v)))
      q"_root_.code.chymyst.jc.SimpleVarInput(${identToScalaSymbol(v)}, $guardFunction)"
    case OtherInputPatternF(matcherTree, guardTreeOpt, vars) =>
      q"_root_.code.chymyst.jc.OtherInputPattern(${matcherFunction(matcherTree, guardTreeOpt.getOrElse(EmptyTree), vars)}, ${vars.map(identToScalaSymbol)}, ${guardTreeOpt.isEmpty})"
    case _ =>
      q"_root_.code.chymyst.jc.WildcardInput" // this case will not be encountered here; we are conflating InputPatternFlag and ReplyInputPatternFlag
  }

  implicit val liftableOutputPatternFlag: Liftable[OutputPatternFlag] = Liftable[OutputPatternFlag] {
    case ConstOutputPatternF(tree) =>
      q"_root_.code.chymyst.jc.SimpleConstOutput($tree)"
    case OtherOutputPatternF =>
      q"_root_.code.chymyst.jc.OtherOutputPattern"
  }

  implicit val liftableOutputPatternType: Liftable[OutputPatternType] = Liftable[OutputPatternType] {
    case SimpleConstOutput(v) =>
      q"_root_.code.chymyst.jc.SimpleConstOutput(${v.asInstanceOf[Tree]})" // When we lift it here, it always has type `Tree`.
    case OtherOutputPattern =>
      q"_root_.code.chymyst.jc.OtherOutputPattern"
  }

  implicit val liftableOutputEnvironment: Liftable[OutputEnvironment] = Liftable[OutputEnvironment] {
    case ChooserBlock(id, clause, total) =>
      q"_root_.code.chymyst.jc.ChooserBlock($id, $clause, $total)"
    case FuncBlock(id, name) =>
      q"_root_.code.chymyst.jc.FuncBlock($id, $name)"
    case FuncLambda(id) =>
      q"_root_.code.chymyst.jc.FuncLambda($id)"
    case AtLeastOneEmitted(id, name) =>
      q"_root_.code.chymyst.jc.AtLeastOneEmitted($id, $name)"
  }

  /** Build an error message about incorrect usage of chemical notation.
    * The phrase looks like this: (Beginning of phrase) must (Phrase connector) (What was incorrect) (molecule list)
    *
    * @param what        Beginning of phrase.
    * @param patternWhat What was incorrect about the molecule usage.
    * @param molecules   List of molecules (or other objects) that were incorrectly used.
    * @param connector   Phrase connector. By default: `"not contain a pattern that"`.
    * @param method      How to report the error; by default using [[scala.reflect.macros.FrontEnds.error]].
    * @tparam T Type of molecule or other object.
    */
  def maybeError[T](what: String, patternWhat: String, molecules: Seq[T], connector: String = "not contain a pattern that", method: (c.Position, String) => Unit = c.error): Unit = {
    if (molecules.nonEmpty)
      method(c.enclosingPosition, s"$what must $connector $patternWhat (${molecules.mkString(", ")})")
  }

  def reportError(message: String): Unit = c.error(c.enclosingPosition, message)

  def equalsInMacro(a: Any, b: Any): Boolean = a match {
    case x: Tree => x.equalsStructure(b.asInstanceOf[Tree])
    //    case _ => a === b  // this is never used
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
