package io.chymyst.jc

import io.chymyst.jc.Core.InputMoleculeList

trait Reporter {
  def emitted[T](mol: MolEmitter, value: T): Unit

  def reactionSiteCreated(reactionSite: ReactionSite): Unit

  def schedulerStep(mol: MolEmitter): Unit

  def reactionScheduled(mol: MolEmitter, reaction: Reaction): Unit

  def reactionStarted(reaction: Reaction, inputs: InputMoleculeList): Unit

  def reactionFinished(reaction: Reaction, inputs: InputMoleculeList, status: ReactionExitStatus): Unit
}

class EmptyReporter extends Reporter {override def emitted[T](mol: MolEmitter, value: T): Unit = ()

  override def reactionSiteCreated(reactionSite: ReactionSite): Unit = ()

  override def schedulerStep(mol: MolEmitter): Unit = ()

  override def reactionScheduled(mol: MolEmitter, reaction: Reaction): Unit = ()

  override def reactionStarted(reaction: Reaction, inputs: InputMoleculeList): Unit = ()

  override def reactionFinished(reaction: Reaction, inputs: InputMoleculeList, status: ReactionExitStatus): Unit = ()
}