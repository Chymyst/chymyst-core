package io.chymyst.jc

import io.chymyst.jc.Core._

import scala.concurrent.duration.Duration

trait Reporter {
  def emitted(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String, moleculesPresent: ⇒ String): Unit = ()

  def removed(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String, moleculesPresent: ⇒ String): Unit = ()

  def replyReceived(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, molValue: ⇒ String): Unit = ()

  def replyTimedOut(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, timeout: Duration): Unit = ()

  def reactionSiteCreated(rsId: ReactionSiteId, rsString: ReactionSiteString): Unit = ()

  def schedulerStep(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, moleculesPresent: ⇒ String): Unit = ()

  def reactionScheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString, reaction: ReactionString, inputs: ⇒ String, remainingMols: ⇒ String): Unit = ()

  def noReactionScheduled(rsId: ReactionSiteId, rsString: ReactionSiteString, molIndex: MolSiteIndex, mol: MolString): Unit = ()

  def reactionStarted(rsId: ReactionSiteId, rsString: ReactionSiteString, reaction: ReactionString, inputs: ⇒ String): Unit = ()

  def reactionFinished(rsId: ReactionSiteId, rsString: ReactionSiteString, reaction: ReactionString, inputs: ⇒ String, status: ReactionExitStatus): Unit = ()

  def errorReport(rsId: ReactionSiteId, rsString: ReactionSiteString, message: String, printToConsole: Boolean = false): Unit = ()
}

object NoopReporter extends Reporter
