package code.chymyst.jc

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.collection.mutable


sealed trait Severity
trait WarningSeverity extends Severity
trait ErrorSeverity extends Severity

case class Message1(m: Molecule, rs: ReactionSite) extends ErrorSeverity {
  def show: String = ???  // the actual name of show is unimportant here and not required, provided message1Show
  // makes use of Messsage1 meaningfully and can obtain a String.
}

case class Message2(rs: ReactionSite, r1: ReactionInfo, r2: ReactionInfo, m: Molecule) extends WarningSeverity {
  def show: String = ???  // the actual name of show is unimportant here and not required, provided message2Show
  // makes use of Messsage2 meaningfully and can obtain a String.
}

trait Show[A] {
  def show(a: A): String
}

object Show {
  def apply[A](f: A => String): Show[A] = new Show[A] {
    def show(a: A): String = f(a)
  }
  implicit def message1Show: Show[Message1] = Show(_.show)
  implicit def message2Show: Show[Message2] = Show(_.show)
}

sealed trait ReportSeverity
case object ErrorSeverity extends ReportSeverity
case object WarningSeverity extends ReportSeverity
object ReportSeverity {
  implicit def toString(s: ReportSeverity): String =
    s match {
      case ErrorSeverity => "ERROR"
      case WarningSeverity => "WARNING"
      case _ => "UNKNOWN SEVERITY"
    }
  // unclear as to what numbers are most useful or convey better normal usage convention (normally higher numbers represent worse
  // conditions)
  implicit def toInt(s: ReportSeverity): Int =
  s match {
    case WarningSeverity => 1
    case ErrorSeverity => 2
    case _ => 0
  }
}

// possible consideration for a unique error code, which would be possible if error messages were not dynamically built
// (or could use a top level error code as representative). Report consumers assumed to be English speakers (format and fArgs is not international).
final case class ErrorReport(severity: ReportSeverity, format: String, fArgs: Seq[String]) {
  lazy val formatted: String = format.format(fArgs:_*)
}

object Core {

  /** A special value for {{{ReactionInfo}}} to signal that we are not running a reaction.
    *
    */
  private[jc] val emptyReactionInfo = ReactionInfo(Nil, None, GuardPresenceUnknown, "")

  private lazy val sha1Digest = java.security.MessageDigest.getInstance("SHA-1")

  def getSha1(c: Any): String = sha1Digest.digest(c.toString.getBytes("UTF-8")).map("%02X".format(_)).mkString

  //  def flatten[T](optionSet: Option[Set[T]]): Set[T] = optionSet.getOrElse(Set())
  //  def flatten[T](optionSeq: Option[Seq[T]]): Seq[T] = optionSeq.getOrElse(Seq())


  def nonemptyOpt[S](s: Seq[S]): Option[Seq[S]] = if (s.isEmpty) None else Some(s)


  /** Add a random shuffle method to sequences.
    *
    * @param a Sequence to be shuffled.
    * @tparam T Type of sequence elements.
    */
  implicit final class ShufflableSeq[T](a: Seq[T]) {
    /** Shuffle sequence elements randomly.
      *
      * @return A new sequence with randomly permuted elements.
      */
    def shuffle: Seq[T] = scala.util.Random.shuffle(a)
  }
  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyOpsEquals[A](self: A) {
    def ===(other: A): Boolean = self == other
  }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyOpsNotEquals[A](self: A) {
    def =!=(other: A): Boolean = self != other
  }


  val defaultSitePool = new FixedPool(2)
  val defaultReactionPool = new FixedPool(4)


  // Wait until the reaction site to which `molecule` is bound becomes quiescent, then emit `callback`.
  // TODO: implement
  def waitUntilQuiet[T](molecule: M[T], callback: E): Unit = molecule.site.setQuiescenceCallback(callback)

  /** Create a reaction value out of a simple reaction body. Used only for testing.
    * The reaction body must be "simple" in the sense that it allows very limited pattern-matching with molecule values:
    * - all patterns must be simple variables or wildcards, or {{{null}}} or zero constant values, except the last molecule in the reaction.
    * - the last molecule in the reaction can have a nontrivial pattern matcher.
    *
    * The only reason this method exists is to enable testing JoinRun without depending on the macro package.
    * Since this method does not provide a full compile-time analysis of reactions, it should be used only for internal testing and debugging of JoinRun itself.
    * At the moment, this method is used in benchmarks and unit tests of JoinRun that run without depending on the macro package.
    *
    * @param body Body of the reaction. Should not contain any pattern-matching on molecule values, except possibly for the last molecule in the list of input molecules.
    * @return Reaction value. The [[ReactionInfo]] structure will be filled out in a minimal fashion (only has information about input molecules, and all patterns are "unknown").
    */
  private[jc] def _go(body: ReactionBody): Reaction = {
    val moleculesInThisReaction = UnapplyCheckSimple(mutable.MutableList.empty)
    body.isDefinedAt(moleculesInThisReaction)
    // detect nonlinear patterns
    val duplicateMolecules = moleculesInThisReaction.inputMolecules diff moleculesInThisReaction.inputMolecules.distinct
    if (duplicateMolecules.nonEmpty) throw new ExceptionInJoinRun(s"Nonlinear pattern: ${duplicateMolecules.mkString(", ")} used twice")
    val inputMoleculesUsed = moleculesInThisReaction.inputMolecules.toList
    val inputMoleculeInfo = inputMoleculesUsed.map(m => InputMoleculeInfo(m, UnknownInputPattern, UUID.randomUUID().toString))
    val simpleInfo = ReactionInfo(inputMoleculeInfo, None, GuardPresenceUnknown, UUID.randomUUID().toString)
    Reaction(simpleInfo, body, retry = false)
  }

  /**
    * Convenience syntax: users can write a(x)+b(y) to emit several molecules at once.
    * (However, the molecules are emitted one by one in the present implementation.)
    *
    * @param x the first emitted molecule
    * @return a class with a + operator
    */
  implicit final class EmitMultiple(x: Unit) {
    def +(n: Unit): Unit = ()
  }

  /** Type alias for reaction body.
    *
    */
  private[jc] type ReactionBody = PartialFunction[UnapplyArg, Any]

  // for M[T] molecules, the value inside AbsMolValue[T] is of type T; for B[T,R] molecules, the value is of type
  // ReplyValue[T,R]. For now, we don't use shapeless to enforce this typing relation.
  private[jc] type MoleculeBag = MutableBag[Molecule, AbsMolValue[_]]
  private[jc] type MutableLinearMoleculeBag = mutable.Map[Molecule, AbsMolValue[_]]
  private[jc] type LinearMoleculeBag = Map[Molecule, AbsMolValue[_]]

  private[jc] def moleculeBagToString(mb: MoleculeBag): String =
    mb.getMap.toSeq
      .map{ case (m, vs) => (m.toString, vs) }
      .sortBy(_._1)
      .flatMap {
        case (m, vs) => vs.map {
          case (mv, 1) => s"$m($mv)"
          case (mv, i) => s"$m($mv) * $i"
        }
      }.sorted.mkString(", ")

  private[jc] def moleculeBagToString(mb: LinearMoleculeBag): String =
    mb.map {
      case (m, jmv) => s"$m($jmv)"
    }.toSeq.sorted.mkString(", ")

  def site(reactions: Reaction*): WarningsAndErrors = site(defaultReactionPool, defaultSitePool)(reactions: _*)
  def site(reactionPool: Pool)(reactions: Reaction*): WarningsAndErrors = site(reactionPool, reactionPool)(reactions: _*)

  /** Create a reaction site with one or more reactions.
    * All input and output molecules in reactions used in this site should have been
    * already defined, and input molecules should not be already bound to another site.
    *
    * @param reactions One or more reactions of type [[Reaction]]
    * @param reactionPool Thread pool for running new reactions.
    * @param sitePool Thread pool for use when making decisions to schedule reactions.
    * @return List of warning messages.
    */
  def site(reactionPool: Pool, sitePool: Pool)(reactions: Reaction*): WarningsAndErrors = {

    // Create a reaction site object holding the given local chemistry.
    // The constructor of ReactionSite will perform static analysis of all given reactions.
    new ReactionSite(reactions, reactionPool, sitePool).diagnostics

  }

  private val errorLog = new ConcurrentLinkedQueue[ErrorReport]
  private[jc] def reportError(report: ErrorReport): Unit = {
    errorLog.add(report)
    ()
  }

  def globalErrorLog: Iterable[ErrorReport] = errorLog.iterator().asScala.toIterable
}

