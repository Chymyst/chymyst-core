package io.chymyst.util

import io.chymyst.test.LogSpec

class FinalTaglessSpec extends LogSpec {

  behavior of "final tagless Option secundo Oleksandr Manzyuk"

  it should "create some() and none() values and run isEmpty() and getOrElse() on them" in {

    import FinalTagless.{FTOptionIsEmpty, some, ie, none, goe}

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
  }

}
