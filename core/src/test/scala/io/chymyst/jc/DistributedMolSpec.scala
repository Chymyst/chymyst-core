package io.chymyst.jc

import io.chymyst.test.LogSpec
import org.scalatest.Matchers

class DistributedMolSpec extends LogSpec with Matchers {

  behavior of "distributed molecules"

  it should "print DMs correctly" in {
    implicit val clusterConfig = ClusterConfig("")
    val x = dm[Int]
    x.isDistributed shouldEqual true
    x.toString shouldEqual "x/D"
    x.clusterConfig shouldEqual ClusterConfig("")
    x.isBlocking shouldEqual false
    x.isBound shouldEqual false
    x.isStatic shouldEqual false
    x.isPipelined shouldEqual false
  }

  it should "emit DMs using a test-only cluster config" in {
    implicit val clusterConfig = ClusterConfig("")
    val x = dm[Int]

    site(go { case x(_) ⇒ })
    x.isBound shouldEqual true
    
    val n: Int = 123
    x(n)

    // Connector should reflect an emitted molecule.
    val connector: TestOnlyConnector = Cluster.connectors(clusterConfig).asInstanceOf[TestOnlyConnector]
    connector.sessionId().nonEmpty shouldEqual true

    // The test-only connector should now have this molecule in its dictionary.
    Cluster.deserialize[Int](connector.allMoleculeData.values.head) shouldEqual n
    // The path to the molecule must be of the form <headPath>/v-0
    val headPath = connector.molValueCounters.keys.head
    connector.allMoleculeData.keySet should contain (headPath + "/v-0")
  }
}
