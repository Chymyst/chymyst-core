package io.chymyst.jc

import java.util

import com.twitter.chill.ScalaKryoInstantiator
import io.chymyst.jc.Core.ClusterSessionId
import org.apache.curator.framework.{AuthInfo, CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.RetryNTimes
import org.apache.zookeeper.CreateMode

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.concurrent.TrieMap

final case class ClusterConfig(
  url: String,
  username: String = "",
  password: String = "",
  connectionTimeoutMs: Int = 500,
  numRetries: Int = 5,
  retryIntervalMs: Int = 1000
) {
  /** Each DCM peer is uniquely identified by this ID.
    *
    */
  val peerId: String = Core.getSha1(this.toString + Cluster.guid, Core.getMessageDigest)
}

private[jc] final case class ClusterConnector(clusterConfig: ClusterConfig) {
  private[jc] def emit[T](reactionSite: ReactionSite, mol: DM[T], value: T): Unit = {
    val path: String = s"DCM/${reactionSite.sha1CodeWithNames}/dm-${mol.siteIndex}/v"
    val molData = Cluster.serialize(value)
    val createMode: CreateMode = CreateMode.PERSISTENT_SEQUENTIAL
    zk.create().creatingParentsIfNeeded().withMode(createMode).forPath(path, molData)
  }

  private[jc] def emit[T](reactionSite: ReactionSite, mol: DM[T], value: T, currentSessionId: ClusterSessionId): Unit = ???

  private val zk: CuratorFramework = CuratorFrameworkFactory.builder
    .connectString(clusterConfig.url)
    .connectionTimeoutMs(clusterConfig.connectionTimeoutMs)
    .retryPolicy(new RetryNTimes(clusterConfig.numRetries, clusterConfig.retryIntervalMs))
    .authorization(List[AuthInfo](new AuthInfo("digest", (clusterConfig.username + ":" + clusterConfig.password).getBytes("UTF-8"))).asJava)
    .build

  def start(): Unit = zk.start()

  def sessionId: Option[ClusterSessionId] = {
    if (zk.getZookeeperClient.isConnected)
      Some(ClusterSessionId(zk.getZookeeperClient.getZooKeeper.getSessionId))
    else None
  }

  private val reactionSites: TrieMap[String, ReactionSite] = new TrieMap()

  def addReactionSite(reactionSite: ReactionSite): Unit = {
    reactionSites.getOrElseUpdate(reactionSite.sha1CodeWithNames, reactionSite)
  }

  start()
}

object Cluster {
  /** This value is used to compute the client ID, which needs to be unique and to persist per JVM lifetime.
    *
    */
  val guid: String = java.util.UUID.randomUUID().toString

  // This code is taken from the chill-scala test suite.
  def serialize[T](t: T): Array[Byte] = ScalaKryoInstantiator.defaultPool.toBytesWithClass(t)

  def deserialize[T](bytes: Array[Byte]): T = ScalaKryoInstantiator.defaultPool.fromBytes(bytes).asInstanceOf[T]

  /** For each `ClusterConfig` value, a separate cluster connection is maintained by `ClusterConnector`
    * values in this dictionary. The values are created whenever a DRS is activated that uses a given cluster.
    * There is only one `ClusterConnector` for all DRSs using the same cluster.
    */
  private[jc] val connectors: TrieMap[ClusterConfig, ClusterConnector] = new TrieMap()

  private[jc] def addClusterConnector(reactionSite: ReactionSite)(clusterConfig: ClusterConfig): ClusterConfig = {
    val connector = connectors.getOrElseUpdate(clusterConfig, ClusterConnector(clusterConfig))
    connector.addReactionSite(reactionSite)
    clusterConfig
  }
}

final class ClusterBag[T](clusterConnector: ClusterConfig) extends MutableBag[T] {
  override def find(predicate: T ⇒ Boolean): Option[T] = ???

  override protected def iteratorAsScala: Iterator[T] = ???

  override protected def iteratorAsJava: util.Iterator[T] = ???

  override def getCountMap: Map[T, Int] = ???

  /** List all values, perhaps with repetitions.
    * It is not guaranteed that the values will be repeated the correct number of times.
    *
    * @return An iterator of values.
    */
  override def allValues: Iterator[T] = ???

  /** List all values, with repetitions, excluding values from a given sequence (which can also contain repeated values).
    * It is guaranteed that the values will be repeated the correct number of times.
    *
    * @param skipping A sequence of values that should be skipped while running the iterator.
    * @return An iterator of values.
    */
  override def allValuesSkipping(skipping: MutableMultiset[T]): Iterator[T] = ???

  override def size: Int = 0

  override def add(v: T): Unit = ???

  override def remove(v: T): Boolean = ???
}
