package code.chymyst.jc

sealed trait LogMessage {
  val reactionSite: ReactionSite
  def format: String
}

final case class JoinRunInternalMessage(reactionInfo: ReactionInfo,
                                        override val reactionSite: ReactionSite,
                                        moleculesAsString: String,
                                        exceptionMessage: String) extends LogMessage {
  override def format: String =
    s"""In ${reactionSite.toString}: Reaction ${reactionInfo.toString} produced an exception that is internal to JoinRun. Input molecules "
      | "[$moleculesAsString] were not emitted again. Message: $exceptionMessage"""
}


final case class JoinRunAboutMoleculesMessage(reactionInfo: ReactionInfo,
                                              override val reactionSite: ReactionSite,
                                              moleculesAsString: String,
                                              aboutMolecules: String,
                                              exceptionMessage: String) extends LogMessage {
  override def format: String = s"""In ${reactionSite.toString}: Reaction ${reactionInfo.toString} produced an exception. Input molecules "
                                   | [$moleculesAsString] $aboutMolecules. Message: $exceptionMessage"""
}

final case class JoinRunComboOfTwoMessages(reactionInfo: ReactionInfo,
                                           override val reactionSite: ReactionSite,
                                           blockingMoleculesWithNoReply: Option[String],
                                           blockingMoleculesWithMultipleReply: Option[String],
                                           moleculesAsString: String) extends LogMessage {
  override def format: String = {
    val messageNoReply: Option[String] = blockingMoleculesWithNoReply map { s =>
      s"Error: In $this: Reaction {${reactionInfo}} with inputs [$moleculesAsString] finished without replying to $s"
    }
    val messageMultipleReply: Option[String] = blockingMoleculesWithMultipleReply map { s =>
      s"Error: In $this: Reaction {$reactionInfo} with inputs [$moleculesAsString] replied to $s more than once" }

    Seq(messageNoReply, messageMultipleReply).flatten.mkString("; ")

  }

}

final case class ExceptionInJoinRunMessage(override val reactionSite: ReactionSite,
                                           e: ExceptionInJoinRun) extends LogMessage {
  override def format: String = e.getMessage
}
