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
}
