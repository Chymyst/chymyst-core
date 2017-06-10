package io.chymyst.jc

import io.chymyst.jc.Core.InputMoleculeList

import scala.concurrent.duration.Duration

trait Reporter {
  def emitted[T](mol: MolEmitter, value: T): Unit

  def replyReceived[T, R](mol: B[T, R], value: R): Unit

  def replyTimedOut[T, R](mol: B[T, R], timeout: Duration): Unit

  def reactionSiteCreated(reactionSite: ReactionSite): Unit

  def schedulerStep(mol: MolEmitter): Unit

  def reactionScheduled(mol: MolEmitter, reaction: Reaction): Unit

  def reactionStarted(reaction: Reaction, inputs: InputMoleculeList): Unit

  def reactionFinished(reaction: Reaction, inputs: InputMoleculeList, status: ReactionExitStatus): Unit

  def errorReport(reactionSite: ReactionSite, message: String): Unit
}

object NoopReporter extends Reporter {
  def emitted[T](mol: MolEmitter, value: T): Unit = ()

  def replyReceived[T, R](mol: B[T, R], value: R): Unit = ()

  def replyTimedOut[T, R](mol: B[T, R], timeout: Duration): Unit = ()

  def reactionSiteCreated(reactionSite: ReactionSite): Unit = ()

  def schedulerStep(mol: MolEmitter): Unit = ()

  def reactionScheduled(mol: MolEmitter, reaction: Reaction): Unit = ()

  def reactionStarted(reaction: Reaction, inputs: InputMoleculeList): Unit = ()

  def reactionFinished(reaction: Reaction, inputs: InputMoleculeList, status: ReactionExitStatus): Unit = ()

  def errorReport(reactionSite: ReactionSite, message: String): Unit = ()
}