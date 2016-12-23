package code.chymyst.jc


import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.time.{Millis, Span}

class ReactionSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  val warmupTimeMs = 50L

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "matcherIsSimilarToOutput"

  it should "detect input matcher similar or weaker wrt output matcher when input pattern is unknown" in {
    val a = new E("a")
    val inputMoleculeInfo = InputMoleculeInfo(a, UnknownInputPattern, "some_sha")
    val outputMoleculeInfo = OutputMoleculeInfo(a, OtherOutputPattern)
    inputMoleculeInfo.matcherIsSimilarToOutput(outputMoleculeInfo) shouldEqual Some(true)
    inputMoleculeInfo.matcherIsWeakerThanOutput(outputMoleculeInfo) shouldEqual Some(false)
    inputMoleculeInfo.matcherIsWeakerThan(inputMoleculeInfo) shouldEqual Some(false)
  }

}