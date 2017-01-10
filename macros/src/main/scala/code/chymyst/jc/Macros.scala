package code.chymyst.jc

import Core._

import scala.collection.mutable
import scala.language.experimental.macros
import scala.reflect.macros.{blackbox, whitebox}
import scala.reflect.NameTransformer.LOCAL_SUFFIX_STRING
import scala.annotation.tailrec
import scala.collection.immutable.Seq
import scala.reflect.api.Trees

object Macros {

  /** This macro is used only for testing.
    *
    * @param x Any scala expression (will not be evaluated).
    * @return The raw syntax tree object (after typer) corresponding to the expression.
    */
  private[jc] def rawTree(x: Any): String = macro rawTreeImpl

  def rawTreeImpl(c: blackbox.Context)(x: c.Expr[Any]): c.universe.Tree = {
    import c.universe._
    val result = showRaw(x.tree)
    q"$result"
  }

  def replaceScala211Quirk(s: String): String = {
    val string211 = "AppliedTypeTree(Select(This(TypeName(\"scala\")), scala.Function1), "
    val string212 = "AppliedTypeTree(Select(Ident(scala), scala.Function1), "

    s.replace(string211, string212)
  }

  /** This macro is not actually used by Chymyst.
    * It serves only for testing the mechanism by which we detect the name of the enclosing value.
    * For example, `val myVal = { 1; 2; 3; getName }` returns the string "myVal".
    *
    * @return The name of the enclosing value as string.
    */
  private[jc] def getName: String = macro getNameImpl

  def getNameImpl(c: blackbox.Context): c.Expr[String] = {
    import c.universe._
    val s = getEnclosingName(c)
    c.Expr[String](q"$s")
  }

  /** This is how the enclosing name is detected.
    *
    * @param c The macro context.
    * @return String that represents the name of the enclosing value.
    */
  private def getEnclosingName(c: blackbox.Context): String =
    c.internal.enclosingOwner.name.decodedName.toString
      .stripSuffix(LOCAL_SUFFIX_STRING).stripSuffix("$lzy")

  def m[T]: M[T] = macro mImpl[T]

  def mImpl[T: c.WeakTypeTag](c: whitebox.Context): c.universe.Tree = {
    import c.universe._
    val moleculeName = getEnclosingName(c)

    val moleculeValueType = c.weakTypeOf[T]
    if (moleculeValueType =:= typeOf[Unit])
      q"new E($moleculeName)"
    else
      q"new M[$moleculeValueType]($moleculeName)"
  }

  def b[T, R]: B[T,R] = macro bImpl[T, R]

  // Does providing an explicit return type here as c.Expr[...] helps anything? Looks like it doesn't, so far.
  def bImpl[T: c.WeakTypeTag, R: c.WeakTypeTag](c: whitebox.Context): c.universe.Tree = {
    import c.universe._
    val moleculeName = getEnclosingName(c)

    val moleculeValueType = c.weakTypeOf[T]
    val replyValueType = c.weakTypeOf[R]

    if (replyValueType =:= typeOf[Unit]) {
      if (moleculeValueType =:= typeOf[Unit])
        q"new EE($moleculeName)"
      else
        q"new BE[$moleculeValueType]($moleculeName)"
    } else {
      // reply type is not Unit
      if (moleculeValueType =:= typeOf[Unit])
        q"new EB[$replyValueType]($moleculeName)"
      else
        q"new B[$moleculeValueType,$replyValueType]($moleculeName)"
    }
  }

  // Classes need to be defined at top level because we can't have case classes local to a function scope.
  // However, we need to use path-dependent types such as `Ident` and `Tree`.
  // So we use type parameters for them.

  /** Describes the pattern matcher for input molecules.
    * Possible values:
    * Wildcard: a(_)
    * SimpleVar: a(x)
    * SimpleConst: a(1)
    * WrongReplyVar: the second matcher for blocking molecules is not a simple variable
    * OtherPattern: we don't recognize the pattern (could be a case class or a general Unapply expression)
    */
  sealed trait InputPatternFlag[+Ident,+Tree] {
    def notReplyValue: Boolean = true

    /** Does this pattern contain a nontrivial syntax tree that could contain other molecules?
      *
      * @return true or false
      */
    def hasSubtree: Boolean = false

    def varNames: List[Ident] = Nil
  }

  case object WildcardF extends InputPatternFlag[Nothing, Nothing]

  /** Represents a reply pattern consisting of a simple variable.
    *
    * @param replyVar The Ident of a reply pattern variable.
    */
  final case class ReplyVarF[Ident,Tree](replyVar: Ident) extends InputPatternFlag[Ident,Tree] {
    override def notReplyValue: Boolean = false
  }

  /** Represents a pattern match with a simple pattern variable, such as `a(x)`
    *
    * @param v The Ident of the pattern variable.
    */
  final case class SimpleVarF[Ident,Tree](v: Ident, binder: Tree, cond: Option[Tree]) extends InputPatternFlag[Ident,Tree] {
    override def varNames: List[Ident] = List(v)
  }
  case object WrongReplyVarF extends InputPatternFlag[Nothing, Nothing] // the reply pseudo-molecule must be bound to a simple variable, but we found another pattern
  final case class SimpleConstF[Ident,Tree](v: Tree) extends InputPatternFlag[Ident,Tree] // this is the [T] type of M[T] or B[T,R]

  /** Nontrivial pattern matching expression that could contain unapply, destructuring, pattern @ variables, etc.
    * For example, if c is a molecule then this could be c( z@(x, Some(y)) )
    * In that case, vars = List("z", "x", "y") and matcher = { case z@(x, Some(y)) => (z, x, y) }
    *
    * @param matcher Tree of a partial function of type Any => Any.
    * @param vars List of pattern variable names in the order of their appearance in the syntax tree.
    */
  final case class OtherPatternF[Ident,Tree](matcher: Tree, guard: Tree, vars: List[Ident]) extends InputPatternFlag[Ident,Tree] {
    override def hasSubtree: Boolean = true
    override def varNames: List[Ident] = vars
  }

  /** Describes the pattern matcher for output molecules.
    * Possible values:
    * ConstOutputPatternF(x): a(123)
    * EmptyOutputPatternF: a()
    * OtherOutputPatternF: a(x+y) or anything else
    */
  sealed trait OutputPatternFlag[+Tree] {
    def needTraversal: Boolean = false
  }
  case object OtherOutputPatternF extends OutputPatternFlag[Nothing] {
    override def needTraversal: Boolean = true
  }
  case object EmptyOutputPatternF extends OutputPatternFlag[Nothing]
  final case class ConstOutputPatternF[Tree](v: Tree) extends OutputPatternFlag[Tree]

  /**
    * Users will define reactions using this function.
    * Examples: {{{ go { a(_) => ... } }}}
    * {{{ go { a (_) => ...}.withRetry onThreads threadPool }}}
    *
    * The macro also obtains statically checkable information about input and output molecules in the reaction.
    *
    * @param reactionBody The body of the reaction. This must be a partial function with pattern-matching on molecules.
    * @return A reaction value, to be used later in [[site]].
    */
  def go(reactionBody: ReactionBody): Reaction = macro buildReactionImpl

  def buildReactionImpl(c: whitebox.Context)(reactionBody: c.Expr[ReactionBody]): c.universe.Tree = {
    import c.universe._

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
      *
      */
    object ReactionCases extends Traverser {
      private var info: List[CaseDef] = List()

      override def traverse(tree: Tree): Unit =
        tree match {
          // this is matched by the partial function of type ReactionBody
          case DefDef(_, TermName("applyOrElse"), _, _, _, Match(_, list)) =>
            info = list

          // this is matched by a closure which is not a partial function. Not used now.
          /*
          case Function(List(ValDef(_, TermName(_), TypeTree(), EmptyTree)), Match(Ident(TermName(_)), list)) =>
           info = list
          */
          case _ => super.traverse(tree)
        }

      def from(tree: Tree): List[(Tree, Tree, Tree, String)] = {
        info = List()
        this.traverse(tree)
        info.filter {
          // PartialFunction automatically adds a default case; we ignore that CaseDef.
          case CaseDef(Bind(TermName("defaultCase$"), Ident(termNames.WILDCARD)), EmptyTree, _) => false
          case _ => true
        }.map { case c@CaseDef(aPattern, aGuard, aBody) => (aPattern, aGuard, aBody, getSha1String(replaceScala211Quirk(showRaw(c)))) }
      }
    }

    def isSingletonReaction(pattern: Tree, guard: Tree, body: Tree): Boolean = {
      pattern match {
        case Ident(termNames.WILDCARD) => true
        case _ => false
      }
    }

    def getSimpleVar(binderTerm: Tree): Ident = binderTerm match {
      case Bind(t@TermName(n), Ident(termNames.WILDCARD)) => Ident(t)
    }
    /** Detect whether an expression tree represents a constant expression.
      * Recognize literals, Some(), None(), and tuples.
      *
      * @param exprTree Binder pattern tree or expression tree.
      * @return {{{Some(tree)}}} if the expression represents a constant of the recognized form. Here {{{tree}}} will be a quoted expression tree (not a binder tree). {{{None}}} otherwise.
      */

    def getConstantTree(exprTree: Trees#Tree): Option[Trees#Tree] = exprTree match {
      case Literal(_) => Some(exprTree)
      case pq"scala.None" | q"scala.None" => Some(q"None")
      case q"scala.Some.apply[..$ts](..$xs)" if ts.size === 1 && xs.size === 1 =>
        xs.headOption
          .flatMap(getConstantTree)
          .map(t => q"Some(${t.asInstanceOf[Tree]})")
      case pq"$extr(..$xs)" if xs.size === 1 && showCode(extr.asInstanceOf[Tree]) === "scala.Some" =>
        xs.headOption
          .flatMap(getConstantTree)
          .map(t => q"Some(${t.asInstanceOf[Tree]})")
      case pq"(..$exprs)" if exprs.size > 1 => // Tuples of size 0 are Unit values, tuples of size 1 are ordinary values.
        val trees = exprs.flatMap(getConstantTree).map(_.asInstanceOf[Tree]) // if some exprs are not constant, they will be omitted in this list
        if (trees.size === exprs.size) Some(q"(..$trees)") else None
      case q"(..$exprs)" if exprs.size > 1 => // Tuples of size 0 are Unit values, tuples of size 1 are ordinary values.
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

      def disjunctionOneTerm(a: Tree, b: List[List[Tree]]): List[List[Tree]] = b.map( y => (a :: y).distinct ).distinct
      def disjunctionOneClause(a: List[Tree], b: List[List[Tree]]): List[List[Tree]] = b.map( y => (a ++ y).distinct ).distinct
      def disjunction(a: List[List[Tree]], b: List[List[Tree]]): List[List[Tree]] = a.flatMap( x => disjunctionOneClause(x, b)).distinct
      def conjunction(a: List[List[Tree]], b: List[List[Tree]]): List[List[Tree]] = (a++b).distinct
      def negation(a: List[List[Tree]]): List[List[Tree]] = a match {
        case x :: xs =>
          val nxs = negation(xs)
          x.flatMap(t => disjunctionOneTerm(q"! $t", nxs))
        case Nil => List(List())  // negation of true is false
      }
      def normalize(a: Trees#Tree): List[List[Tree]] = convertToCNF(a.asInstanceOf[Tree])

      term match {
        case q"$a && $b" =>
          val aN = normalize(a)
          val bN = normalize(b)
          conjunction(aN, bN)

        case q"$a || $b" =>
          val aN = normalize(a)
          val bN = normalize(b)
          disjunction(aN, bN)

        case q"if ($a) $b else $c" =>  // (a' + b)(a + c)
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
          val newIdentOpt = replacingIdents.find( _.name.toTermName.decodedName.toString === name )
          newIdentOpt.map { newIdent =>
            val newIdentType = newIdent.symbol.info.typeSymbol
            pq"$t : $newIdentType"
          }.getOrElse(tree)
        case _ => super.transform(tree)
      } else tree match {
        case Ident(name) => replacingIdents.find( _.name === name ).getOrElse(tree)
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
      def fromFlags(guardTerm: Tree, inputInfos: List[InputPatternFlag[Ident, Tree]]): List[Ident] =
        fromVars(guardTerm, inputInfos.flatMap(_.varNames))

      def fromVars(guardTerm: Tree, givenVars: List[Ident]): List[Ident] = {
        givenPatternVars = givenVars
        vars = mutable.ArrayBuffer()
        traverse(guardTerm)
        vars.toList
      }
    }

    class MoleculeInfo(reactionBodyOwner: c.Symbol) extends Traverser {

      /** Examine an expression tree, looking for molecule expressions.
        *
        * @param reactionPart An expression tree (could be the "case" pattern, the "if" guard, or the reaction body).
        * @return A triple: List of input molecule patterns, list of output molecule patterns, and list of reply action patterns.
        */
      def from(reactionPart: Tree): (List[(c.Symbol, InputPatternFlag[Ident, Tree], Option[InputPatternFlag[Ident, Tree]], String)], List[(c.Symbol, OutputPatternFlag[Tree])], List[(c.Symbol, OutputPatternFlag[Tree])]) = {
        inputMolecules = mutable.ArrayBuffer()
        outputMolecules = mutable.ArrayBuffer()
        replyActions = mutable.ArrayBuffer()
        traverse(reactionPart)
        (inputMolecules.toList, outputMolecules.toList, replyActions.toList)
      }

      private var inputMolecules: mutable.ArrayBuffer[(c.Symbol, InputPatternFlag[Ident, Tree], Option[InputPatternFlag[Ident, Tree]], String)] = mutable.ArrayBuffer()
      private var outputMolecules: mutable.ArrayBuffer[(c.Symbol, OutputPatternFlag[Tree])] = mutable.ArrayBuffer()
      private var replyActions: mutable.ArrayBuffer[(c.Symbol, OutputPatternFlag[Tree])] = mutable.ArrayBuffer()

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

      private def getInputFlag(binderTerm: Tree): InputPatternFlag[Ident, Tree] = binderTerm match {
        case Ident(termNames.WILDCARD) => WildcardF
        case Bind(t@TermName(_), Ident(termNames.WILDCARD)) => SimpleVarF(Ident(t), binderTerm, None)
        case _ => getConstantTree(binderTerm)
          .map(t => SimpleConstF(t.asInstanceOf[Tree]))
          .getOrElse {
            val vars = PatternVars.from(binderTerm)
            OtherPatternF(binderTerm, EmptyTree, vars)
          }
      }

      private def getOutputFlag(binderTerms: List[Tree]): OutputPatternFlag[Tree] = binderTerms match {
        case List(t) => getConstantTree(t).map(tree => ConstOutputPatternF(tree.asInstanceOf[Tree])).getOrElse(OtherOutputPatternF)
        case Nil => EmptyOutputPatternF
        case _ => OtherOutputPatternF
      }

      override def traverse(tree: Tree): Unit = {
        tree match {
          // avoid traversing nested reactions: check whether this subtree is a Reaction() value
          case q"code.chymyst.jc.Reaction.apply($_,$_,$_,$_)" => ()
          case q"Reaction.apply($_,$_,$_,$_)" => ()

          // matcher with a single argument: a(x)
          case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder)) if t.tpe <:< typeOf[Molecule] =>
            val flag2Opt = if (t.tpe <:< weakTypeOf[B[_, _]]) Some(WrongReplyVarF) else None
            val flag1 = getInputFlag(binder)
            if (flag1.hasSubtree) traverse(binder)
            inputMolecules.append((t.symbol, flag1, flag2Opt, getSha1(binder)))

          // matcher with two arguments: a(x, y)
          case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder1, binder2)) if t.tpe <:< typeOf[Molecule] =>
            val flag2 = getInputFlag(binder2) match {
              case SimpleVarF(_,_,_) => ReplyVarF[Ident, Tree](getSimpleVar(binder2))
              case f@_ => WrongReplyVarF // this is an error that we should report later
            }
            val flag1 = getInputFlag(binder1)
            // Perhaps we need to continue to analyze the "binder" (it could be another molecule).
            if (flag1.hasSubtree) traverse(binder1)
            if (flag2.hasSubtree) traverse(binder2)
            // After traversing the subtrees, we append this molecule information.
            inputMolecules.append((t.symbol, flag1, Some(flag2), getSha1(binder1)))

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

    // this boilerplate is necessary for being able to use PatternType values in macro quasiquotes
    implicit val liftablePatternFlag: Liftable[InputPatternFlag[Ident, Tree]] = Liftable[InputPatternFlag[Ident, Tree]] {
      case WildcardF => q"_root_.code.chymyst.jc.Wildcard"
      case SimpleConstF(tree) => q"_root_.code.chymyst.jc.SimpleConst($tree)"
      case SimpleVarF(v, binder, cond) =>
        val guardFunction = cond.map(c => matcherFunction(binder, c, List(v)))
        q"_root_.code.chymyst.jc.SimpleVar(${identToScalaSymbol(v)}, $guardFunction)"
      case OtherPatternF(matcherTree, guardTree, vars) => q"_root_.code.chymyst.jc.OtherInputPattern(${matcherFunction(matcherTree, guardTree, vars)}, ${vars.map(identToScalaSymbol)})"
      case _ => q"_root_.code.chymyst.jc.UnknownInputPattern"
    }

    implicit val liftableOutputPatternFlag: Liftable[OutputPatternFlag[Tree]] = Liftable[OutputPatternFlag[Tree]] {
      case ConstOutputPatternF(tree) => q"_root_.code.chymyst.jc.SimpleConstOutput($tree)"
      case EmptyOutputPatternF => q"_root_.code.chymyst.jc.SimpleConstOutput(())"
      case _ => q"_root_.code.chymyst.jc.OtherOutputPattern"
    }

    def maybeError[T](what: String, patternWhat: String, molecules: Seq[T], connector: String = "not contain a pattern that", method: (c.Position, String) => Unit = c.error) = {
      if (molecules.nonEmpty)
        method(c.enclosingPosition, s"$what should $connector $patternWhat (${molecules.mkString(", ")})")
    }

    val caseDefs = ReactionCases.from(reactionBody.tree)

    // Note: `caseDefs` should not be an empty list because that's a typecheck error (`go` only accepts a partial function, so at least one `case` needs to be given).
    // However, the user could be clever and write `val body = new PartialFunction...; go(body)`. We do not allow this because `go` needs to see the entire reaction body.
    if (caseDefs.isEmpty) c.error(c.enclosingPosition, "Reactions should be defined inline with the `go { case ... => ... }` syntax")

    if (caseDefs.length > 1) c.error(c.enclosingPosition, "Reactions should contain only one `case` clause")

    val Some((pattern, guard, body, reactionSha1)) = caseDefs.headOption

    val moleculeInfoMaker = new MoleculeInfo(getCurrentSymbolOwner)

    val (patternIn, patternOut, patternReply) = moleculeInfoMaker.from(pattern) // patternOut and patternReply should be empty
    maybeError("input molecule patterns", "emits output molecules", patternOut)
    maybeError("input molecule patterns", "perform any reply actions", patternReply, "not")

    val (guardIn, guardOut, guardReply) = moleculeInfoMaker.from(guard) // guard in/out/reply lists should be all empty
    maybeError("input guard", "matches on additional input molecules", guardIn.map(_._1))
    maybeError("input guard", "emit any output molecules", guardOut.map(_._1), "not")
    maybeError("input guard", "perform any reply actions", guardReply.map(_._1), "not")

    val (bodyIn, bodyOut, bodyReply) = moleculeInfoMaker.from(body) // bodyIn should be empty
    maybeError("reaction body", "matches on additional input molecules", bodyIn.map(_._1))

    val guardCNF: List[List[Tree]] = convertToCNF(guard) // Conjunctive normal form of the guard condition. In this CNF, `true` is List() and `false` is List(List()).

    // If any of the CNF clauses is empty, the entire guard is identically `false`. This is an error condition: reactions should not be permanently prohibited.
    if (guardCNF.exists(_.isEmpty)) {
      c.error(c.enclosingPosition, "Reaction should not have an identically false guard condition")
    }

    // If the CNF is empty, the entire guard is identically `true`. We can remove it altogether.
    val isGuardAbsent = guardCNF.isEmpty || (guard match {
      case EmptyTree => true;
      case _ => false
    })

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

    val allGuardVars =  moleculeGuardVarsSeq.flatMap(_._2)

    // To avoid problems with macros, we nneed to put types on binder variables and remove owners from guard tree symbols.
    def replaceVarsInBinder(binderTree: Tree): Tree = ReplaceVars.in(binderTree, allGuardVars, inBinder = true)

    def replaceVarsInGuardTree(guardTree: Tree): Tree = ReplaceVars.in(guardTree, allBinderVars, inBinder = false)

    val staticGuardTree: Option[Tree] = staticGuardVarsSeq // We need to merge all these guard clauses.
      .map(_._1)
      .reduceOption{ (g1, g2) => q"$g1 && $g2" }
      .map(guardTree => q"() => $guardTree")

    def guardVarsConstrainOnlyThisMolecule(guardVarList: List[Ident], moleculeFlag: InputPatternFlag[Ident, Tree]): Boolean =
      guardVarList.forall(v => moleculeFlag.varNames.exists(mv => mv.name === v.name))

    def guardVarsConstrainThisMolecule(guardVarList: List[Ident], moleculeFlag: InputPatternFlag[Ident, Tree]): Boolean =
      guardVarList.exists(v => moleculeFlag.varNames.exists(mv => mv.name === v.name))

    // Merge the guard information into the individual input molecule infos. The result, patternInWithMergedGuards, replaces patternIn.
    val patternInWithMergedGuards = patternIn // patternInWithMergedGuards has same type as patternIn
      .map {
      case (mol, flag, replyFlag, patternSha1) =>
        val mergedGuardOpt = moleculeGuardVarsSeq
          .filter { case (g, vars) => guardVarsConstrainOnlyThisMolecule(vars, flag) }
          .map(_._1)
          .reduceOption{ (g1, g2) => q"$g1 && $g2" }
          .map(t => replaceVarsInGuardTree(t))

        val mergedFlag = flag match {
          case SimpleVarF(v, binder, _) => mergedGuardOpt.map(guardTree => SimpleVarF(v, replaceVarsInBinder(binder), Some(guardTree))).getOrElse(flag) // a(x) if x>0 is replaced with a(x : check if x>0).
          case OtherPatternF(matcher, _, vars) => mergedGuardOpt.map(guardTree => OtherPatternF(replaceVarsInBinder(matcher), guardTree, vars)).getOrElse(flag) // We can't have a nontrivial guardTree in patternIn, so we replace it here with the new guardTree.
          case _ => flag
        }

        (mol, mergedFlag, replyFlag, patternSha1)
    }

    val allInputMatchersAreTrivial = patternInWithMergedGuards.forall{
      case (_, SimpleVarF(_, _, None), _, _) | (_, WildcardF, _, _) => true
      case _ => false
    }

    val crossGuards = moleculeGuardVarsSeq // List[(List[scala.Symbol], Tree)]. The "cross guards" are guard clauses whose variables do not all belong to any single molecule's matcher.
      .filter { case (_, vars) => patternIn.forall { case (_, flag, _, _) => !guardVarsConstrainOnlyThisMolecule(vars, flag) } }
      // We need to produce a closure that starts with all the vars as parameters, and evaluates the guardTree.
      .map {
      case (guardTree, vars) =>
        // Determine which molecules we are constraining in this guard. Collect the binders from all these molecules.
        val binders = patternIn.flatMap {
          case (_, flag, _, _) => flag match {
            case SimpleVarF(v, binder, _) => Some((flag, binder, List(v)))
            case OtherPatternF(matcher, _, vs) => Some((flag, matcher, vs))
            case _ => None
          }
        }
          .filter { case (flag, _, vs) => guardVarsConstrainThisMolecule(vs, flag) }
          .map { case (_, binder, _) => binder }

        // To avoid problems with macros, we nneed to put types on binder variables and remove owners from guard tree symbols.
        val bindersWithTypedVars = binders.map(b => replaceVarsInBinder(b))
        val guardWithReplacedVars = replaceVarsInGuardTree(guardTree)

        val caseDefs = List(cq"List(..$bindersWithTypedVars) if $guardWithReplacedVars => ")
        val partialFunctionTree = q"{ case ..$caseDefs }"
//        val pfTree = c.parse(showCode(partialFunctionTree)) // This works but it's an overkill.
        val pfTree = c.untypecheck(partialFunctionTree)
        (vars.map(identToScalaSymbol), pfTree)
    }

    // We lift the GuardPresenceType values explicitly through q"" here, so we don't need an implicit Liftable[GuardPresenceType].
    val guardPresenceFlag = if (isGuardAbsent) {
      if (allInputMatchersAreTrivial)
        q"AllMatchersAreTrivial"
      else
        q"GuardAbsent"
    } else
      q"GuardPresent(${guardVarsSeq.map(_._2.map(identToScalaSymbol)).filter(_.nonEmpty)}, $staticGuardTree, $crossGuards)"


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

    maybeError("blocking input molecules", "but no reply found for", blockingMoleculesWithoutReply, "receive a reply")
    maybeError("blocking input molecules", "but multiple replies found for", blockingMoleculesWithMultipleReply, "receive only one reply")

    if (patternIn.isEmpty && !isSingletonReaction(pattern, guard, body)) // go { case x => ... }
      c.error(c.enclosingPosition, "Reaction should not have an empty list of input molecules")

    if (isSingletonReaction(pattern, guard, body) && bodyOut.isEmpty)
      c.error(c.enclosingPosition, "Reaction should not have an empty list of input molecules and no output molecules")

    val inputMolecules = patternInWithMergedGuards.map { case (s, p, _, patternSha1) => q"InputMoleculeInfo(${s.asTerm}, $p, $patternSha1)" }

    // Note: the output molecules could be sometimes not emitted according to a runtime condition.
    // We do not try to examine the reaction body to determine which output molecules are always emitted.
    // However, the order of output molecules corresponds to the order in which they might be emitted.
    val allOutputInfo = bodyOut // Neither the pattern nor the guard can emit output molecules.
    val outputMolecules = allOutputInfo.map { case (m, p) => q"OutputMoleculeInfo(${m.asTerm}, $p)" }

    // Detect whether this reaction has a simple livelock:
    // All input molecules have trivial matchers and are a subset of output molecules.
    val inputMoleculesAreSubsetOfOutputMolecules = (patternIn.map(_._1) difff allOutputInfo.map(_._1)).isEmpty

    if(isGuardAbsent && allInputMatchersAreTrivial && inputMoleculesAreSubsetOfOutputMolecules) {
      maybeError("Unconditional livelock: Input molecules", "output molecules, with all trivial matchers for", patternIn.map(_._1.asTerm.name.decodedName), "not be a subset of")
    }

    // Prepare the ReactionInfo structure.
    val result = q"Reaction(ReactionInfo($inputMolecules, Some(List(..$outputMolecules)), $guardPresenceFlag, $reactionSha1), $reactionBody, None, false)"
//    println(s"debug: ${show(result)}")
//    println(s"debug raw: ${showRaw(result)}")
//    c.untypecheck(result) // this fails
    result
  }

}
