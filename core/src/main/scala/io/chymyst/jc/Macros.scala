package io.chymyst.jc

import java.security.MessageDigest

import io.chymyst.jc.Core._
import io.chymyst.util.ConjunctiveNormalForm.CNF

import scala.language.experimental.macros
import scala.reflect.NameTransformer.LOCAL_SUFFIX_STRING
import scala.reflect.macros.blackbox

class CommonMacros(val c: blackbox.Context) {

  import c.universe._

  def rawTreeImpl(x: c.Expr[Any]): Tree = {
    val result = showRaw(x.tree)
    q"$result"
  }

  def getNameImpl: Tree = {
    val s = getEnclosingName
    q"$s"
  }

  /** Detect the enclosing name of an expression.
    * For example: `val x = "name is " + getName` will set `x` to the string `"name is x"`.
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
    * [[WildcardF]] for `case a(_) ⇒`
    * [[SimpleVarF]] for `case a(x) ⇒`
    * [[ConstantPatternF]] for `case a(1) ⇒`
    * [[WrongReplyVarF]]: the reply matcher for blocking molecules is not a simple variable, e.g. `case f(_, _)` instead of `case f(_, r)`
    * [[OtherInputPatternF]]: none of the above (could be a case class or a general `unapply` expression)
    */
  sealed trait InputPatternFlag {
    def patternSha1(showCode: Tree => String, md: MessageDigest): String = "" // parameters are not used

    def notReplyValue: Boolean = true

    /** Does this pattern contain a nontrivial syntax tree that could contain other molecules?
      *
      * @return true or false
      */
    def needTraversing: Boolean = false

    def varNames: List[Ident] = Nil

    def containsVar(v: Ident): Boolean = varNames.exists(_.name === v.name)
  }

  case object WildcardF extends InputPatternFlag

  /** Represents a reply pattern consisting of a simple variable.
    *
    * @param replyVar The Ident of a reply pattern variable.
    */
  final case class ReplyVarF(replyVar: Ident) extends InputPatternFlag { // ignore warning about "outer reference in this type test"
    override def notReplyValue: Boolean = false
  }

  /** Represents a pattern match with a simple pattern variable, such as `a(x)`
    *
    * @param v The Ident of the pattern variable.
    */
  final case class SimpleVarF(v: Ident, binder: Tree, cond: Option[Tree]) extends InputPatternFlag { // ignore warning about "outer reference in this type test"
    override def varNames: List[Ident] = List(v)

    override def patternSha1(showCode: Tree => String, md: MessageDigest): String =
      cond.map(c => getSha1(showCode(c), md)).getOrElse("")
  }

  /** Represents an error situation.
    * The reply pseudo-molecule must be bound to a simple variable, but we found another pattern instead.
    */
  case object WrongReplyVarF extends InputPatternFlag

  /** The pattern represents a constant, which can be a literal constant such as `"abc"`
    * or a compound type such as `(2, 3)` or `(Some(2), 3, 4)`.
    * The value `v` represents a value of the `[T]` type of [[M]]`[T]` or [[B]]`[T,R]`.
    */
  final case class ConstantPatternF(v: Tree) extends InputPatternFlag { // ignore warning about "outer reference in this type test"
    override def patternSha1(showCode: Tree => String, md: MessageDigest): String = getSha1(showCode(v), md)
  }

  /** Nontrivial pattern matching expression that could contain `unapply`, destructuring, and pattern `@` variables.
    * For instance, if `c` is a molecule with values of type `(Int, Option[Int])`
    * then and example of nontrivial pattern match could be `c( z@(x, Some(y)) )`.
    * In this example, we will have `vars = List("z", "x", "y")` and `matcher = { case z@(x, Some(y)) => (z, x, y) }`.
    *
    * @param matcher Tree of a partial function of type `Any => Any`.
    * @param guard   `None` if the pattern is irrefutable; `Some(guard expression tree)` if the pattern is not irrefutable and potentially requires a guard condition.
    * @param vars    List of pattern variables in the order of their appearance in the syntax tree.
    */
  final case class OtherInputPatternF(matcher: Tree, guard: Option[Tree], vars: List[Ident]) extends InputPatternFlag { // ignore warning about "outer reference in this type test"
    override def needTraversing: Boolean = true

    override def varNames: List[Ident] = vars

    override def patternSha1(showCode: Tree => String, md: MessageDigest): String =
      getSha1(showCode(matcher) + showCode(guard.getOrElse(EmptyTree)), md)
  }

  /** Describes the pattern matcher for output molecules. This flag is used only within the macro code and is not exported to compiled code.
    * The corresponding value of type [[OutputPatternType]] is exported to compiled code of the [[Reaction]] instance.
    *
    * Possible values:
    * ConstOutputPatternF(x): a(123) or a(Some(4)), etc.
    * OtherOutputPatternF: a(x), a(x+y), or any other kind of expression.
    * EmptyOutputPatternF: no argument given, i.e. bare molecule emitter value or reply emitter value
    */
  sealed trait OutputPatternFlag {
    val needTraversal: Boolean = false
    val patternTypeWithTree: OutputPatternType = OtherOutputPattern
  }

  case object OtherOutputPatternF extends OutputPatternFlag {
    override val needTraversal: Boolean = true

    override val toString: String = "?"
  }

  case object EmptyOutputPatternF extends OutputPatternFlag {
    override val needTraversal: Boolean = true

    /** The pattern is empty, and most probably will be used with a value, so we print `f(?)` in error messages. */
    override val toString: String = "?"
  }

  final case class ConstOutputPatternF(v: Tree) extends OutputPatternFlag { // ignore warning about "outer reference in this type test"
    override val patternTypeWithTree: OutputPatternType = ConstOutputPattern(v)

    override val toString: String = showCode(v)
  }

}

final class MoleculeMacros(override val c: blackbox.Context) extends CommonMacros(c) {

  import c.universe._

  def mImpl[T: c.WeakTypeTag]: Tree = {
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

  // Implicit arguments of the def macro become non-implicit arguments of the implementation function. See https://github.com/scala/scala/pull/1194
  def dmImpl[T: c.WeakTypeTag](clusterConfig: c.Expr[ClusterConfig]): Tree = {
    val moleculeName = getEnclosingName
    val moleculeValueType = c.weakTypeOf[T]
    q"new DM[$moleculeValueType]($moleculeName)($clusterConfig)"
  }

}

final class PoolMacros(override val c: blackbox.Context) extends CommonMacros(c) {

  import c.universe._

  def newFixedPoolImpl0(): Tree = {
    val poolName = getEnclosingName
    q"new FixedPool($poolName)"
  }

  def newFixedPoolImpl1(parallelism: c.Expr[Int]): Tree = {
    val poolName = getEnclosingName
    q"new FixedPool($poolName, $parallelism)"
  }

  def newBlockingPoolImpl0(): Tree = {
    val poolName = getEnclosingName
    q"new BlockingPool($poolName)"
  }

  def newBlockingPoolImpl1(parallelism: c.Expr[Int]): Tree = {
    val poolName = getEnclosingName
    q"new BlockingPool($poolName, $parallelism)"
  }

}

final class BlackboxMacros(override val c: blackbox.Context) extends ReactionMacros(c) {

  import c.universe._

  private val md = getMessageDigest

  // This is the main method implementing `go` that gathers the reaction info and performs some preliminary static analysis.
  def goImpl(reactionBody: c.Expr[ReactionBody]): c.Expr[Reaction] = GetReactionCases.from(reactionBody.tree) match {
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

  def printRawPatternType(p: OutputPatternType): String = {
    p match {
      case ConstOutputPattern(q"()") ⇒ ""
      case _ ⇒ p.toString
    }
  }

  def buildReactionValueImpl(reactionBody: c.Expr[ReactionBody], pattern: Tree, guard: Tree, body: Tree): c.Expr[Reaction] = {

    if (DetectInvalidInputGrouping.in(pattern))
      reportError("Reaction's input molecules must be grouped to the left in chemical notation, and have no @-pattern variables")

    val currentSymbolOwner = getCurrentSymbolOwner
    val moleculeInfoMaker = new MoleculeInfo(currentSymbolOwner)

    val (patternIn, patternOut, patternReply, wrongMoleculesInInput) = moleculeInfoMaker.from(pattern) // patternOut and patternReply should be empty
    maybeError("Input molecule patterns", "emits output molecules", patternOut.map(_._1.name))
    maybeError("Input molecule patterns", "perform any reply actions", patternReply.map(_._1.name), "not")
    maybeError("Input molecules", "uses other molecules inside molecule value patterns", wrongMoleculesInInput.map(_.name))

    val (guardIn, guardOut, guardReply, wrongMoleculesInGuard) = moleculeInfoMaker.from(guard) // guard in/out/reply lists should be all empty
    maybeError("Input guard", "emit any output molecules", guardOut.map(_._1.name), "not")
    maybeError("Input guard", "perform any reply actions", guardReply.map(_._1.name), "not")
    maybeError("Input guard", "matches on molecules", (guardIn.map(_._1) ++ wrongMoleculesInGuard).map(_.name))

    val (bodyIn, bodyOut, bodyReply, wrongMoleculesInBody) = moleculeInfoMaker.from(body) // bodyIn should be empty
    maybeError("Reaction body", "matches on additional input molecules", bodyIn.map(_._1.name))
    maybeError("Reaction body", "matches on molecules", wrongMoleculesInBody.map(_.name))

    // Blocking molecules should not be used under nontrivial output environments.
    val nontrivialEmittedBlockingMoleculeStrings = bodyOut
      .filter(_._1.typeSignature <:< weakTypeOf[B[_, _]])
      .filter(_._3.exists(!_.linear))
      .map { case (molSymbol, flag, _) => s"molecule ${molSymbol.name}($flag)" }
    maybeError("Reaction body", "emit blocking molecules inside function blocks", nontrivialEmittedBlockingMoleculeStrings, "not")

    // Reply emitters should not be used under nontrivial output environments.
    val nontrivialEmittedRepliesStrings = bodyReply
      .filter {
        case (_, EmptyOutputPatternF, _) ⇒ true
        case (_, _, envs) ⇒ envs.exists(!_.linear)
      }
      .map { case (molSymbol, flag, _) => s"reply emitter ${molSymbol.name}($flag)" }
    maybeError("Reaction body", "use reply emitters inside function blocks", nontrivialEmittedRepliesStrings, "not")

    // TODO: Code should check at compile time that each reply emitter is used only once. This depends on proper shrinkage.

    val guardCNF: CNF[Tree] = convertToCNF(guard) // Conjunctive normal form of the guard condition. In this CNF, `true` is List() and `false` is List(List()).

    // If any of the CNF clauses is empty, the entire guard is identically `false`. This is an error condition: reactions should not be permanently prohibited.
    if (guardCNF.exists(_.isEmpty)) {
      reportError("Reaction must not have an identically false guard condition")
    }

    // If the CNF is empty, the entire guard is identically `true`. We can remove it altogether.
    val isGuardAbsent = guardCNF.isEmpty

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

    val staticGuardTree: Option[Tree] = mergeGuards(staticGuardVarsSeq) // We need to merge all these guard clauses.
      .map(guardTree => q"() => $guardTree")

    // Merge the guard information into the individual input molecule infos. The result, patternInWithMergedGuards, replaces patternIn.
    val patternInWithMergedGuardsAndIndex = patternIn.zipWithIndex // patternInWithMergedGuards has same type as patternIn, except for the index
      .map {
      case ((mol, flag, replyFlag), i) =>
        val mergedGuardOpt = mergeGuards(moleculeGuardVarsSeq
          .filter { case (_, vars) => guardVarsConstrainOnlyThisMolecule(vars, flag) }
        ).map(t => replaceVarsInGuardTree(t))

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

    val crossGuardsIndicesAndCodes: List[(Tree, String)] = moleculeGuardVarsSeq // The "cross-molecule guards" are guard clauses whose variables do not all belong to any single molecule's matcher.
      .map { case (guardTree, vars) ⇒ (guardTree, vars, moleculeIndicesConstrainedByGuard(vars, patternIn.map(_._2))) }
      .filter(_._3.length > 1)
      .groupBy(_._3)
      .mapValues(_.map { case (guardTree, vars, _) ⇒ (guardTree, vars) })
      .toList
      .sortBy { case (indices, _) ⇒ (indices.length, -intHash(indices)) }.reverse
      // Now we have List[(indices: List[Int], List[(guardTree: Tree, vars: List[Ident])])] sorted in decreasing complexity order.
      .map { case (indicesOfConstrainedMols, guardsSubtreesAndIdents) ⇒
      // Collect and merge all cross-molecule guards that constrain the same set of indices.
      val mergedGuardTree = mergeGuards(guardsSubtreesAndIdents).getOrElse(EmptyTree)
      val mergedIdents = guardsSubtreesAndIdents.flatMap(_._2)

      // At this point, we map over only the cross-molecule guard clauses.
      // A guard clause is represented now by the guard expression tree `mergedGuardTree` and the list of variable identifiers `mergedIdents` used by that guard clause.
      // For each guard clause, we will produce a closure that takes all the vars as parameters and evaluates `mergedGuardTree` as a partial function.

      // Collect the match binders for all molecules constrained by this guard, in the order given by `indicesOfConstrainedMols`.
      val usedBinders: List[Tree] = indicesOfConstrainedMols.flatMap { i => patternIn.lift(i) }
        .flatMap {
          // Find all input molecules that have some pattern variables; omit wildcards and constants here.
          case (_, flag, _) ⇒ flag match {
            case SimpleVarF(_, binder, _) ⇒
              Some(binder)
            case OtherInputPatternF(matcher, _, _) ⇒
              Some(matcher)
            case _ ⇒
              None
          }
        }

      // To avoid problems with macros, we need to put types on binder variables and remove owners from guard tree symbols.
      val bindersWithTypedVars = usedBinders.map { b ⇒ replaceVarsInBinder(b) }
      val guardTreeWithReplacedVars = replaceVarsInGuardTree(mergedGuardTree)
      // Create the expression tree for a partial function that will match the variables for this guard.
      val caseDefs = List(cq"List(..$bindersWithTypedVars) if $guardTreeWithReplacedVars => ")
      val partialFunctionTree = q"{ case ..$caseDefs }"
      //        val pfTree = c.parse(showCode(partialFunctionTree)) // This works, but it's perhaps an overkill.
      val pfTreeCode = showCode(partialFunctionTree)
      val pfTree = c.untypecheck(partialFunctionTree) // It's important to untypecheck here.

      val indices = indicesOfConstrainedMols.toArray
      val varSymbols = mergedIdents.map(identToScalaSymbol).distinct.toArray

      (q"CrossMoleculeGuard($indices, $varSymbols, $pfTree)", pfTreeCode)
    }

    val crossGuards = crossGuardsIndicesAndCodes.map(_._1).toArray
    val crossGuardsSourceCodes = crossGuardsIndicesAndCodes.map(_._2)

    // We lift the GuardPresenceFlag values explicitly through q"" here, so we don't need an implicit Liftable[GuardPresenceFlag].
    val guardPresenceFlag = if (isGuardAbsent) {
      if (allInputMatchersAreTrivial)
        q"AllMatchersAreTrivial"
      else q"GuardAbsent"
    } else q"GuardPresent($staticGuardTree, $crossGuards)"

    // Note: the output molecules could be sometimes not emitted according to a run-time condition.
    // We do not try to examine the reaction body to determine which output molecules are always emitted.
    // However, the order of output molecules corresponds to the order in which they might be emitted.
    val allOutputInfo = bodyOut
    // Output molecule info comes only from the body since neither the pattern nor the guard can emit output molecules.
    val outputMoleculesReactionInfo = allOutputInfo.map { case (m, p, envs) => q"OutputMoleculeInfo(${m.asTerm}, ${m.asTerm.name.decodedName.toString.toScalaSymbol}, ${p.patternTypeWithTree}, ${envs.reverse})" }.toArray

    // Compute shrunk output info.
    val shrunkOutputInfo = OutputEnvironment.shrink(allOutputInfo.map { case (m, p, envs) => (m, p.patternTypeWithTree, envs) }, equalsToTree)
    val shrunkOutputReactionInfo = shrunkOutputInfo.map { case (m, p, envs) => q"OutputMoleculeInfo(${m.asTerm}, ${m.asTerm.name.decodedName.toString.toScalaSymbol}, $p, ${envs.reverse})" }.toArray

    val blockingMolecules = patternIn.filter(_._3.nonEmpty)
    // It is an error to have blocking molecules that do not match on a simple variable.
    val wrongBlockingMolecules = blockingMolecules.filter(_._3.exists(_.notReplyValue)).map(_._1)
    maybeError("Blocking input molecules", "matches a reply emitter with a simple variable", wrongBlockingMolecules.map(ms ⇒ s"molecule ${ms.name}"), "contain a pattern that")

    // If we are here, all reply emitters have correct pattern variables. Now we check that each blocking molecule has one and only one reply.
    val shrunkReplyInfo: List[(MacroSymbol, OutputPatternType, List[OutputEnvironment])] =
      OutputEnvironment.shrink(bodyReply.map { case (m, p, envs) => (m, p.patternTypeWithTree, envs) }, equalsToTree)

    val shrunkGuaranteedReplies = shrunkReplyInfo.filter(_._3.forall(_.atLeastOne)).map(_._1.asTerm.name.decodedName)
    val shrunkPossibleReplies = shrunkReplyInfo.map(_._1.asTerm.name.decodedName)
    val expectedBlockingReplies = blockingMolecules.flatMap(_._3.flatMap {
      case ReplyVarF(x) => Some(x.name)
      case _ => None
    })

    val blockingMoleculesWithoutReply = expectedBlockingReplies difff shrunkGuaranteedReplies
    val blockingMoleculesWithMultipleReply = shrunkPossibleReplies difff expectedBlockingReplies
    val blockingMoleculesEmittedLast = shrunkOutputInfo
      .filter(_._1.typeSignature <:< weakTypeOf[B[_, _]])
      .filter(_._3.forall(!_.notLastBlock))
      .map { case (mol, flag, _) ⇒
        s"molecule ${mol.name}(${printRawPatternType(flag)})"
      }

    maybeError("Blocking molecules", "but no unconditional reply found for", blockingMoleculesWithoutReply.map(ms ⇒ s"reply emitter $ms"), "receive a reply")
    maybeError("Blocking molecules", "but possibly multiple replies found for", blockingMoleculesWithMultipleReply.map(ms ⇒ s"reply emitter $ms"), "receive only one reply")
    maybeError("Blocking molecules", "but so were emitted", blockingMoleculesEmittedLast, "not be emitted last in a reaction")

    if (patternIn.isEmpty && !isStaticReaction(pattern, guard, body)) // go { case x => ... }
      reportError(s"Reaction input must be `_` or must contain some input molecules, but is ${showCode(pattern)}")

    if (isStaticReaction(pattern, guard, body) && bodyOut.isEmpty)
      reportError("Static reaction must emit some output molecules")

    val inputMolecules = patternInWithMergedGuardsAndIndex
      .map { case (s, i, p, _) =>
        // Determine the value type of the molecule and create a symbol, e.g. 'Int or 'Unit.
        val valType = s.typeSignature.typeArgs.headOption.map(_.dealias.finalResultType.toString).getOrElse("<unknown>").toScalaSymbol
        q"InputMoleculeInfo(${s.asTerm}, ${s.asTerm.name.decodedName.toString.toScalaSymbol}, $i, $p, ${p.patternSha1(t => showCode(t), md)}, $valType)"
      }.toArray

    // Detect whether this reaction has a simple livelock:
    // All input molecules have trivial matchers and are a subset of unconditionally emitted output molecules.
    val inputMoleculesAreSubsetOfOutputMolecules = (
      patternIn.map(_._1) difff
        shrunkOutputInfo.filter {
          case (_, _, envs) => envs.forall(_.atLeastOne)
        }.map(_._1)
      ).isEmpty

    // We can detect unconditional livelock at compile time only if no conditions need to be evaluated against e.g. some constant values.
    // That is, only if all matchers are trivial, and if the guard is absent.
    // Then it is sufficient to take the shrunk output info list and to see whether enough output molecules are present to cover all input molecules.
    if (isGuardAbsent && allInputMatchersAreTrivial && inputMoleculesAreSubsetOfOutputMolecules) {
      maybeError("Unconditional livelock: Input molecules", "output molecules, with all trivial matchers for", patternIn.map(_._1.name), "not be a subset of")
    }

    // Compute reaction sha1 from simplified input list.
    val reactionBodyCode = showCode(body)
    val reactionSha1 = getSha1(
      patternInWithMergedGuardsAndIndex.map(_._3.patternSha1(t => showCode(t), md)).sorted.mkString(",") +
        patternInWithMergedGuardsAndIndex.map(_._1.name.decodedName.toString).sorted.mkString(",") +
        crossGuardsSourceCodes.sorted.mkString(",") +
        reactionBodyCode,
      md
    )

    // Replace static emissions in body.
    val rbReplacedStatic = c.Expr[ReactionBody](new ReplaceStaticEmits(currentSymbolOwner).transform(reactionBody.tree))

    // Prepare the ReactionInfo structure.
    val result = q"Reaction(new ReactionInfo($inputMolecules, $outputMoleculesReactionInfo, $shrunkOutputReactionInfo, $guardPresenceFlag, $reactionSha1), $rbReplacedStatic, None, false)"
    //    println(s"debug: ${showCode(result)}")
    //    println(s"debug raw: ${showRaw(result)}")
    //    c.untypecheck(result) // this fails
    c.Expr[Reaction](result)
  }

}

object Macros {

  /** Return the raw expression tree. This macro is used only in tests, to verify that certain expression trees are what the code expects them to be.
    *
    * @param x Any scala expression. The expression will not be evaluated.
    * @return The raw syntax tree object (after typer) corresponding to the expression.
    */
  private[jc] def rawTree(x: Any): String = macro CommonMacros.rawTreeImpl

  /** Determine the name of the enclosing value.
    * For example, `val myVal = { 1; 2; 3; getName }` returns the string `"myVal"`.
    *
    * This works only for simple values, but not for pattern-matched values such as `val (x,y) = (getName, getName)`.
    *
    * This macro is used only in tests, to check the mechanism by which we detect the name of the enclosing value.
    *
    * @return The name of the enclosing value as string.
    */
  private[jc] def getName: String = macro CommonMacros.getNameImpl

}
