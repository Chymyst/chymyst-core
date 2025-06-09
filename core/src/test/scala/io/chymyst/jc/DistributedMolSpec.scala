package io.chymyst.jc

import io.chymyst.test.LogSpec
import org.scalatest.Matchers

class DistributedMolSpec extends LogSpec with Matchers {

  behavior of "distributed molecules"

  it should "print DMs correctly" in {
    implicit val clusterConfig = ClusterConfig("", "1")
    val x = dm[Int]
    x.isDistributed shouldEqual true
    x.toString shouldEqual "x/D"
    x.clusterConfig shouldEqual ClusterConfig("", "1")
    x.isBlocking shouldEqual false
    x.isBound shouldEqual false
    x.isStatic shouldEqual false
    x.isPipelined shouldEqual false
  }

  it should "emit DMs using a test-only cluster config" in {
    implicit val clusterConfig = ClusterConfig("", "2")
    val x = dm[Int]

    site(go { case x(_) ⇒ })
    x.isBound shouldEqual true

    val n: Int = 123
    x(n)

    // Connector should reflect an emitted molecule.
    val connector: TestOnlyConnector = Cluster.connectors(clusterConfig).asInstanceOf[TestOnlyConnector]
    connector.sessionId().nonEmpty shouldEqual true

    // The test-only connector should now have this molecule in its dictionary. The deserialized data must match.
    Cluster.deserialize[Int](connector.allMoleculeData.values.head) shouldEqual n
    // The path to the molecule must be of the form <headPath>/v-0
    val headPath = connector.molValueCounters.keys.head
    connector.allMoleculeData.keySet should contain(headPath + "/v-0")

    val oldSession = connector.sessionId().get
    connector.updateSession()
    val newSession = connector.sessionId().get
    oldSession should not equal newSession
    connector.invalidateSession()
    connector.sessionId() shouldEqual None
    connector.updateSession()
    connector.sessionId().nonEmpty shouldEqual true
  }

  behavior of "serializing data on DMs"

  it should "serialize complicated data" in {
    val data: TestData[Boolean] = TestData(true, "xyz", List(1, 2, 3))
    val serialized: Array[Byte] = Cluster.serialize(data)
    val roundtrip = Cluster.deserialize[TestData[Boolean]](serialized)
    roundtrip shouldEqual data
  }

  it should "serialize M emitters" in {
    val x = m[Int]
    // Serializing an unbound molecule emitter.
    the[ExceptionEmittingDistributedMol] thrownBy Cluster.serialize(x) should have message "Data on a DM cannot be serialized because emitter x is not bound"
    site(go { case x(_) ⇒ })
    // Serializing a molecule emitter bound to a non-distributed reaction site.
    Cluster.deserialize[M[Int]](Cluster.serialize(x)) shouldEqual x
  }

  it should "serialize M and B emitters" in {
    val x = m[B[Int, Int]]
    val y = b[Int, Int]
    // Serializing an unbound molecule emitter.
    the[ExceptionEmittingDistributedMol] thrownBy Cluster.serialize(x) should have message "Data on a DM cannot be serialized because emitter x is not bound"
    site(go { case x(_) ⇒ }, go { case y(_, r) ⇒ r(0) })
    // Serializing a molecule emitter bound to a non-distributed reaction site.
    Cluster.deserialize[M[B[Int, Int]]](Cluster.serialize(x)) shouldEqual x
    Cluster.deserialize[B[Int, Int]](Cluster.serialize(y)) shouldEqual y
  }

  it should "serialize DM emitter when it is emitted as data" in {
    implicit val clusterConfig = ClusterConfig("", "3")
    val x = dm[DM[Int]]
    val y = dm[Int]
    x.isDistributed shouldEqual true
    x.isBound shouldEqual false
    // This should fail.

    the[ExceptionEmittingDistributedMol] thrownBy Cluster.serialize(x) should have message "Data on a DM cannot be serialized because emitter x/D is not bound"
    the[ExceptionEmittingDistributedMol] thrownBy Cluster.serialize(y) should have message "Data on a DM cannot be serialized because emitter y/D is not bound"

    site(go { case x(_) ⇒ }, go { case y(_) ⇒ })
    x.isBound shouldEqual true
    y.isBound shouldEqual true

    // We should be able to serialize x and y now, since they are both bound.
    val x2Serialized = Cluster.serialize(x)
    Cluster.deserialize[DM[DM[Int]]](x2Serialized) shouldEqual x
    Cluster.deserialize[DM[Int]](Cluster.serialize(y)) shouldEqual y

    // When we emit x(y), we need to serialize a DM emitter.
    x(y)

    // Check that the data is written correctly.
    val connector: TestOnlyConnector = Cluster.connectors(clusterConfig).asInstanceOf[TestOnlyConnector]
    connector.sessionId().nonEmpty shouldEqual true

    // The path to the molecule must be of the form <headPath>/v-0
    val headPath = connector.molValueCounters.keys.head
    connector.allMoleculeData.keySet should contain(headPath + "/v-0")
    // The test-only connector should now have this molecule in its dictionary. The deserialized data must match.
    Cluster.deserialize[DM[Int]](connector.allMoleculeData.values.head) shouldEqual y
  }
}

// This case class cannot be an inner class in the test, or else serialization will be impossible.
case class TestData[A](x: A, y: String, z: List[Int])
