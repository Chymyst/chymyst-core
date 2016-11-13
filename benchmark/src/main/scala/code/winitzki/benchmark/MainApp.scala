package code.winitzki.benchmark

import code.winitzki.benchmark.Benchmarks1._
import code.winitzki.benchmark.Benchmarks4._
import code.winitzki.benchmark.Benchmarks7._

import code.winitzki.jc.JoinRun.{defaultJoinPool, defaultReactionPool}

object MainApp extends App {
  val version = "0.0.5"

  def run3times(task: => Long): Long = {
    val prime1 = {
      task
    }
    val prime2 = {
      task
    }
    val result = {
      task
    }
    //    println(s"timing with priming: prime1 = $prime1, prime2 = $prime2, result = $result")
    (result + prime2 + 1) / 2
  }

  val n = 50000

  val threads = 8

  println(s"Benchmark parameters n = $n, threads = $threads")

  Seq(
    benchmark1 _ -> true, // simple counter
    benchmark3 _ -> true, // simple counter encapsulated in a closure - it's faster for some reason
    benchmark2 _ -> true, // simple counter using Jiansen's Join.scala
    benchmark2a _ -> true, // simple counter encapsulated in a closure, using Jiansen's Join.scala
    benchmark4_100 _ -> true, // 100 different reactions chained together
    benchmark5_6 _ -> false, // same but with only 6 reactions, using Jiansen's Join.scala
    benchmark5_100 _ -> false, // (this deadlocks) 50 different reactions chained together, using Jiansen's Join.scala

    benchmark7 _ -> true, // many concurrent counters
    benchmark8 _ -> false, // (this deadlocks) many concurrent counters, using Jiansen's Join.scala

    benchmark1 _ -> false // write more benchmarks and append them here
  )
      .zipWithIndex.foreach {
    case ((benchmark, flag), i) => if(flag) println(s"Benchmark ${i+1} took ${run3times { benchmark(n,threads) }} ms") else println(s"Benchmark ${i+1} skipped")
  }

  defaultJoinPool.shutdownNow()
  defaultReactionPool.shutdownNow()

}
