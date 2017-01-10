package code.chymyst.jc

import Core._
import scala.{Symbol => ScalaSymbol}

/** Represents compile-time information about the pattern matching for values carried by input molecules.
  * Possibilities:
  * {{{a(_)}}} is represented by [[Wildcard]]
  * {{{a(x)}}} is represented by [[SimpleVar]] with value {{{SimpleVar(v = 'x, cond = None)}}}
  * {{{a(x) if x > 0}}} is represented by [[SimpleVar]] with value {{{SimpleVar(v = 'x, cond = Some({ case x if x > 0 => }))}}}
  * {{{a(1)}}} is represented by [[SimpleConst]] with value {{{SimpleConst(v = 1)}}}
  * {{{a( (x, Some((y,z)))) ) if x > y}}} is represented by [[OtherInputPattern]] with value {{{OtherInputPattern(matcher = { case (x, Some((y,z)))) if x > y => }, vars = List('x, 'y, 'z))}}}
  * [[UnknownInputPattern]] is used for reactions defined with [[_go]], which do not have this compile-time information.
  */
sealed trait InputPatternType

case object Wildcard extends InputPatternType

final case class SimpleVar(v: ScalaSymbol, cond: Option[PartialFunction[Any, Unit]]) extends InputPatternType

final case class SimpleConst(v: Any) extends InputPatternType

final case class OtherInputPattern(matcher: PartialFunction[Any, Unit], vars: List[ScalaSymbol]) extends InputPatternType

case object UnknownInputPattern extends InputPatternType

sealed trait OutputPatternType

final case class SimpleConstOutput(v: Any) extends OutputPatternType

case object OtherOutputPattern extends OutputPatternType

/** Indicates whether a reaction has a guard condition.
  *
  */
sealed trait GuardPresenceType {
  /** Checks whether the reaction has no cross-molecule guard conditions.
    * For example, {{{go { case a(x) + b(y) if x > y => } }}} has a cross-molecule guard condition,
    * whereas {{{go { case a(x) + b(y) if x == 1 && y == 2 => } }}} has no cross-guard conditions because its guard condition
    * can be split into a conjunction of guard conditions that each constrain the value of a single molecule.
    *
    * @return {{{true}}} if the reaction has no guard condition, or if it has guard conditions that can be split between molecules;
    *        {{{false}}} if the reaction has a cross-molecule guard condition, or if it is unknown whethe rthe reaction has a guard condition at all.
    */
  def effectivelyAbsent: Boolean = this match {
    case GuardAbsent | AllMatchersAreTrivial | GuardPresent(_, None, List()) => true
    case _ => false
  }
}

/** Indicates the presence of a guard condition.
  * The guard is parsed into a flat conjunction of guard clauses, which are then analyzed for cross-dependencies between molecules.
  *
  * For example, consider the reaction {{{go { case a(x) + b(y) + c(z) if x > n && y > 0 && y > z && n > 1 => ...} }}}. Here {{{n}}} is an integer constant defined outside the reaction.
  * The conditions for starting this reaction is that a(x) has value x > n; that b(y) has value y > 0; that c(z) has value such that y > z; and finally that n > 1, independently of any molecule values.
  * The condition n > 1 is a static guard. The condition x > n pertains only to the molecule a(x) and therefore can be moved out of the guard into the InputMoleculeInfo for that molecule. Similarly, the condition y > 0 can be moved out of the guard.
  * However, the condition y > z relates two different molecule values; it is a cross guard.
  *
  * @param vars The list of all pattern variables used by the guard condition. Each element of this list is a list of variables used by one guard clause. In the example shown above, this will be {{{List(List('y, 'z))}}} because all other conditions are moved out of the guard.
  * @param staticGuard The conjunction of all the clauses of the guard that are independent of pattern variables. This closure can be called in order to determine whether the reaction should even be considered to start, regardless of the presence of molecules. In this example, the value of {{{staticGuard}}} will be {{{Some(() => n > 1)}}}.
  * @param crossGuards A list of functions that represent the clauses of the guard that relate values of different molecules. The partial function `Any => Unit` should be called with the arguments representing the tuples of pattern variables from each molecule used by the cross guard.
  *                    In the present example, {{{crossGuards}}} will be {{{List((List('y, 'z), { case List(y, z) if y > z => () }))}}}.
  */
final case class GuardPresent(vars: List[List[ScalaSymbol]], staticGuard: Option[() => Boolean], crossGuards: List[(List[ScalaSymbol], PartialFunction[List[Any], Unit])]) extends GuardPresenceType

case object GuardAbsent extends GuardPresenceType
case object AllMatchersAreTrivial extends GuardPresenceType

/** Indicates that there is no information about the presence of the guard.
  * This happens only with reactions that
  */
case object GuardPresenceUnknown extends GuardPresenceType

/** Compile-time information about an input molecule pattern in a reaction.
  * This class is immutable.
  *
  * @param molecule The molecule emitter value that represents the input molecule.
  * @param flag     Type of the input pattern: wildcard, constant match, etc.
  * @param sha1     Hash sum of the source code (AST tree) of the input pattern.
  */
final case class InputMoleculeInfo(molecule: Molecule, flag: InputPatternType, sha1: String) {
  /** Determine whether this input molecule pattern is weaker than another pattern.
    * Pattern a(xxx) is weaker than b(yyy) if a==b and if anything matched by yyy will also be matched by xxx.
    *
    * @param info The input molecule info for another input molecule.
    * @return Some(true) if we can surely determine that this matcher is weaker than another;
    *         Some(false) if we can surely determine that this matcher is not weaker than another;
    *         None if we cannot determine anything because information is insufficient.
    */
  private[jc] def matcherIsWeakerThan(info: InputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule) Some(false)
    else flag match {
      case Wildcard | SimpleVar(_, None) => Some(true)
      case SimpleVar(_, Some(matcher1)) => info.flag match {
        case SimpleConst(c)=> Some(matcher1.isDefinedAt(c))
        case SimpleVar(_, Some(_)) | OtherInputPattern(_,_) => None // Cannot reliably determine a weaker matcher.
        case _ => Some(false)
      }
      case OtherInputPattern(matcher1,_) => info.flag match {
        case SimpleConst(c) => Some(matcher1.isDefinedAt(c))
        case OtherInputPattern(_,_) => if (sha1 === info.sha1) Some(true) else None // We can reliably determine identical matchers.
        case _ => Some(false) // Here we can reliably determine that this matcher is not weaker.
      }
      case SimpleConst(c) => Some(info.flag match {
        case SimpleConst(c1) => c == c1
        case _ => false
      })
      case _ => Some(false)
    }
  }

  private[jc] def matcherIsWeakerThanOutput(info: OutputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule) Some(false)
    else flag match {
      case Wildcard | SimpleVar(_, None) => Some(true)
      case SimpleVar(_, Some(matcher1)) => info.flag match {
        case SimpleConstOutput(c) => Some(matcher1.isDefinedAt(c))
        case _ => None // Here we can't reliably determine whether this matcher is weaker.
      }
      case OtherInputPattern(matcher1,_) => info.flag match {
        case SimpleConstOutput(c) => Some(matcher1.isDefinedAt(c))
        case _ => None // Here we can't reliably determine whether this matcher is weaker.
      }
      case SimpleConst(c) => info.flag match {
        case SimpleConstOutput(`c`) => Some(true)
        case SimpleConstOutput(_) => Some(false) // definitely not the same constant
        case _ => None // Otherwise, it could be this constant but we can't determine.
      }
      case _ => Some(false)
    }
  }

  // Here "similar" means either it's definitely weaker or it could be weaker (but it is definitely not stronger).
  private[jc] def matcherIsSimilarToOutput(info: OutputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule) Some(false)
    else flag match {
      case Wildcard | SimpleVar(_, None) => Some(true)
      case SimpleVar(_, Some(matcher1)) => info.flag match {
        case SimpleConstOutput(c) => Some(matcher1.isDefinedAt(c))
        case _ => Some(true) // Here we can't reliably determine whether this matcher is weaker, but it's similar (i.e. could be weaker).
      }
      case OtherInputPattern(matcher1, _) => info.flag match {
        case SimpleConstOutput(c) => Some(matcher1.isDefinedAt(c))
        case _ => Some(true) // Here we can't reliably determine whether this matcher is weaker, but it's similar (i.e. could be weaker).
      }
      case SimpleConst(c) => Some(info.flag match {
        case SimpleConstOutput(`c`) => true
        case SimpleConstOutput(_) => false // definitely not the same constant
        case _ => true // Otherwise, it could be this constant.
      })
      case UnknownInputPattern => Some(true) // pattern unknown - could be weaker.
    }
  }

  override def toString: String = {
    val printedPattern = flag match {
      case Wildcard => "_"
      case SimpleVar(v, None) => v.name
      case SimpleVar(v, Some(_)) => s"${v.name} if ?"
      case SimpleConst(()) => ""
      case SimpleConst(c) => c.toString
      case OtherInputPattern(_, _) => s"<${sha1.substring(0, 4)}...>"
      case UnknownInputPattern => s"?"
    }

    s"$molecule($printedPattern)"
  }

}

/** Compile-time information about an output molecule pattern in a reaction.
  * This class is immutable.
  *
  * @param molecule The molecule emitter value that represents the output molecule.
  * @param flag     Type of the output pattern: either a constant value or other value.
  */
final case class OutputMoleculeInfo(molecule: Molecule, flag: OutputPatternType) {
  override def toString: String = {
    val printedPattern = flag match {
      case SimpleConstOutput(()) => ""
      case SimpleConstOutput(c) => c.toString
      case OtherOutputPattern => "?"
    }

    s"$molecule($printedPattern)"
  }
}

// This class is immutable.
final case class ReactionInfo(inputs: List[InputMoleculeInfo], outputs: Option[List[OutputMoleculeInfo]], guardPresence: GuardPresenceType, sha1: String) {

  // The input pattern sequence is pre-sorted for further use.
  private[jc] val inputsSorted: List[InputMoleculeInfo] = inputs.sortBy { case InputMoleculeInfo(mol, flag, sha) =>
    // Wildcard and SimpleVar without a conditional are sorted together; more specific matchers will precede less specific matchers
    val patternPrecedence = flag match {
      case Wildcard | SimpleVar(_, None) => 3
      case OtherInputPattern(_, _) | SimpleVar(_, Some(_)) => 2
      case SimpleConst(_) => 1
      case _ => 0
    }
    val molValue = flag match {
      case SimpleConst(v) => v.toString
      case _ => ""
    }
    (mol.toString, patternPrecedence, molValue, sha)
  }

  override val toString: String = {
    val inputsInfo = inputsSorted.map(_.toString).mkString(" + ")
    val guardInfo = guardPresence match {
      case GuardAbsent | AllMatchersAreTrivial | GuardPresent(_, None, List()) => ""
      case GuardPresent(_, Some(_), List()) => " if(?)"
      case GuardPresent(_, _, crossGuards) =>
        val crossGuardsInfo = crossGuards.flatMap(_._1).map(_.name).mkString(",")
        s" if($crossGuardsInfo)"
      case GuardPresenceUnknown => " ?if?"
    }
    val outputsInfo = outputs match {
      case Some(outputMoleculeInfos) => outputMoleculeInfos.map(_.toString).mkString(" + ")
      case None => "?"
    }
    s"$inputsInfo$guardInfo => $outputsInfo"
  }
}

/** Represents a reaction body. This class is immutable.
  *
  * @param body Partial function of type {{{ UnapplyArg => Unit }}}
  * @param threadPool Thread pool on which this reaction will be scheduled. (By default, the common pool is used.)
  * @param retry Whether the reaction should be run again when an exception occurs in its body. Default is false.
  */
final case class Reaction(info: ReactionInfo, body: ReactionBody, threadPool: Option[Pool] = None, retry: Boolean) {

  /** Convenience method to specify thread pools per reaction.
    *
    * Example: go { case a(x) => ... } onThreads threadPool24
    *
    * @param newThreadPool A custom thread pool on which this reaction will be scheduled.
    * @return New reaction value with the thread pool set.
    */
  def onThreads(newThreadPool: Pool): Reaction = Reaction(info, body, Some(newThreadPool), retry)

  /** Convenience method to specify the "retry" option for a reaction.
    *
    * @return New reaction value with the "retry" flag set.
    */
  def withRetry: Reaction = Reaction(info, body, threadPool, retry = true)

  /** Convenience method to specify the "no retry" option for a reaction.
    * (This option is the default.)
    *
    * @return New reaction value with the "retry" flag unset.
    */
  def noRetry: Reaction = Reaction(info, body, threadPool, retry = false)

  // Optimization: this is used often.
  val inputMolecules: Seq[Molecule] = info.inputs.map(_.molecule).sortBy(_.toString)

  /** Convenience method for debugging.
    *
    * @return String representation of input molecules of the reaction.
    */
  override val toString: String = s"${inputMolecules.map(_.toString).mkString(" + ")} => ...${if (retry)
    "/R" else ""}"
}

