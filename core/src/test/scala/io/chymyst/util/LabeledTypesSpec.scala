package io.chymyst.util

import io.chymyst.test.LogSpec
import io.chymyst.util.LabeledTypes.{Newtype, Subtype}

class LabeledTypesSpec extends LogSpec {

  // Example 1: labeled String type

  val UserName = Newtype[String]
  // the new type UserName is not the same as String and not a subtype of String
  type UserName = UserName.T // optional boilerplate to define a shorter type alias

  val UserNameS = Subtype[String]
  // the new type UserNameS is a subtype of String
  type UserNameS = UserNameS.T // optional boilerplate to define a shorter type alias

  // Example 2: labeled Int type

  val UserId = Newtype[Int]
  // the new type UserId is not the same as Int and not a subtype of Int
  type UserId = UserId.T // optional boilerplate to define a shorter type alias

  val UserIdS = Subtype[Int]
  // the new type UserIdS is a subtype of Int
  type UserIdS = UserIdS.T // optional boilerplate to define a shorter type alias

  behavior of "labeled string"

  it should "convert a string and then unwrap using labeled subtype" in {
    val x = UserNameS("user name") // x is now of type UserNameS

    x shouldEqual "user name"

    def f(x: UserNameS): Int = x.length // automatic upcast of x to String
    def g(x: String): Int = x.length

    f(x) shouldEqual 9
    """ f("abc") """ shouldNot compile

    // automatic upcast
    g(x) shouldEqual 9
  }

  it should "distinguish different newtype instances with the same underlying type" in {
    val EnvName = Newtype[String]
    type EnvName = EnvName.T
    val TenantName = Newtype[String]
    type TenantName = TenantName.T

    def f(env: EnvName, tenant: TenantName): Unit = ()

    val env = EnvName("env1")
    val tenant = TenantName("tenant1")

    f(env, tenant) shouldEqual (())

    " f(tenant, env) " shouldNot compile
    """ f("tenant1", "env1") """ shouldNot compile
  }

  it should "distinguish different subtype instances with the same underlying type" in {
    val EnvName = Subtype[String]
    type EnvName = EnvName.T
    val TenantName = Subtype[String]
    type TenantName = TenantName.T

    def f(env: EnvName, tenant: TenantName): Unit = ()

    val env = EnvName("env1")
    val tenant = TenantName("tenant1")

    f(env, tenant) shouldEqual (())

    " f(tenant, env) " shouldNot compile
    """ f("tenant1", "env1") """ shouldNot compile
  }

  it should "convert a string and then unwrap using labeled newtype" in {
    val x = UserName("user name") // x is now of type UserName

    x shouldEqual "user name"

    def f(x: UserName): Int = UserName.get(x).length // no automatic upcast of x to String
    def g(x: String): Int = x.length

    g("user name") shouldEqual 9

    f(x) shouldEqual 9
    """ f("abc") """ shouldNot compile

    " g(x) " shouldNot compile // no automatic upcast of x to String
  }

  it should "convert a list of labeled newtypes" in {
    val rawList = List("a", "b", "c")
    val usernameList = UserName.maptype(rawList) // usernameList is now of type List[UserName]

    def f(x: List[UserName]): Int = x.length

    def g(x: List[String]): Int = x.length

    " f(rawList) " shouldNot compile
    f(usernameList) shouldEqual 3 // conversion from List[String] to List[UserName]

    " g(usernameList) " shouldNot compile // No automatic converstion back to List[String]

    // Manual conversion back to List[String]. This does not iterate over the list.
    val unwrappedList = UserName.unmaptype(usernameList)
    g(unwrappedList) shouldEqual 3
  }

  it should "convert a list of labeled subtypes" in {
    val rawList = List("a", "b", "c")
    val usernameList = UserNameS.maptype(rawList) // usernameList is now of type List[UserNameS]

    def f(x: List[UserNameS]): Int = x.length

    def g(x: List[String]): Int = x.length

    f(usernameList) shouldEqual 3
    g(usernameList) shouldEqual 3 // automatic conversion from List[UserNameS] back to List[String]

    " f(rawList) " shouldNot compile
    f(usernameList) shouldEqual 3 // OK
  }

  behavior of "labeled integer"

  it should "convert an integer and then unwrap using labeled subtype" in {
    val x = UserIdS(123) // x is now of type UserIdS

    def f(x: UserIdS): Int = x * 2

    f(x) shouldEqual 246
    """ f(123) """ shouldNot compile
  }

  it should "convert an integer and then unwrap using labeled type" in {
    val x = UserId(123) // x is now of type UserId

    def f(x: UserId): Int = UserId.get(x) * 2

    f(x) shouldEqual 246
    """ f(123) """ shouldNot compile
  }

  it should "convert a list of labeled integers" in {
    val rawList = List(1, 2, 3)
    val useridList = UserId.maptype(rawList) // useridList is now of type List[UserId]

    def f(x: List[UserId]): Int = x.length

    def g(x: List[Int]): Int = x.length

    f(useridList) shouldEqual 3
    " g(useridList) " shouldNot compile

    val unwrappedList = UserId.unmaptype(useridList) // convert back to List[Int]
    g(unwrappedList) shouldEqual 3
  }

}
