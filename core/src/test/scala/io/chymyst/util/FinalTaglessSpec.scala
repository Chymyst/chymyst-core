package io.chymyst.util

import io.chymyst.test.{Common, LogSpec}

class FinalTaglessSpec extends LogSpec {

  behavior of "final tagless Option"

  def benchmark(message: String, x: => Any): Unit = {
    val total = 50000

    val result = Common.elapsedTimesNs(x, total)
    val (mean0, std0) = Common.meanAndStdev(result.slice(50, 150))
    val (mean1, std1) = Common.meanAndStdev(result.takeRight(200))
    println(s"$message (total=$total) takes before warmup ${Common.formatNanosToMicrosWithMeanStd(mean0, std0)}, after warmup ${Common.formatNanosToMicrosWithMeanStd(mean1, std1)}")
    Common.showFullStatistics(message, result)
  }

  it should "benchmark creating an Option[Int] value" in {
    benchmark("standard Option Some(3).getOrElse(2)", Some(3).getOrElse(2))
  }

  // see https://oleksandrmanzyuk.wordpress.com/2014/06/18/from-object-algebras-to-finally-tagless-interpreters-2/
  it should "create some() and none() values secundo Oleksandr Manzyuk" in {

    import FinalTagless._

    // testing with explicit ftIsEmpty
    val some3: FTOptionIsEmpty = some(3)(ie)
    val some3IsEmpty: Boolean = some3.isEmpty
    some3IsEmpty shouldEqual false
    val noneInt: FTOptionIsEmpty = none(ie)
    val noneIntIsEmpty: Boolean = noneInt.isEmpty
    noneIntIsEmpty shouldEqual true
    val some3GOE = some(3)(goe) // [Int] is inferred here
    val noneGOE = none(goe[Int]) // necessary to say goe[Int] here
    val res1 = some3GOE.getOrElse(100) // this is 3
    res1 shouldEqual 3
    val res2 = noneGOE.getOrElse(100) // this is 100
    res2 shouldEqual 100
    benchmark("FTOption some(3).getOrElse(2)", some(3)(goe).getOrElse(2))
  }

  it should "use less boilerplate with FT2" in {
    import FT2._

    def some3[X] = some[Int, X](3)

    some3[Boolean].isEmpty shouldEqual false
    benchmark("FT2Option some(3).getOrElse(2)", some[Int, Int ⇒ Int](3).getOrElse(2))
  }

  it should "use less boilerplate with FT3" in {
    import FT3._

    def some3[X] = some[Int, X](3)

    some3[Boolean].isEmpty shouldEqual false
    benchmark("FT3Option some(3).getOrElse(2)", some[Int, Int ⇒ Int](3).getOrElse(2))
  }

  it should "use less boilerplate with FT4" in {
    import FT4._

    val some3 = some(3) // some3 has type FT4Option[Int] with type inference.
    some3.isEmpty shouldEqual false
    benchmark("FT4Option some(3).getOrElse(2)", some(3).getOrElse(2))
    val none3 = none[Int] // otherwise none3 has type FT4Option[Nothing] and that fails...
    none3.isEmpty shouldEqual true
    some3.getOrElse(2) shouldEqual 3
    none3.getOrElse(2) shouldEqual 2
  }

  it should "use less boilerplate with FT5" in {
    import FT5._

    val some3 = some(3) // some3 has type FT4Option[Int] with type inference.
    some3.isEmpty shouldEqual false
    benchmark("FT5Option some(3).getOrElse(2)", some(3).getOrElse(2))
    val none3 = none[Int] // otherwise none3 has type FT4Option[Nothing] and that fails...
    none3.isEmpty shouldEqual true
    some3.getOrElse(2) shouldEqual 3
    none3.getOrElse(2) shouldEqual 2
  }

}
