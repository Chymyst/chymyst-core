package io.chymyst.jc

import java.util

import com.twitter.chill.ScalaKryoInstantiator
import io.chymyst.jc.Core.ClusterSessionId
import org.apache.curator.framework.{AuthInfo, CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.RetryNTimes
import org.apache.zookeeper.CreateMode
import Core.AnyOpsEquals
import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.concurrent.TrieMap

/** Configuration value that describes how to connect to a cluster.
  *
  * @param url                 ZooKeeper URL or empty string. Empty string means a non-distributed cluster, used only for testing.
  * @param username            ZooKeeper username string or empty string if no authentication is desired for ZooKeeper connection.
  * @param password            ZooKeeper password string.
  * @param connectionTimeoutMs ZooKeeper connection timeout in milliseconds.
  * @param numRetries          Number of retries for connecting.
  * @param retryIntervalMs     Retry interval in milliseconds.
  */
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

private[jc] sealed trait ClusterConnector {
  private[jc] def emit[T](reactionSite: ReactionSite, mol: DM[T], value: T): Unit

  private[jc] def emit[T](reactionSite: ReactionSite, mol: DM[T], value: T, previousSessionId: ClusterSessionId): Unit = {
    emit(reactionSite, mol, value)
    lazy val error = new ExceptionEmittingDistributedMol(s"Distributed molecule $mol($value) cannot be emitted because session $previousSessionId is not current")
    // Check that `sessionId` after emission matches what was given before emission.
    sessionId() match {
      case Some(currentSessionId) if currentSessionId === previousSessionId ⇒
        // Session ID matches, can proceed to emitting.
        emit(reactionSite, mol, value)
        // Check that `sessionId` did not change.
        sessionId() match {
          case Some(currentSessionIdAfterEmission) if previousSessionId === currentSessionIdAfterEmission ⇒ // All clear.
          case _ ⇒ throw error
        }
      case _ ⇒ throw error
    }
  }

  def start(): Unit = {}

  def sessionId(): Option[ClusterSessionId]

  protected val reactionSites: TrieMap[String, ReactionSite] = new TrieMap()

  private[jc] def addReactionSite(reactionSite: ReactionSite): Unit = {
    reactionSites.getOrElseUpdate(reactionSite.sha1CodeWithNames, reactionSite)
    ()
  }

  protected def dcmPathForMol(reactionSite: ReactionSite, mol: MolEmitter): String = {
    s"DCM/${reactionSite.sha1CodeWithNames}/dm-${mol.siteIndex}/v"
  }
}

private[jc] final class ZkClusterConnector(clusterConfig: ClusterConfig) extends ClusterConnector {
  private[jc] def emit[T](reactionSite: ReactionSite, mol: DM[T], value: T): Unit = {
    val path = dcmPathForMol(reactionSite, mol)
    val molData = Cluster.serialize(value)
    val result = zk.create().creatingParentsIfNeeded()
//      .withProtection() // Curator protection may be necessary only for ephemeral ZK nodes.
      .withMode(CreateMode.PERSISTENT_SEQUENTIAL)
      .forPath(path, molData)
    println(s"got zk result: $result")
  }

  private val zk: CuratorFramework = {
    val builder = CuratorFrameworkFactory.builder
      .connectString(clusterConfig.url)
      .connectionTimeoutMs(clusterConfig.connectionTimeoutMs)
      .retryPolicy(new RetryNTimes(clusterConfig.numRetries, clusterConfig.retryIntervalMs))
    val builderWithAuth = if (clusterConfig.username.nonEmpty)
      builder.authorization(List[AuthInfo](new AuthInfo("digest", (clusterConfig.username + ":" + clusterConfig.password).getBytes("UTF-8"))).asJava)
    else builder
    builderWithAuth.build
  }

  override def start(): Unit = zk.start()

  /** Obtain current cluster session ID.
    *
    * @return Non-empty option if the current cluster connection is up, otherwise `None`.
    */
  def sessionId(): Option[ClusterSessionId] = {
    if (zk.getZookeeperClient.isConnected)
      Some(ClusterSessionId(zk.getZookeeperClient.getZooKeeper.getSessionId))
    else None
  }

  start()
}

/** A trivial implementation of ClusterConnector, automatically used when the ZooKeeper URL in `ClusterConfig` is empty.
  *
  * This implementation does not support clusters and holds all data in memory in the single JVM instance where it is created.
  * Reaction sites connected to a TestOnlyConnector will behave as if they are running on a single-node cluster.
  *
  * Use for unit testing purposes only.
  */
final class TestOnlyConnector extends ClusterConnector {
  private[jc] val allData: TrieMap[String, Array[Byte]] = new TrieMap()

  override private[jc] def emit[T](reactionSite: ReactionSite, mol: DM[T], value: T): Unit = {
    val path = dcmPathForMol(reactionSite, mol)
    val molData = Cluster.serialize(value)
    allData.update(path, molData)
  }

  private var sessionIdValue: ClusterSessionId = ClusterSessionId(0L)

  override def sessionId(): Option[ClusterSessionId] = Some(sessionIdValue)

  override def start(): Unit = {
    sessionIdValue = ClusterSessionId(scala.util.Random.nextLong())
  }

  /** This method may be called repeatedly, refreshing the session ID for testing purposes.
    * 
    */
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

  private def createConnector(clusterConfig: ClusterConfig): ClusterConnector = {
    if (clusterConfig.url.nonEmpty)
      new ZkClusterConnector(clusterConfig)
    else new TestOnlyConnector
  }

  private[jc] def addClusterConnector(reactionSite: ReactionSite)(clusterConfig: ClusterConfig): ClusterConfig = {
    val connector = connectors.getOrElseUpdate(clusterConfig, createConnector(clusterConfig))
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
