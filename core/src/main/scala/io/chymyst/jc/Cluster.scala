package io.chymyst.jc

import java.util

import org.apache.curator.framework.{AuthInfo, CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.RetryNTimes

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.mutable

final case class ClusterConfig(
  url: String,
  username: String = "",
  password: String = "",
  connectionTimeoutMs: Int = 500,
  numRetries: Int = 5,
  retryIntervalMs: Int = 1000
) {
  val clientId: String = Core.getSha1(this.toString + Cluster.guid, Core.getMessageDigest)
}

final class ClusterConnector(clusterConfig: ClusterConfig) {
  private val zk: CuratorFramework = CuratorFrameworkFactory.builder
    .connectString(clusterConfig.url)
    .connectionTimeoutMs(clusterConfig.connectionTimeoutMs)
    .retryPolicy(new RetryNTimes(clusterConfig.numRetries, clusterConfig.retryIntervalMs))
    .authorization(List[AuthInfo](new AuthInfo("digest", (clusterConfig.username + ":" + clusterConfig.password).getBytes("UTF-8"))).asJava)
    .build

  def start(): Unit = zk.start()
}

object Cluster {
  val guid: String = java.util.UUID.randomUUID().toString
  val connectors: mutable.Map[String, ClusterConnector] = mutable.Map()
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

  override def size: Int = ???

  override def add(v: T): Unit = ???

  override def remove(v: T): Boolean = ???
}
