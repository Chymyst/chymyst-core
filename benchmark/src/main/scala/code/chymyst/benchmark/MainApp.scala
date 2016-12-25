package code.chymyst.benchmark

import Benchmarks1._
import Benchmarks4._
import Benchmarks7._
import Benchmarks9._
import code.chymyst.jc._

object MainAppConfig {

  val n = 50000

  val threads = 8
}

object MainApp extends App {
  import MainAppConfig._
  val version = "0.0.5"

  def run3times(task: => Long): Long = {
    task // just priming, no measurement
    val result1 = {
      task
    }
    val result2 = {
      task
    }
    (result1 + result2 + 1) / 2
  }

  println(s"Benchmark parameters: count to $n, threads = $threads")

  Seq[(String, (Int, Pool) => Long)](
  // List the benchmarks that we should run.

    s"count using JoinRun" -> benchmark1 _,
    s"count using Jiansen's Join.scala" -> benchmark2 _,
    "counter in a closure, using JoinRun" -> benchmark3 _,
    "counter in a closure, using Jiansen's Join.scala" -> benchmark2a _,
    s"${Benchmarks4.differentReactions} different reactions chained together, 2000 times" -> benchmark4_100 _,

//  "(this deadlocks) 50 different reactions chained together, using Jiansen's Join.scala" -> benchmark5_100 _,
//  "(StackOverflowError) same but with only 6 reactions, using Jiansen's Join.scala" -> benchmark5_6 _,

    s"${Benchmarks7.numberOfCounters} concurrent counters with non-blocking access" -> benchmark7 _,

//  "(this deadlocks) many concurrent counters with non-blocking access, using Jiansen's Join.scala" -> benchmark8 _,

    s"${Benchmarks9.numberOfCounters} concurrent counters with blocking access" -> benchmark9_1 _,
    s"${Benchmarks9.numberOfCounters} concurrent counters with blocking access, using Jiansen's Join.scala" -> benchmark9_1_Jiansen _,

    s"${Benchmarks9.pingPongCalls} blocked threads with ping-pong calls" -> benchmark9_2 _,

    s"count to ${counterMultiplier*n} using blocking access with checking reply status" -> benchmark10 _
  ).zipWithIndex.foreach {
    case ((message, benchmark), i) => println(s"Benchmark ${i+1} took ${run3times {
      val tp = new FixedPool(threads)
      val result = benchmark(n, tp)
      tp.shutdownNow()
      result
    }} ms ($message)")
  }

  defaultSitePool.shutdownNow()
  defaultReactionPool.shutdownNow()

}
