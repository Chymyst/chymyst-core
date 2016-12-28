package code.chymyst.jc

trait WarningMessage { val isError = false }
trait ErrorMessage { val isError = true }

sealed trait LogMessage { def format: String }

final case class JoinRunInternalMessage(reactionInfo: ReactionInfo,
                                        rs: ReactionSite,
                                        moleculesAsString: String,
                                        exceptionMessage: String) extends LogMessage with ErrorMessage {
  override def format: String =
    s"""In ${rs.toString}: Reaction ${reactionInfo.toString} produced an exception that is internal to JoinRun. Input molecules "
      | "[$moleculesAsString] were not emitted again. Message: $exceptionMessage"""
}


final case class JoinRunAboutMoleculesMessage(reactionInfo: ReactionInfo,
                                              rs: ReactionSite,
                                              moleculesAsString: String,
                                              aboutMolecules: String,
                                              exceptionMessage: String) extends LogMessage with ErrorMessage {
  override def format: String = s"""In ${rs.toString}: Reaction ${reactionInfo.toString} produced an exception. Input molecules "
                                   | [$moleculesAsString] $aboutMolecules. Message: $exceptionMessage"""
}

final case class JoinRunComboOfTwoMessages(reactionInfo: ReactionInfo,
                                           rs: ReactionSite,
                                           blockingMoleculesWithNoReply: Option[String],
                                           blockingMoleculesWithMultipleReply: Option[String],
                                           moleculesAsString: String) extends LogMessage with ErrorMessage {
  override def format: String = {
    val messageNoReply: Option[String] = blockingMoleculesWithNoReply map { s =>
      s"Error: In $this: Reaction {${reactionInfo}} with inputs [$moleculesAsString] finished without replying to $s"
    }
    val messageMultipleReply: Option[String] = blockingMoleculesWithMultipleReply map { s =>
      s"Error: In $this: Reaction {$reactionInfo} with inputs [$moleculesAsString] replied to $s more than once" }

    Seq(messageNoReply, messageMultipleReply).flatten.mkString("; ")

  }

}

final case class ExceptionInJoinRunMessage(e: ExceptionInJoinRun) extends LogMessage with ErrorMessage {
  override def format: String = e.getMessage
}
