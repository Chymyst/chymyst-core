package sample

object Syntax {

  object ! {
    def unapply(x: Any): Option[(Any, Any)] = ??? // This method should remain unimplemented.
  }

  object & {
    def unapply(x: Any): Option[(Any, Any)] = ???
  }

  // This is purely for syntax. This provides "a ! x & b ! y" in expressions (rather than in patterns).
  // These methods should all remain unimplemented.

  implicit def spawn(x: Any) = new Object {
    def !(y: Any): Any = ???
    def !!(y: Any): Any = ???

    // This method should remain unimplemented, so that fully constructed molecules are not values.
    def &(y: Any): Any = ??? // This method should remain unimplemented, so that fully constructed molecules are not values.
  }

  trait JoinChan[T] {
    def !(x: T): Unit = ()
  }


}
//  case class C[X,Y,A](f: (X, Y) => A) // if arguments are parameterized by types, type inference fails and compilation also fails.
//
//  case class B[X](f: X => Unit)
//  Macros.hello
//  val s = Macros.desugar(List(6, 4, 5).sorted)
//  println(s)

// Can we desugar something that is not immediately well-typed? No.

//  val t = Macros.desugar(
//    C[Int,String,Int]{ // compilation fails without explicit type parameters here.
//      case (x, y) => x
//    }
//  )
//  val t = Macros.desugar(
//    B[(Int,String)]{ // compilation fails without explicit type parameters here.
//      case (x, y) => ()
//    }
//  )
//
//  val t1 = Macros.desugar(
//    {
//      (a: Int, b: String) =>
//    }
//  )
//
//  object :& {
//    def unapply(x: Any): Option[(Any, Any)] = ???
//  }
//
//  object :% {
//    def unapply(x: Any): Option[(Any, Any)] = ???
//  }
//
//  case class Join[T](f: Any => T) // syntax helper
//
//  val t2 = Macros.desugar(
//    Join {
//      // we don't need any parameter types (it's all "Any")
//      case (x, y) => x
//      case x :& y => x
//      case a :% x :& b :% y => (x,y) // wrong precedence!
//    }
//  )

//  object ! {
//    def unapply(x: Any): Option[(Any, Any)] = ??? // This method should remain unimplemented, so that users can't write molecules in case expressions.
////    def apply(x: Any, y: Any): Any = ??? // Useless - this does not work in expressions such as "a ! x". We need an implicit conversion.
//  }
//  // This is purely for syntax. This provides "a ! x & b ! y" in expressions (rather than in patterns).
//
//  implicit def spawn(x: Any) = new Object {
//    def !(y: Any): Any = ??? // This method should remain unimplemented, so that fully constructed molecules are not values.
//    def &(y: Any): Any = ??? // This method should remain unimplemented, so that fully constructed molecules are not values.
//  }
//
//  object & {
//    def unapply(x: Any): Option[(Any, Any)] = ??? // We do not need to implement this method.
//    // This will prevent the user from writing molecules in a case expression.
//  }
//
//  val t2 = Macros.desugarF( // This macro is parameterized by the return type of the anonymous partial function.
//    { // Here, we don't need parameter types either (it's all "Any").
//      case (x, y) => "abc" // compiles! "x" and "y" have type "Any".
//      case a!x & b!y => x
//      case c!x & d!y => y // same case pattern as above, with different pattern variables and molecule names.
//      // The clause is not deleted at macro invocation! Good.
//      case a!x & b!y & c!z => a ! x & b ! y // correct implicit precedence; linearity of pattern is enforced
//
//    }
//  )
//
//  lazy val fully_constructed_molecule = 1 ! 2 // This has type Any, compiles, but crashes with "not implemented" at runtime
//
//  // However, a macro can parse it. So we could implement "spawn" as a macro.
//
//  println(s"Fully constructed molecule: ${Macros.desugar(1 ! 2 & 3 ! 4)}")

// But, until now, types are not inferred at all. We would like to do this: a(x) & b(y) => a(x+y).
// But it seems that we need to annotate types: a!(x:Int) and b!(y:Int) => a(x+y)
//
//  object & {
//    def unapply(x: Any): Option[(Any,Any)] = ???
//  }
//  object ! { // type inference will not work here. No use trying to make "unapply[A]" guess the type of A.
//    def unapply(x: Any): Option[(JC[Any], Any)] = ???
//  }
//
//  trait JC[A] {
//    def apply(x: A) = ???
//    def !(x:A) : Unit = ???
//  }

// Implicit conversion from JC[A] to an object that has methods. Not sure why we want to use this. Let's try using type inference.
//  implicit def spawn1[A](x: JC[A]) = new Object {
//    def !:(z: Any): JC[Nothing] = ??? // This method should remain unimplemented, so that fully constructed molecules are not values.
//    def &:(z: JC[Nothing]) = ??? // This method should remain unimplemented, so that fully constructed molecules are not values.
//  }

// this needs to be a compile-time error
//  val (a, v) = 1

//  val (c, i, g, s) =

//    val debugString = Macros.joindefT[String] {
//    case c ! _ & s ! (y: Int) => c ! y
//    case c ! (x: Int) & i ! _ => c ! (x+1)
//    case c ! (x: Int) & g => c ! x & g !! x
//    case d ! _ => ()
//    case _ => c ! 0
//  }
//
//
//  println(s"Desugared join definitions:\n$debugString")

//  object TestMolecule extends JC[Int]
//  val a = TestMolecule
//  println(s"Desugar constructed molecule: ${Macros.desugar(a ! 2, a(2))}")

// can we use macros to define a local variable with a name mentioned in macro body?



//  Macros.mkval{ xyz: Int => 10 }

//  println(s"Variable xyz defined as $xyz")

//  println(s"Desugar macro body: ${Macros.desugar { x: Int => 10 }}")

