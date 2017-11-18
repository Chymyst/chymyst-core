package io.chymyst.test

import io.chymyst.jc._

// See https://understandingpaxos.wordpress.com/

class PaxosSpec extends LogSpec {

  behavior of "Paxos algorithm"

  it should "run single-decree Paxos with three nodes" in {
    val tp = FixedPool(4)

    val response = m[String]
    val fetch = b[Unit, String]

    val nodes = 3
    lazy val (request, propose0: M[PermissionRequest[String]], accept0) = createNode(0, tp, Some(response))
    lazy val (_, propose1, accept1) = createNode(1, tp)
    lazy val (_, propose2, accept2) = createNode(2, tp)

    lazy val proposeMolecules = Seq(propose0, propose1, propose2)
    lazy val acceptMolecules = Seq(accept0, accept1, accept2)

    def createNode(index: Int, tp: Pool, response: Option[M[String]] = None): (M[String], M[PermissionRequest[String]], M[Suggestion[String]]) = {

      def proposalNumber(): ProposalNumber = ProposalNumber(timestamp, index)

      def computeAcceptorResponse[T](
        state: AcceptorNodeState[T],
        requestedProposalNumber: ProposalNumber
      ): (ProposalNumber, PermissionResponse[T]) = {
        val lastSeenProposalNumber = state.rejectBelow match {
          case Some(p) if p > requestedProposalNumber ⇒ p
          case _ ⇒ requestedProposalNumber
        }
        val responseToProposer: PermissionResponse[T] =
          if (lastSeenProposalNumber > requestedProposalNumber)
            PermissionDenied
          else PermissionGranted(requestedProposalNumber, state.lastAccepted)

        (lastSeenProposalNumber, responseToProposer)
      }

      def computeNewProposal[T](p: Option[Proposal[T]], lastProposal: Option[Proposal[T]]): Option[Proposal[T]] = {
        p match {
          case Some(proposal) ⇒ lastProposal match {
            case Some(lastP) if proposal.number <= lastP.number ⇒ Some(lastP)
            case _ ⇒ Some(proposal)
          }
          case None ⇒ lastProposal
        }
      }

      val acceptorNode = m[AcceptorNodeState[String]]
      val proposerNode = m[ProposerNodeState[String]]

      val clientRequest = m[String]
      val propose = m[PermissionRequest[String]]
      val promise = m[PermissionResponse[String]]
      val accept = m[Suggestion[String]]
      val learn = m[PermissionResponse[String]]

      // Each node plays all three roles.
      // Reactions for one node:
      site(tp)(
        go { case proposerNode(_) + clientRequest(v) ⇒
          // Send a proposal to all acceptor nodes.
          val newNumber = proposalNumber()
          val proposal = PermissionRequest(newNumber, promise)
          proposeMolecules.foreach(_.apply(proposal))
          val newState = ProposerNodeState(Some(Proposal(newNumber, v)))
          proposerNode(newState)
        },
        go { case acceptorNode(state) + propose(PermissionRequest(proposalNumber, proposerResponse)) ⇒
          val (lastProposalNumber, newResponse) = computeAcceptorResponse(state, proposalNumber)
          proposerResponse(newResponse)
          acceptorNode(state.copy(rejectBelow = Some(lastProposalNumber)))
        },
        go { case acceptorNode(state) + accept(Suggestion(proposal, proposer)) ⇒
          val (lastProposalNumber, newResponse) = computeAcceptorResponse(state, proposal.number)
          proposer(newResponse)
          acceptorNode(state.copy(rejectBelow = Some(lastProposalNumber)))
        },
        go { case proposerNode(state) + promise(permissionResponse) ⇒
          permissionResponse match {
            case PermissionGranted(_, p) ⇒
              val newProposal: Option[Proposal[String]] = computeNewProposal(p, state.lastProposal)
              val newPromisesReceived = state.promisesReceived + 1
              proposerNode(state.copy(lastProposal = newProposal, promisesReceived = newPromisesReceived))
              if (newPromisesReceived > nodes / 2) {
                // Stage 1 consensus achieved.
                acceptMolecules.foreach(_.apply(Suggestion(newProposal.get, learn)))
              }

            case PermissionDenied ⇒
              // Reset state.
              proposerNode(state.copy(promisesReceived = 0, acksReceived = 0))
              state.lastProposal.foreach(p ⇒ clientRequest(p.value)) // Make another attempt to achieve consensus for this value.
          }
        },
        go { case proposerNode(state) + learn(permissionResponse) ⇒
          permissionResponse match {
            case PermissionGranted(_, p) ⇒
              val newProposal: Option[Proposal[String]] = computeNewProposal(p, state.lastProposal)
              val newAcksReceived = state.acksReceived + 1
              proposerNode(state.copy(lastProposal = newProposal, acksReceived = newAcksReceived))
              if (newAcksReceived > nodes / 2) {
                // Stage 2 consensus achieved. Send response.
                response.foreach(_.apply(newProposal.get.value))
              }

            case PermissionDenied ⇒
              // Reset state.
              proposerNode(state.copy(promisesReceived = 0, acksReceived = 0))
              state.lastProposal.foreach(p ⇒ clientRequest(p.value)) // Make another attempt to achieve consensus for this value.
          }
        }
      )

      acceptorNode(AcceptorNodeState())
      proposerNode(ProposerNodeState())

      (clientRequest, propose, accept)
    }

    site(tp)(
      go { case response(v) + fetch(_, r) ⇒ r(v) }
    )

    request("abc")
    request("xyz")
    val result = fetch()
    result shouldEqual "xyz"
    tp.shutdownNow()
  }

  def timestamp: Long = System.nanoTime()

  final case class AcceptorNodeState[T](rejectBelow: Option[ProposalNumber] = None, lastAccepted: Option[Proposal[T]] = None)

  final case class ProposerNodeState[T](lastProposal: Option[Proposal[T]] = None, promisesReceived: Int = 0, acksReceived: Int = 0)

  sealed trait PermissionResponse[+T]

  final case class PermissionGranted[T](initialProposalNumber: ProposalNumber, lastAccepted: Option[Proposal[T]] = None) extends PermissionResponse[T]

  case object PermissionDenied extends PermissionResponse[Nothing]

  final case class PermissionRequest[T](proposalNumber: ProposalNumber, proposerResponse: M[PermissionResponse[T]])

  final case class Suggestion[T](proposal: Proposal[T], proposer: M[PermissionResponse[T]])

  type Accepted[T] = ProposalNumber

  final case class Proposal[T](number: ProposalNumber, value: T)

  final case class ProposalNumber(n: Long, i: Int) extends Ordered[ProposalNumber] {

    import scala.math.Ordered.orderingToOrdered

    override def compare(that: ProposalNumber): Int = (n, i) compare((that.n, that.i))
  }

}
