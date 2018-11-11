package io.chymyst.jc

import java.util
import java.util.concurrent.atomic.AtomicInteger

import com.twitter.chill.{IKryoRegistrar, KryoInstantiator, KryoPool, KryoSerializer, ScalaKryoInstantiator}
import io.chymyst.jc.Core.ClusterSessionId
import org.apache.curator.framework.{AuthInfo, CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.RetryNTimes
import org.apache.zookeeper.CreateMode
import Core.AnyOpsEquals
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.concurrent.TrieMap
import scala.reflect.ClassTag

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

  /** A dictionary of all distributed reaction sites that are activated and connected to this cluster.
    *
    */
  protected val reactionSites: TrieMap[String, ReactionSite] = new TrieMap()

  private[jc] def addReactionSite(reactionSite: ReactionSite): Unit = {
    reactionSites.getOrElseUpdate(reactionSite.sha1CodeWithNames, reactionSite)
    ()
  }

  protected def dcmPathForMol(reactionSite: ReactionSite, mol: MolEmitter): String = {
    s"DCM/${reactionSite.sha1CodeWithNames}/dm-${mol.siteIndex}"
  }
}

private[jc] final class ZkClusterConnector(clusterConfig: ClusterConfig) extends ClusterConnector {
  private[jc] def emit[T](reactionSite: ReactionSite, mol: DM[T], value: T): Unit = {
    val path = dcmPathForMol(reactionSite, mol) + "/v"
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
  private[jc] val allMoleculeData: TrieMap[String, Array[Byte]] = new TrieMap()
  private[jc] val molValueCounters: TrieMap[String, AtomicInteger] = new TrieMap()

  override private[jc] def emit[T](reactionSite: ReactionSite, mol: DM[T], value: T): Unit = {
    val path = dcmPathForMol(reactionSite, mol)
    val index = molValueCounters.getOrElseUpdate(path, new AtomicInteger()).getAndIncrement()
    val molData = Cluster.serialize(value)
    allMoleculeData.update(path + "/v-" + index.toString, molData)
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

  /** Serializing molecule emitters is possible only if they are bound,
    * because the serialized data consist of the molecule emitter's reaction site hash and site-wide index.
    * If the emitter is not bound, serializing or deserializing it will fail.
    */
  final class MolEmitterSerializer[ME <: MolEmitter] extends Serializer[ME] {
    override def write(kryo: Kryo, output: Output, molEmitter: ME): Unit = {
      if (molEmitter.isBound) {
        output.writeString(molEmitter.reactionSite.sha1CodeWithNames)
        output.writeInt(molEmitter.siteIndex, true)
        output.close() // TODO: figure out whether we need this
      } else throw new ExceptionEmittingDistributedMol(s"Data on a DM cannot be serialized because emitter $molEmitter is not bound")
    }

    override def read(kryo: Kryo, input: Input, tpe: Class[ME]): ME = {
      val reactionSiteHash = input.readString()
      val molEmitterOpt = for {
        reactionSite ← knownReactionSites.get(reactionSiteHash)
        siteIndex = input.readInt(true)
        molEmitter ← reactionSite.moleculeAtIndex.get(siteIndex)
      } yield molEmitter
      input.close() // TODO: figure out whether we need this
      molEmitterOpt.getOrElse(throw new ExceptionEmittingDistributedMol(s"Data on a DM cannot be deserialized because reaction site hash $reactionSiteHash does not correspond to an activated reaction site")).asInstanceOf[ME]
    }
  }

  // This Kryo `Pool` will register some custom serializers with Kryo.
  private val kryoPool = {
    val scalaRegistrar: IKryoRegistrar = KryoSerializer.registerAll
    val registrar = new IKryoRegistrar {
      override def apply(k: Kryo): Unit = {
        // Register my custom serializers.
        k.register(classOf[MolEmitter], new MolEmitterSerializer[MolEmitter])
        k.register(classOf[DM[_]], new MolEmitterSerializer[DM[_]])
        k.register(classOf[B[_, _]], new MolEmitterSerializer[B[_, _]])
        k.register(classOf[M[_]], new MolEmitterSerializer[M[_]])
        // Register all other Scala serializers supplied by `chill`.
        scalaRegistrar(k)
      }
    }
    val kryoInstantiator: KryoInstantiator = (new ScalaKryoInstantiator).withRegistrar(registrar)
    KryoPool.withByteArrayOutputStream(Runtime.getRuntime.availableProcessors * 2, kryoInstantiator)
  }

  /** Serialize data carried by a molecule.
    * If the data is a molecule emitter, the emitter must be bound to a reaction site.
    *
    * @param t Molecule value.
    * @tparam T Type of the molecule value.
    * @return Serialized byte array. Throws `ExceptionEmittingDistributedMol` if the data contains an unbound emitter.
    */
  def serialize[T](t: T): Array[Byte] = kryoPool.toBytesWithoutClass(t)

  /** Deserialize data carried by a molecule.
    * If the data is a molecule emitter, the emitter must be bound to a reaction site.
    * The deserialized emitter will be JVM-object-identical to the local emitter defined in the reaction site.
    *
    * @param bytes    Serialized molecule value.
    * @param classTag Class tag for the type of the molecule.
    * @tparam T Type of the molecule value.
    * @return Deserialized molecule value. Throws `ExceptionEmittingDistributedMol` if the data contains an emitter bound to an unknown reaction site.
    */
  def deserialize[T](bytes: Array[Byte])(implicit classTag: ClassTag[T]): T = kryoPool.fromBytes(bytes, classTag.runtimeClass.asInstanceOf[Class[T]])

  /** For each `ClusterConfig` value, a separate cluster connection is maintained by `ClusterConnector`
    * values in this dictionary. The values are created whenever a DRS is activated that uses a given cluster.
    * There is only one `ClusterConnector` for all DRSs using the same cluster.
    */
  private[jc] val connectors: TrieMap[ClusterConfig, ClusterConnector] = new TrieMap()

  /** A dictionary of all known distributed reaction sites that have been activated without errors.
    *
    */
  private[jc] val knownReactionSites: TrieMap[String, ReactionSite] = new TrieMap()

  private[jc] def addReactionSite(reactionSite: ReactionSite): Unit = {
    knownReactionSites.update(reactionSite.sha1CodeWithNames, reactionSite)
  }

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
