package code.chymyst.jc

import Macros.{m, b, go}
import org.scalatest.{FlatSpec, Matchers}

class GuardsSpec extends FlatSpec with Matchers {

  behavior of "guard conditions"

  it should "correctly recognize a guard condition with no variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) if 1 > n => }

    (result.info.hasGuard match {
      case GuardPresent(List(), Some(staticGuard), List()) => // `n` should not be among the guard variables
        staticGuard() shouldEqual false
        true
      case _ => false
    }) shouldEqual true

    result.info.inputs should matchPattern { case List(InputMoleculeInfo(`a`, SimpleVar('x, None), _)) => }
    result.info.toString shouldEqual "a(x) if(?) => "
  }

  it should "correctly recognize a guard condition with captured variables" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(xyz) if xyz > n => }

    result.info.hasGuard should matchPattern { case GuardPresent(List(List('xyz)), None, List()) => // `n` should not be among the guard variables
    }
    result.info.toString shouldEqual "a(xyz if ?) => "
    (result.info.inputs match {
      case List(InputMoleculeInfo(`a`, SimpleVar('xyz, Some(cond)), _)) =>
        cond.isDefinedAt(n + 1) shouldEqual true
        cond.isDefinedAt(n) shouldEqual false
        true
      case _ => false
    }) shouldEqual true
  }

  it should "correctly split a guard condition with several clauses" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if 1 > n && x > n && y > n => }

    (result.info.hasGuard match {
      case GuardPresent(List(List('x), List('y)), Some(staticGuard), List()) =>
        staticGuard() shouldEqual false
        true
      case _ => false
    }) shouldEqual true
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) if(?) => "
  }

  it should "correctly handle a guard condition with ||" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if (1 > n || x > n) && y > n => }

    result.info.hasGuard should matchPattern { case GuardPresent(List(List('x), List('y)), None, List()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) => "
  }

  it should "perform Boolean transformation on a guard condition to eliminate cross dependency" in {
    val a = m[Int]

    val n = 10

    val result = go { case a(x) + a(y) if x > n && y > n || 1 > n => }

    result.info.hasGuard should matchPattern { case GuardPresent(List(List('x), List('y)), None, List()) => }
    result.info.toString shouldEqual "a(x if ?) + a(y if ?) => "
  }

  it should "correctly handle a guard condition with nontrivial matcher" in {
    val a = m[(Int, Int, Int)]

    val result = go { case a((x: Int, y: Int, z: Int)) if x > y => }

    result.info.hasGuard shouldEqual GuardPresent(List(List('x, 'y)), None, List())
    result.info.toString should fullyMatch regex "a\\(<[A-F0-9]{4}\\.\\.\\.>\\) => "

  }
  /* cross guard causes untypecheck-related trouble with Tuple2
        it should "handle a guard condition with cross dependency that cannot be eliminated by Boolean transformations" in {
          val a = m[Int]

          val n = 10

          val result = go { case a(x) + a(y) if x > n + y => }

          (result.info.hasGuard match {
            case GuardPresent(List(List('x, 'y)), None, List((List('x, 'y), guard_x_y))) =>
              true
            case _ => false
          }) shouldEqual true

        }

           it should "correctly split a guard condition when some clauses contain no pattern variables" in {
             val a = m[Int]
             val bb = m[(Int, Option[Int])]
             val f = b[Unit, Unit]

             val k = 5
             val n = 10

             val result = go {
               case a(p) + a(y) + a(1) + bb((1, z:Option[Int])) + bb((t:Int, Some(qwerty:Int))) + f(_, r) if y > 0 && n == 10 && qwerty == n && t > p && k < n => r()
             }
             (result.info.hasGuard match {
               case GuardPresent(List(List('y), List('qwerty), List('t, 'p)), Some(staticGuard), List((List('t,'p), guard_t_p))) =>
                 staticGuard() shouldEqual true
                 true
               case _ => false
             }) shouldEqual true
           }

           it should "correctly flatten a guard condition with complicated nested clauses" in {
             val a = m[Int]
             val bb = m[(Int, Option[Int])]

             val result = go {
               case a(p) + a(y) + a(1) + bb((1, z:Option[Int])) + bb((t:Int, Some(q:Int))) if p == 3 && ((t == q && y > 0) && q > 0) && (t == p && y == q) =>
             }
             (result.info.hasGuard match {
               case GuardPresent(List(List('p), List('t, 'q), List('y), List('q), List('t, 'p), List('y, 'q)), None, List((List('t, 'p), guard_t_p), (List('y, 'q), guard_y_q))) =>

                 true
               case _ => false
             }) shouldEqual true
           }
      */
}
