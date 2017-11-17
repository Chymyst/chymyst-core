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
    lazy val (request, propose0, accept0, learn0) = createNode(0, tp, Some(response))
    lazy val (_, propose1, accept1, learn1) = createNode(1, tp)
    lazy val (_, propose2, accept2, learn2) = createNode(2, tp)

    lazy val proposeMolecules = Seq(propose0, propose1, propose2)
    lazy val acceptMolecules = Seq(accept0, accept1, accept2)
    lazy val learnMolecules = Seq(learn0, learn1, learn2)

    def createNode(index: Int, tp: Pool, response: Option[M[String]] = None) = {

      def proposalNumber: ProposalNumber = ProposalNumber(timestamp, index)

      val acceptorNode = m[AcceptorNodeState[String]]
      val learnerNode = m[LearnerNodeState[String]]
      val proposerNode = m[ProposerNodeState[String]]

      val clientRequest = m[String]
      val propose = m[PermissionRequest[String]]
      val promise = m[PermissionResponse[String]]
      val accept = m[Accepted[String]]
      val learn = m[String]

      // Each node plays all three roles.
      // Reactions for one node:
      site(tp)(
        go { case proposerNode(_) + clientRequest(v) ⇒
          // Send a proposal to all acceptor nodes.
          val proposal = PermissionRequest(proposalNumber, promise)
          proposeMolecules.foreach(_.apply(proposal))
          val newState = ProposerNodeState(Some(Proposal(proposalNumber, v)))
          proposerNode(newState)
        },
        go { case acceptorNode(state) + propose(permissionRequest) ⇒
          val requestedProposalNumber = permissionRequest.proposalNumber
          val lastSeenProposalNumber = state.rejectBelow match {
            case Some(p) if p > requestedProposalNumber ⇒ p
            case _ ⇒ requestedProposalNumber
          }
          val responseToProposer: PermissionResponse[String] = if (lastSeenProposalNumber > requestedProposalNumber)
            PermissionDenied
          else PermissionGranted(requestedProposalNumber, state.lastAccepted)

          permissionRequest.proposerResponse(responseToProposer)
          acceptorNode(state.copy(rejectBelow = Some(lastSeenProposalNumber)))
        },
        go { case acceptorNode(state) + accept(proposal) ⇒

        },
        go { case proposerNode(state) + promise(promised) ⇒
          promised match {
            case PermissionGranted(initialProposalNumber, p) ⇒
              val newProposal: Option[Proposal[String]] = p match {
                case Some(proposal) ⇒ state.lastProposal match {
                  case Some(lastProposal) if proposal.number <= lastProposal.number ⇒ Some(lastProposal)
                  case _ ⇒ Some(proposal)
                }
                case None ⇒ state.lastProposal
              }
              val newPromisesReceived = state.promisesReceived + 1
              proposerNode(state.copy(lastProposal = newProposal, promisesReceived = newPromisesReceived))
              if (newPromisesReceived > nodes /2) {
                // Stage 1 consensus achieved.
                acceptMolecules.foreach(_.apply(newProposal.get.number))
              }

            case PermissionDenied ⇒
              proposerNode(state.copy(promisesReceived = 0))
              state.lastProposal.foreach(p ⇒ clientRequest(p.value)) // Make another attempt to achieve consensus for this value.
          }
        },
        go { case learnerNode(state) + learn(proposal) ⇒
        }
      )

      acceptorNode(AcceptorNodeState())
      learnerNode(LearnerNodeState())
      proposerNode(ProposerNodeState())

      (clientRequest, propose, accept, learn)
    }

    site(tp)(
      go { case response(v) + fetch(_, r) ⇒ r(v) }
    )

    request("abc")
    request("xyz")
    val result = fetch()
    result shouldEqual "abc"
    tp.shutdownNow()
  }

  def timestamp: Long = System.nanoTime()

  final case class AcceptorNodeState[T](rejectBelow: Option[ProposalNumber] = None, lastAccepted: Option[Proposal[T]] = None)

  final case class ProposerNodeState[T](lastProposal: Option[Proposal[T]] = None, promisesReceived: Int = 0, acksReceived: Int = 0)

  final case class LearnerNodeState[T](lastSeenProposalNumber: Option[Long] = None, promisedValue: Option[T] = None)

  sealed trait PermissionResponse[+T]

  final case class PermissionGranted[T](initialProposalNumber: ProposalNumber, lastAccepted: Option[Proposal[T]] = None) extends PermissionResponse[T]

  case object PermissionDenied extends PermissionResponse[Nothing]

  final case class PermissionRequest[T](proposalNumber: ProposalNumber, proposerResponse: M[PermissionResponse[T]])

  final case class Suggestion[T](proposal: Proposal[T], proposer: M[ProposerNodeState[T]])

  type Accepted[T] = ProposalNumber

  final case class Proposal[T](number: ProposalNumber, value: T)

  final case class ProposalNumber(n: Long, i: Int) extends Ordered[ProposalNumber] {

    import scala.math.Ordered.orderingToOrdered

    override def compare(that: ProposalNumber): Int = (n, i) compare(that.n, that.i)
  }

}
