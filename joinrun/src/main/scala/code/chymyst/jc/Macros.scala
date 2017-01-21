package code.chymyst.jc

import Core._

import scala.language.experimental.macros
import scala.reflect.macros.blackbox
import scala.reflect.NameTransformer.LOCAL_SUFFIX_STRING

class CommonMacros(val c: blackbox.Context) {

  import c.universe._

  def rawTreeImpl(x: c.Expr[Any]): Tree = {
    import c.universe._
    val result = showRaw(x.tree)
    q"$result"
  }

  def getNameImpl: Tree = {
    import c.universe._
    val s = getEnclosingName
    q"$s"
  }

  /** Detect the enclosing name of an expression.
    * For example: `val x = "name is " + getName` will set x to the string "name is x".
    *
    * @return String that represents the name of the enclosing value.
    */
  def getEnclosingName: String =
    c.internal.enclosingOwner.name.decodedName.toString
      .stripSuffix(LOCAL_SUFFIX_STRING).stripSuffix("$lzy")

  // Classes need to be defined at top level because we can't have case classes local to a function scope.
  // However, we need to use path-dependent types such as `Ident` and `Tree`.
  // We could use type parameters for every path-dependent type, but it's unwieldy (although it works).
  // Instead, we just put them inside the `CommonMacros` class. The price for this is a warning "The outer reference in this type test cannot be checked at run time."
  // This warning is discussed here, http://stackoverflow.com/questions/16450008/typesafe-swing-events-the-outer-reference-in-this-type-test-cannot-be-checked-a

  /** Describes the pattern matcher for input molecules.
    * Possible values:
    * Wildcard: a(_)
    * SimpleVar: a(x)
    * SimpleConst: a(1)
    * WrongReplyVar: the second matcher for blocking molecules is not a simple variable
    * OtherPattern: we don't recognize the pattern (could be a case class or a general Unapply expression)
    */
  sealed trait InputPatternFlag {
    def patternSha1(showCode: Tree => String): String = ""

    def notReplyValue: Boolean = true

    /** Does this pattern contain a nontrivial syntax tree that could contain other molecules?
      *
      * @return true or false
      */
    def needTraversing: Boolean = false

    def varNames: List[Ident] = Nil
  }

  case object WildcardF extends InputPatternFlag

  /** Represents a reply pattern consisting of a simple variable.
    *
    * @param replyVar The Ident of a reply pattern variable.
    */
  final case class ReplyVarF(replyVar: Ident) extends InputPatternFlag {
    override def notReplyValue: Boolean = false
  }

  /** Represents a pattern match with a simple pattern variable, such as `a(x)`
    *
    * @param v The Ident of the pattern variable.
    */
  final case class SimpleVarF(v: Ident, binder: Tree, cond: Option[Tree]) extends InputPatternFlag {
    override def varNames: List[Ident] = List(v)

    override def patternSha1(showCode: Tree => String): String = cond.map(c => getSha1String(showCode(c))).getOrElse("")
  }

  /** Represents an error situation.
    * The reply pseudo-molecule must be bound to a simple variable, but we found another pattern instead.
    */
  case object WrongReplyVarF extends InputPatternFlag

  /** The pattern represents a constant, which can be a literal constant such as "abc" or a compound type such as (2,3) or (Some(2),3,4).
    * The value v represents a value of the [T] type of M[T] or B[T,R].
    */
  final case class ConstantPatternF(v: Tree) extends InputPatternFlag {
    override def patternSha1(showCode: Tree => String): String = getSha1String(showCode(v))
  }

  /** Nontrivial pattern matching expression that could contain unapply, destructuring, pattern @ variables, etc.
    * For example, if c is a molecule then this could be c( z@(x, Some(y)) )
    * In that case, vars = List("z", "x", "y") and matcher = { case z@(x, Some(y)) => (z, x, y) }
    *
    * @param matcher Tree of a partial function of type Any => Any.
    * @param guard   `None` if the pattern is irrefutable; `Some(guard expression tree)` if the pattern is not irrefutable and potentially requires a guard condition.
    * @param vars    List of pattern variable names in the order of their appearance in the syntax tree.
    */
  final case class OtherInputPatternF(matcher: Tree, guard: Option[Tree], vars: List[Ident]) extends InputPatternFlag {
    override def needTraversing: Boolean = true

    override def varNames: List[Ident] = vars

    override def patternSha1(showCode: Tree => String): String = getSha1String(showCode(matcher) + showCode(guard.getOrElse(EmptyTree)))
  }

  /** Describes the pattern matcher for output molecules.
    * Possible values:
    * ConstOutputPatternF(x): a(123) or a(Some(4)), etc.
    * EmptyOutputPatternF: a() - this only happens with `Unit` values.
    * OtherOutputPatternF: a(x), a(x+y), or any other kind of expression.
    */
  sealed trait OutputPatternFlag {
    def needTraversal: Boolean = false
  }

  case object OtherOutputPatternF extends OutputPatternFlag {
    override def needTraversal: Boolean = true
  }

  case object EmptyOutputPatternF extends OutputPatternFlag

  final case class ConstOutputPatternF(v: Tree) extends OutputPatternFlag

}

final class MoleculeMacros(override val c: blackbox.Context) extends CommonMacros(c) {

  import c.universe._

  def mImpl[T: c.WeakTypeTag]: c.universe.Tree = {
    val moleculeName = getEnclosingName
    val moleculeValueType = c.weakTypeOf[T]
    q"new M[$moleculeValueType]($moleculeName)"
  }

  // Does providing an explicit return type here as c.Expr[...] helps anything? Looks like it doesn't, so far.
  def bImpl[T: c.WeakTypeTag, R: c.WeakTypeTag]: Tree = {

    val moleculeName = getEnclosingName
    val moleculeValueType = c.weakTypeOf[T]
    val replyValueType = c.weakTypeOf[R]

    q"new B[$moleculeValueType,$replyValueType]($moleculeName)"
  }

}

final class BlackboxMacros(override val c: blackbox.Context) extends ReactionMacros(c) {

  import c.universe._

  // This is the main method that gathers the reaction info and performs some preliminary static analysis.
  def buildReactionImpl(reactionBody: c.Expr[ReactionBody]): c.Expr[Reaction] = GetReactionCases.from(reactionBody.tree) match {
    // Note: `caseDefs` should not be an empty list because that's a typecheck error (`go` only accepts a partial function, so at least one `case` needs to be given).
    // However, the user could be clever and write `val body = new PartialFunction...; go(body)`. We do not allow this because `go` needs to see the entire reaction body.
    case List((pattern, guard, body)) =>
      buildReactionValueImpl(reactionBody, pattern, guard, body)
    case Nil =>
      reportError("No `case` clauses found: Reactions must be defined inline with the `go { case ... => ... }` syntax")
      null
    case _ =>
      reportError("Reactions must contain only one `case` clause")
      null
  }

  def buildReactionValueImpl(reactionBody: c.Expr[ReactionBody], pattern: Tree, guard: Tree, body: Tree): c.Expr[Reaction] = {

    if (DetectInvalidInputGrouping.in(pattern))
      reportError("Reaction's input molecules must be grouped to the left in chemical notation, and have no @-pattern variables")

    val moleculeInfoMaker = new MoleculeInfo(getCurrentSymbolOwner)

    val (patternIn, patternOut, patternReply, wrongMoleculesInInput) = moleculeInfoMaker.from(pattern) // patternOut and patternReply should be empty
    maybeError("input molecule patterns", "emits output molecules", patternOut)
    maybeError("input molecule patterns", "perform any reply actions", patternReply, "not")
    maybeError("input molecules", "uses other molecules inside molecule value patterns", wrongMoleculesInInput)

    val (guardIn, guardOut, guardReply, wrongMoleculesInGuard) = moleculeInfoMaker.from(guard) // guard in/out/reply lists should be all empty
    maybeError("input guard", "matches on additional input molecules", guardIn.map(_._1))
    maybeError("input guard", "emit any output molecules", guardOut.map(_._1), "not")
    maybeError("input guard", "perform any reply actions", guardReply.map(_._1), "not")
    maybeError("input guard", "uses other molecules inside molecule value patterns", wrongMoleculesInGuard)

    val (bodyIn, bodyOut, bodyReply, wrongMoleculesInBody) = moleculeInfoMaker.from(body) // bodyIn should be empty
    maybeError("reaction body", "matches on additional input molecules", bodyIn.map(_._1))
    maybeError("reaction body", "uses other molecules inside molecule value patterns", wrongMoleculesInBody)

    val guardCNF: List[List[Tree]] = convertToCNF(guard) // Conjunctive normal form of the guard condition. In this CNF, `true` is List() and `false` is List(List()).

    // If any of the CNF clauses is empty, the entire guard is identically `false`. This is an error condition: reactions should not be permanently prohibited.
    if (guardCNF.exists(_.isEmpty)) {
      reportError("Reaction must not have an identically false guard condition")
    }

    // If the CNF is empty, the entire guard is identically `true`. We can remove it altogether.
    val isGuardAbsent = guardCNF.isEmpty
    //    || (guard match {
    //      case EmptyTree => true;
    //      case _ => false
    //    })

    val guardVarsSeq: List[(Tree, List[Ident])] = guardCNF.map {
      guardDisjunctions =>
        val mergedDisjunction = guardDisjunctions.reduceOption((g1, g2) => q"$g1 || $g2").getOrElse(q"false")
        (mergedDisjunction, GuardVars.fromFlags(mergedDisjunction, patternIn.map(_._2)))
    }

    // For each guard clause, first determine whether this guard clause is static, relevant to a single molecule, or binds several molecules together.
    // Concatenate all static clauses together into a `() => Boolean`.
    // For each single-molecule clause, append it as a guard condition to the matcher of that molecule and modify the input flag accordingly.
    // For each multiple-molecule clause, create a separate guard condition and tag it with the list of pattern variables it uses.

    val (staticGuardVarsSeq, moleculeGuardVarsSeq) = guardVarsSeq.partition(_._2.isEmpty)

    val allBinderVars = patternIn.flatMap(_._2.varNames)

    val allGuardVars = moleculeGuardVarsSeq.flatMap(_._2)

    // To avoid problems with constructing a partially typed tree, we need to put types on binder variables and remove owners from guard tree symbols, and then untypecheck the tree.
    // These functions perform all that.
    //
    // The variables in the binder are replaced by explicit typed match, e.g. a(Some(x)) is replaced by a(Some(x: Int)).
    def replaceVarsInBinder(binderTree: Tree): Tree = ReplaceVars.in(binderTree, allGuardVars, inBinder = true)

    // The variables in the guard are replaced by variable Idents from the binder. This hopefully resolves the ownership problems (although I need to look further into that).
    def replaceVarsInGuardTree(guardTree: Tree): Tree = ReplaceVars.in(guardTree, allBinderVars, inBinder = false)

    // Eventually it will be necessary to go through the source code of scala/async and through the "Macrology 201" course here, https://github.com/scalamacros/macrology201/commits/part1
    // And perhaps use https://github.com/scalamacros/resetallattrs

    val staticGuardTree: Option[Tree] = staticGuardVarsSeq // We need to merge all these guard clauses.
      .map(_._1)
      .reduceOption { (g1, g2) => q"$g1 && $g2" }
      .map(guardTree => q"() => $guardTree")

    // Merge the guard information into the individual input molecule infos. The result, patternInWithMergedGuards, replaces patternIn.
    val patternInWithMergedGuardsAndIndex = patternIn.zipWithIndex // patternInWithMergedGuards has same type as patternIn, except for the index
      .map {
      case ((mol, flag, replyFlag), i) =>
        val mergedGuardOpt = moleculeGuardVarsSeq
          .filter { case (g, vars) => guardVarsConstrainOnlyThisMolecule(vars, flag) }
          .map(_._1)
          .reduceOption { (g1, g2) => q"$g1 && $g2" }
          .map(t => replaceVarsInGuardTree(t))

        val mergedFlag = flag match {
          case SimpleVarF(v, binder, _) =>
            mergedGuardOpt.map(guardTree => SimpleVarF(v, replaceVarsInBinder(binder), Some(guardTree))).getOrElse(flag) // a(x) if x>0 is replaced with a(x : check if x>0). Let's not replace vars in binder in this case?
          case OtherInputPatternF(matcher, _, vars) =>
            mergedGuardOpt.map(guardTree => OtherInputPatternF(replaceVarsInBinder(matcher), Some(guardTree), vars)).getOrElse(flag) // We can't have a nontrivial guardTree in patternIn, so we replace it here with the new guardTree.
          case ConstantPatternF(Literal(Constant(()))) =>
            WildcardF // Replace Unit constant values with wildcards.
          case _ => flag
        }

        (mol, i, mergedFlag, replyFlag)
    }

    val allInputMatchersAreTrivial = patternInWithMergedGuardsAndIndex.forall {
      case (_, _, SimpleVarF(_, _, None), _)
           | (_, _, WildcardF, _)
           | (_, _, OtherInputPatternF(_, None, _), _) =>
        true
      case _ => false
    }

    val crossGuardsAndCodes: List[(Tree, String)] = moleculeGuardVarsSeq // The "cross guards" are guard clauses whose variables do not all belong to any single molecule's matcher.
      .filter { case (_, vars) => patternIn.forall { case (_, flag, _) => !guardVarsConstrainOnlyThisMolecule(vars, flag) } }
      .map { case (guardTree, vars) =>
        // At this point, we map over only the cross-molecule guard clauses.
        // For each guard clause, we will produce a closure that takes all the vars as parameters and evaluates the guardTree.

        // TODO: collect all guard clauses that pertain to the same subset of molecules into one guard clause.
        // Determine which molecules we are constraining in this guard. Collect the binders and the indices of all these molecules.
        val indicesAndBinders: List[(Int, Tree)] = patternIn.zipWithIndex.flatMap {
          // Find all input molecules that have some pattern variables; omit wildcards and constants here.
          case ((_, flag, _), i) => flag match {
            case SimpleVarF(v, binder, _) => Some((flag, i, binder, List(v)))
            case OtherInputPatternF(matcher, _, vs) => Some((flag, i, matcher, vs))
            case _ => None
          }
        } // Find all input molecules constrained by the guard (guardTree, vars) that we are iterating with.
          .filter { case (flag, _, _, _) => guardVarsConstrainThisMolecule(vars, flag) }
          .map { case (_, i, binder, _) => (i, binder) }

        // To avoid problems with macros, we need to put types on binder variables and remove owners from guard tree symbols.
        val bindersWithTypedVars = indicesAndBinders.map { case (_, b) => replaceVarsInBinder(b) }
        val guardWithReplacedVars = replaceVarsInGuardTree(guardTree)

        val caseDefs = List(cq"List(..$bindersWithTypedVars) if $guardWithReplacedVars => ")
        val partialFunctionTree = q"{ case ..$caseDefs }"
        //        val pfTree = c.parse(showCode(partialFunctionTree)) // This works but it's perhaps an overkill.
        val pfTreeCode = showCode(partialFunctionTree)
        val pfTree = c.untypecheck(partialFunctionTree) // It's important to untypecheck here.

        val indices = indicesAndBinders.map(_._1).toArray
        val varSymbols = vars.map(identToScalaSymbol).distinct.toArray

        (q"CrossMoleculeGuard($indices, $varSymbols, $pfTree)", pfTreeCode)
      }

    val crossGuards = crossGuardsAndCodes.map(_._1).toArray
    val crossGuardsCodes = crossGuardsAndCodes.map(_._2)

    // We lift the GuardPresenceFlag values explicitly through q"" here, so we don't need an implicit Liftable[GuardPresenceFlag].
    val guardPresenceFlag = if (isGuardAbsent) {
      if (allInputMatchersAreTrivial)
        q"AllMatchersAreTrivial"
      else q"GuardAbsent"
    } else {
      val allGuardSymbols = guardVarsSeq
        .map(_._2.map(identToScalaSymbol).distinct.toArray)
        .filter(_.nonEmpty).toArray
      q"GuardPresent($allGuardSymbols, $staticGuardTree, $crossGuards)"
    }

    val blockingMolecules = patternIn.filter(_._3.nonEmpty)
    // It is an error to have reply molecules that do not match on a simple variable.
    val wrongBlockingMolecules = blockingMolecules.filter(_._3.get.notReplyValue).map(_._1)
    maybeError("blocking input molecules", "matches on anything else than a simple variable", wrongBlockingMolecules)

    // If we are here, all input reply molecules have correct pattern variables. Now we check that each of them has one and only one reply.
    val repliedMolecules = bodyReply.map(_._1.asTerm.name.decodedName)
    val blockingReplies = blockingMolecules.flatMap(_._3.flatMap {
      case ReplyVarF(x) => Some(x.name)
      case _ => None
    })

    val blockingMoleculesWithoutReply = blockingReplies difff repliedMolecules
    val blockingMoleculesWithMultipleReply = repliedMolecules difff blockingReplies

    maybeError("blocking input molecules", "but no reply found for", blockingMoleculesWithoutReply, "receive a reply unconditionally")
    maybeError("blocking input molecules", "but multiple replies found for", blockingMoleculesWithMultipleReply, "receive only one reply")

    if (patternIn.isEmpty && !isSingletonReaction(pattern, guard, body)) // go { case x => ... }
      reportError("Reaction input must be `_` or must contain some input molecules")

    if (isSingletonReaction(pattern, guard, body) && bodyOut.isEmpty)
      reportError("Singleton reaction must emit some output molecules")

    val inputMolecules = patternInWithMergedGuardsAndIndex.map { case (s, i, p, _) => q"InputMoleculeInfo(${s.asTerm}, $i, $p, ${p.patternSha1(t => showCode(t))})" }.toArray

    // Note: the output molecules could be sometimes not emitted according to a runtime condition.
    // We do not try to examine the reaction body to determine which output molecules are always emitted.
    // However, the order of output molecules corresponds to the order in which they might be emitted.
    val allOutputInfo = bodyOut
    // Neither the pattern nor the guard can emit output molecules.
    val outputMolecules = allOutputInfo.map { case (m, p, envs) => q"OutputMoleculeInfo(${m.asTerm}, $p, ${envs.reverse})" }.toArray

    // Detect whether this reaction has a simple livelock:
    // All input molecules have trivial matchers and are a subset of output molecules.
    val inputMoleculesAreSubsetOfOutputMolecules = (patternIn.map(_._1) difff allOutputInfo.map(_._1)).isEmpty

    if (isGuardAbsent && allInputMatchersAreTrivial && inputMoleculesAreSubsetOfOutputMolecules) {
      maybeError("Unconditional livelock: Input molecules", "output molecules, with all trivial matchers for", patternIn.map(_._1.asTerm.name.decodedName), "not be a subset of")
    }

    // Compute reaction sha1 from simplified inputlist.
    val reactionBodyCode = showCode(body)
    val reactionSha1 = getSha1String(
      patternInWithMergedGuardsAndIndex.map(_._3.patternSha1(t => showCode(t))).sorted.mkString(",") +
        crossGuardsCodes.sorted.mkString(",") +
        reactionBodyCode
    )

    // Prepare the ReactionInfo structure.
    val result = q"Reaction(ReactionInfo($inputMolecules, $outputMolecules, $guardPresenceFlag, $reactionSha1), $reactionBody, None, false)"
    //    println(s"debug: ${showCode(result)}")
    //    println(s"debug raw: ${showRaw(result)}")
    //    c.untypecheck(result) // this fails
    c.Expr[Reaction](result)
  }

}

object Macros {

  /** Return the raw expression tree. This macro is used only for testing.
    *
    * @param x Any scala expression. The expression will not be evaluated.
    * @return The raw syntax tree object (after typer) corresponding to the expression.
    */
  private[jc] def rawTree(x: Any): String = macro CommonMacros.rawTreeImpl

  /** This macro is not actually used.
    * It serves only for testing the mechanism by which we detect the name of the enclosing value.
    * For example, `val myVal = { 1; 2; 3; getName }` returns the string "myVal".
    *
    * @return The name of the enclosing value as string.
    */
  private[jc] def getName: String = macro CommonMacros.getNameImpl

}
