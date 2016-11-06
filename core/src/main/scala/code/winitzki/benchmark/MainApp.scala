package code.winitzki.benchmark

import code.winitzki.benchmark.Benchmarks1._
import code.winitzki.benchmark.Benchmarks4._
import code.winitzki.benchmark.Benchmarks7._

import code.winitzki.jc.JoinRun.{defaultJoinPool, defaultProcessPool}

object MainApp extends App {
  val version = "0.0.1"

  val n = 1000

  val threads = 8

  println(s"Benchmark parameters n = $n, threads = $threads")

  Seq(
    benchmark1 _ -> true,
    benchmark3 _ -> true,
    benchmark2 _ -> true,
    benchmark2a _ -> true,
    benchmark4_100 _ -> true,
    benchmark5_6 _ -> false,
    benchmark5_100 _ -> false, // this deadlocks

    benchmark7 _ -> true,
    benchmark8 _ -> false, // this deadlocks

    benchmark1 _ -> false // write more benchmarks and append them here
  )
      .zipWithIndex.foreach {
    case ((benchmark, flag), i) => if(flag) println(s"Benchmark ${i+1} took ${benchmark(n,threads)} ms") else println(s"Benchmark ${i+1} skipped")
  }

  defaultJoinPool.shutdownNow()
  defaultProcessPool.shutdownNow()

}
