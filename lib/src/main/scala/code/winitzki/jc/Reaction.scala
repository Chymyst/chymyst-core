package code.winitzki.jc

import code.winitzki.jc.JoinRun.{Molecule, ReactionBody}

sealed trait PatternType
case object Wildcard extends PatternType
case object SimpleVar extends PatternType
case object SimpleConst extends PatternType
case object OtherPattern extends PatternType

final case class InputMoleculeInfo(molecule: Molecule, flag: PatternType)

final case class ReactionInfo(inputs: List[InputMoleculeInfo], outputs: List[Molecule], sha1: String)

/** Represents a reaction body.
  *
  * @param body Partial function of type {{{ UnapplyArg => Unit }}}
  * @param threadPool Thread pool on which this reaction will be scheduled. (By default, the common pool is used.)
  * @param retry Whether the reaction should be run again when an exception occurs in its body. Default is false.
  */
final case class Reaction(info: ReactionInfo, body: ReactionBody, threadPool: Option[Pool] = None, retry: Boolean = false) {
  lazy val inputMoleculesUsed: Set[Molecule] = info.inputs.map { case InputMoleculeInfo(m, f) => m }.toSet

  /** Convenience method to specify thread pools per reaction.
    *
    * Example: run { case a(x) => ... } onThreads threadPool24
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

  /** Convenience method for debugging.
    *
    * @return String representation of input molecules of the reaction.
    */
  override def toString = s"${inputMoleculesUsed.toSeq.map(_.toString).sorted.mkString(" + ")} => ...${if (retry)
    "/R" else ""}"
}
