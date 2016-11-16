# Using `JoinRun` for concurrent programming

`JoinRun` is a library for functional concurrent programming.
It follows the paradigm of [Join Calculus](https://en.wikipedia.org/wiki/Join-calculus) and is implemented as an embedded DSL (domain-specific language) in Scala.

Source code and documentation for `JoinRun` is available at [https://github.com/winitzki/joinrun-scala](https://github.com/winitzki/joinrun-scala).

To understand this tutorial, the reader should have some familiarity with the `Scala` programming language.

Although this tutorial focuses on using `JoinRun` in Scala, one can easily implement the core Join Calculus as a library on top of any programming language.
The main concepts and techniques of Join Calculus programming paradigm are independent of the base programming language.

# The chemical machine

It is easiest to understand Join Calculus by using the metaphor of the “chemical machine”.

Imagine that we have a large tank of water where many different chemical substances are dissolved.
Different chemical reactions are possible in this “chemical soup”.
Reactions could start at the same time (i.e. concurrently) in different regions of the soup, as various molecules come together.

Since we are going to simulate this in a computer, the “chemistry” here is completely imaginary and has nothing to do with real-life chemistry.
We can define molecules of any sort, and we can postulate arbitrary reactions between them.
For instance, we can postulate that there exist three sorts of molecules called `a`, `b`, `c`, and that they can react as follows:

`a + b ⇒ a`

`a + c ⇒` <_nothing_>

Of course, real-life chemistry would not allow two molecules to disappear without producing any other molecules.
But our chemistry is imaginary, and so the programmer is free to postulate arbitrary “chemical laws.”

To develop the chemical analogy further, we allow the “chemical soup” to contain many copies of each molecule.
For example, the soup can contain five hundred copies of `a` and three hundred copies of `b`, etc.
We also assume that we can inject any molecule into the soup at any time.

It is not difficult to implement a simulator for the “chemical soup” behavior as just described.
Having specified the list of “chemical laws", we start the simulation waiting for some molecules to be injected into the soup.
Once molecules are injected, we check whether some reactions can start.

In a reaction such as `a + b + c ⇒ d + e` the **input molecules** are  `a`, `b`, and `c`, and the **output molecules** are `d` and `e`.
A reaction can have one or more input molecules, and zero or more output molecules.

Once a reaction starts, the input molecules instantaneously disappear from the soup (they are “consumed” by the reaction), and then the output molecules are injected into the soup.

The simulator can start many reactions concurrently whenever their input molecules are available.

## Using chemistry for concurrent computation

The runtime engine of `JoinRun` implements such a “chemical machine simulator”.
Now, rather than merely watch as reactions happen, we are going to use this engine for practical computations.

The basic idea is that we are going to specify some values and expressions to be computed whenever reactions occur:

- Each molecule will carry a value. Molecule values are strongly typed: a molecule of a given sort (such as `b`) can only carry values of some fixed specified type.
- Each reaction will carry a function (the **reaction body**) that computes some new values and puts these values on the output molecules.
The input arguments of the reaction body are going to be the values carried by the input molecules of the reaction.

In `JoinRun`, we use the syntax like `a(123)` to denote molecule values.
In this example, `a(123)`, the molecule `a` carries an integer value.

A typical Join Calculus reaction (equipped with molecule values and a reaction body) may look like this:

```scala
a(x) + b(y) ⇒ val z = computeZ(x,y); a(z)
```

In this example, the reaction's input molecules are `a(x)` and `b(y)`; that is, the input molecules have chemical designations `a` and `b`, and carry values `x` and `y` respectively.
The reaction body is a function that receives `x` and `y` as input arguments.
It computes a value `z` out of `x` and `y`, and puts that `z` onto the output molecule `a`.

Whenever input molecules are available in the soup, the runtime engine will start a reaction that consumes these input molecules.
If many copies of input molecules are available, the runtime engine will start several reactions concurrently.

Note that each reaction depends only on the values of its _input_ molecules.
So it is completely safe to execute concurrently several instances of the same reaction, starting from different sets of input molecules.
This is the way Join Calculus uses the “chemical simulator” to execute concurrent computations.

The reaction body can be a pure function since it will receive its arguments on the input molecules.
In this way, Join Calculus enables pure functional concurrent programming.

## The syntax of `JoinRun`

So far, we have been using some chemistry-resembling pseudocode to illustrate the structure of “chemical reactions”.
The actual syntax of `JoinRun` is only a little more verbose than that:

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

// declare the molecule types
val a = jA[Int] // a(...) will be a molecule with an integer value
val b = jA[Int] // ditto for b(...)

// declare the available reaction(s)
join(
  run { case a(x) + b(y) =>
    val z = computeZ(x,y)
    a(z)
  }
)
```

The helper functions `jA`, `join`, and `run` are defined in the `JoinRun` library.

## Example 0: concurrent counter

We would like to maintain a counter with an integer value, which can be incremented or decremented by non-blocking, concurrently running operations.
(For example, we would like to be able to increment and decrement the counter from different processes running at the same time.)

To implement this using Join Calculus, we begin by deciding which molecules we will need to define.
It is clear that we will need a molecule that carries the integer value of the counter:

```scala
val counter = jA[Int]
```

The increment and decrement operations must be represented by other molecules.
Let us call them `incr` and `decr`.
These molecules do not need to carry values, so we will use `Unit` as their value type. 

```scala
val incr = jA[Unit]
val decr = jA[Unit]
```

The reactions must be such that the counter is incremented when we inject the `incr` molecule, and decremented when we inject the `decr` molecule.

So, it looks like we will need two reactions:

```scala
join(
  run { case counter(n) + incr(_) => counter(n+1) },
  run { case counter(n) + decr(_) => counter(n-1) }
)
```

The new value of the counter (either `n+1` or `n-1`) will be carried by the new counter molecule that we inject in these reactions.
The previous counter molecule (with its old value `n`) will be consumed by the reactions.
The `incr` and `decr` molecules will be likewise consumed.

Remarks:
- The two reactions need to be defined together because both reactions use the same input molecule `counter`.
This construction (defining several reactions together) is called a **join definition**.
- In Join Calculus, all reactions that share some _input_ molecule must be defined in the same join definition.
Reactions that share no input molecules can (and should) be defined in separate join definitions.

After defining the molecules and their reactions, we can start injecting new molecules into the soup:

```scala
counter(100)
incr() // now the soup has counter(101)
decr() // now the soup again has counter(100)
decr()+decr() // now the soup has counter(98)
```

The alternative syntax `decr()+decr()` means injecting two molecules at once.
In the current version of `JoinRun`, this is equivalent to injecting the molecules one by one.

It could happen that we are injecting `incr()` and `decr()` molecules too quickly for reactions to start.
This will result in many instances of `incr()` or `decr()` molecules being present in the soup, waiting to be consumed.
This is not a problem if only one instance of the `counter` molecule is present in the soup.
In that case, the single `counter` molecule will react with either an `incr` or a `decr` molecule, starting one reaction at a time.
Thus, we cannot have any race conditions with the counter (In ordinary, non-Join Calculus code, a race condition could occur due to updating the counter value simultaneously from different processes).

## Tracing the output

The code shown above will not print any output, so it is perhaps instructive to put some print statements into the reactions.

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

// declare the molecule types
val counter = jA[Int]
val incr = jA[Unit]
val decr = jA[Unit]

// helper function to be used in reactions
def printAndInject(x: Int) = {
  println(s"new value is $x")
  counter(x)
}

// declare the available reaction(s)
join(
  run { case counter(n) + incr(_) => printAndInject(n+1) },
  run { case counter(n) + decr(_) => printAndInject(n-1) }
)

counter(100)
incr() // prints “new value is 101"
decr() // prints “new value is 100"
decr()+decr() // prints “new value is 99” and then “new value is 98"
```

## Debugging

`JoinRun` has a simple debugging facility.

For a given molecule, there must exist a single join definition (JD) to which this molecule “belongs” - that is, the JD where this molecule is consumed as input molecule by some reactions.

Sometimes, reactions are specified incorrectly.
For debugging purposes, we can use the `logSoup` method on the molecule injector.
This method will return a string showing which molecules are currently present in the soup owned by that JD (i.e. all molecules that are inputs in it) as well as see the input molecules used by reactions in that JD.

After executing the code from the example above, here is how we could use this debugging facility:

```scala
counter.logSoup // returns “Join{counter + incr => ...; counter + decr => ...}
// Molecules: counter(98)"
```

Additionally, the user can set logging level on the JD.
To do this, call `setLogLevel` on any molecule injector that belongs to that JD.

```scala
counter.setLogLevel(2)
```

After this, verbosity level 2 is set on all reactions involving the JD to which `counter` belongs.
This might result in a large printout.
So this facility should be used only for debugging.

## Common errors

### Injecting molecules without defined reactions

It is an error to inject a molecule that is not yet defined as input molecule in any JD.

```scala
val x = jA[Int]

x(100) // java.lang.Exception: Molecule x does not belong to any join definition
```

The same error will occur if such injection is attempted inside a reaction body, or if we try to debug the JD using `logSoup`.

The correct way of using `JoinRun` is first to define molecules, then to create a JD where these molecules are used as inputs for reactions, and only then to start injecting these molecules.

### Redefining input molecules

It is also an error to write a reaction whose input molecule is already used as input in another join definition.

```scala
val x = jA[Int]
val a = jA[Unit]
val b = jA[Unit]

join( run { case x(n) + a(_) => println(s"have x($n) + a") } ) // OK

join( run { case x(n) + b(_) => println(s"have x($n) + b") } )
// java.lang.Exception: Molecule x cannot be used as input since it was already used in Join{a + x => ...}
```

Correct use of Join Calculus requires that we put these two reactions into a single join definition:
 
```scala
val x = jA[Int]
val a = jA[Unit]
val b = jA[Unit]

join(
  run { case x(n) + a(_) => println(s"have x($n) + a") },
  run { case x(n) + b(_) => println(s"have x($n) + b") }
) // OK
``` 

More generally, all reactions that share any input molecules must be defined together in a single JD.
However, reactions that use a certain molecule only as an output molecule can be written in another JD.
Here is an example where we define one JD that computes a result and sends it on a molecule to another JD that prints that result:

```scala
val show = jA[Int]

// JD where “show” is an input molecule
join( run { case show(x) => println(s"") })

val start = jA[Unit]

// JD where “show” is an output molecule
join(
  run { case start(_) => val res = compute(...); show(res) }
)
``` 

### Nonlinear patterns

Join Calculus also requires that all input molecules for a reaction should be of different sorts.
It is not allowed to have a reaction with repeated input molecules, e.g. of the form `a + a => ...` where the molecule of sort `a` is repeated.
An input molecule list with a repeated molecule is called a “nonlinear pattern”.

```scala
val x = jA[Int]

join(run { case x(n1) + x(n2) =>  })
// java.lang.Exception: Nonlinear pattern: x used twice
``` 

Sometimes it appears that repeating input molecules is the most natural way of expressing the desired behavior of some concurrent programs.
However, I believe it is always possible to introduce some new auxiliary molecules and to rewrite the “chemistry laws” so that input molecules are not repeated while the resulting computations give the same results.
This limitation could be lifted in a later version of `JoinRun` if it proves useful to do so.

## Order of reactions

When there are several different reactions that can be consume the available molecules, the runtime engine will choose the reaction at random.
In the current implementation of `JoinRun`, the runtime will reshuffle and randomize reactions, so that every reaction has an equal chance of starting.

Similarly, when there are several instances of the same molecule that can be consumed as input by a reaction, the runtime engine will make a choice of the molecule to be consumed.
Currently, `JoinRun` will _not_ randomize the input molecules but make an implementation-dependent choice.
A truly random selection of input molecules may be implemented in the future.

It is not possible to assign priorities to reactions or to molecules.
The order of reactions in a join definition is ignored, and the order of molecules in the input list is also ignored.
The debugging facility will print the molecule names in alphabetical order, and reactions will be printed in an unspecified order.

The result of this is that the order in which reactions will start is non-deterministic and unknown.
This is the original semantics of Join Calculus.
If the priority of certain reactions is important for a particular application, it is the programmer's task to design the “chemical laws” in such a way that those reactions start in the desired order.

## Summary so far

The “chemical machine” requires for its description:
- a list of defined molecules, together with their types;
- a list of “chemical reactions” involving these molecules as inputs, together with reaction bodies.

The user can define reactions in one or more join definitions.
One join definition encompasses all reactions that have some _input_ molecules in common.

After defining the molecules and specifying the reactions, the user can start injecting molecules into the soup.

In this way, a complicated system of interacting concurrent processes can be specified through a particular set of “chemical laws” and reaction bodies.

# Example 1: declarative solution of “dining philosophers"

The ["dining philosophers problem"](https://en.wikipedia.org/wiki/Dining_philosophers_problem) is to run a simulation of five philosophers who take turns eating and thinking.
Each philosopher needs two forks to start eating, and every pair of neighbor philosophers shares a fork.

The simplest solution of the “dining philosophers” problem is achieved using a molecule for each fork and two molecules per philosopher: one representing a thinking philosopher and the other representing a hungry philosopher.

A “thinking philosopher” molecule causes a reaction in which the process is paused for a random time and then the “hungry philosopher” molecule is injected.
A “hungry philosopher” molecule reacts with two neighbor “fork” molecules: the process is paused for a random time and then the “thinking philosopher” molecule is injected, together with the two “fork” molecules.

The complete code is shown here:

```scala
    def rw(m: AbsMol): Unit = {
      println(m.toString)
      Thread.sleep(scala.util.Random.nextInt(20))
    }

    val h1 = new JA[Int]("Aristotle is thinking")
    val h2 = new JA[Int]("Kant is thinking")
    val h3 = new JA[Int]("Marx is thinking")
    val h4 = new JA[Int]("Russell is thinking")
    val h5 = new JA[Int]("Spinoza is thinking")
    val t1 = new JA[Int]("Aristotle is eating")
    val t2 = new JA[Int]("Kant is eating")
    val t3 = new JA[Int]("Marx is eating")
    val t4 = new JA[Int]("Russell is eating")
    val t5 = new JA[Int]("Spinoza is eating")
    val f12 = new JA[Unit]("f12")
    val f23 = new JA[Unit]("f23")
    val f34 = new JA[Unit]("f34")
    val f45 = new JA[Unit]("f45")
    val f51 = new JA[Unit]("f51")

    join (
      run { case t1(_) => rw(h1); h1() },
      run { case t2(_) => rw(h2); h2() },
      run { case t3(_) => rw(h3); h3() },
      run { case t4(_) => rw(h4); h4() },
      run { case t5(_) => rw(h5); h5() },

      run { case h1(_) + f12(_) + f51(_) => rw(t1); t1(n) + f12() + f51() },
      run { case h2(_) + f23(_) + f12(_) => rw(t2); t2(n) + f23() + f12() },
      run { case h3(_) + f34(_) + f23(_) => rw(t3); t3(n) + f34() + f23() },
      run { case h4(_) + f45(_) + f34(_) => rw(t4); t4(n) + f45() + f34() },
      run { case h5(_) + f51(_) + f45(_) => rw(t5); t5(n) + f51() + f45() }
    )
// inject molecules representing the initial state
    t1() + t2() + t3() + t4() + t5()
    f12() + f23() + f34() + f45() + f51()
```

The result of running this program is the output such as
```
Russell is thinking
Aristotle is thinking
Spinoza is thinking
Marx is thinking
Kant is thinking
Russell is eating
Aristotle is eating
Russell is thinking
Marx is eating
Aristotle is thinking
Spinoza is eating
Marx is thinking
Kant is eating
Spinoza is thinking
Russell is eating
Kant is thinking
Aristotle is eating
Aristotle is thinking
Russell is thinking
Spinoza is eating
```

It is interesting to note that this example code is fully declarative: it describes what the “dining philosophers” simulation must do, and the code is quite close to the English-language description of the problem.

# Blocking molecules

So far, we have used molecules whose injection was a non-blocking call.
An important feature of Join Calculus is “blocking” (or “synchronous”) molecules.

The runtime engine simulates the injecting of a blocking molecule in a special way.
The injection call will be blocked until some reaction can start with the newly injected molecule.
This reaction's body will be able to send a “reply value” back to the injecting process.
Once the reply value has been sent, the injecting process is unblocked.

Here is an example of declaring a blocking molecule:

```scala
val f = jS[Int, String]
```

The molecule `f` carries a value of type `Int`; the reply value is of type `String`.

Sending a reply value is a special action available only with blocking molecules.
The “replying” action is non-blocking within the reaction body.
Example syntax for the reply action within a reaction body:

```scala
val f = jS[Unit, Int]
val c = jA[Int]

join( run { case c(n) + f(_, reply) => reply(n) } )
```

This reaction will proceed when a molecule `c(...)` is present and an `f()` is injected.
The reaction body replies to `f` with the value `n` carried by the molecule `c(n)`.

The syntax for replying suggests that `f` carries a special `reply` pseudo-molecule, and that the reaction body injects this `reply` molecule  with an integer value.
However, the `reply` does not actually stand for a molecule injector - this is merely syntax for the “replying” action that is part of the semantics of the blocking molecule.

## Example 2: benchmarking the concurrent counter

To illustrate the usage of non-blocking and blocking molecules, let us consider the task of benchmarking the concurrent counter we have previously defined.
The plan is to initialize the counter to a large value _N_, then to inject _N_ decrement molecules, and finally wait until the counter reaches the value 0.
We will use a blocking molecule to block until this happens, and thus to determine the time elapsed during the countdown. 

Let us now extend the previous join definition to implement this new functionality.
The simplest solution is to define a blocking molecule `fetch`, which will react with the counter molecule only when the counter reaches zero.
Since we don't need to pass any data (just the fact that the counting is over), the `fetch` molecule will carry `Unit` and also bring back a `Unit` reply.
This reaction can be written in pseudocode like this:
```
fetch() + counter(n) if n==0 => reply () to fetch 
```

We can implement this reaction by using a guard in the `case` clause:

```scala
run { case fetch(_, reply) + counter(n) if n == 0  => reply() }
```

For more clarity, we can also use the pattern-matching facility of `JoinRun` to implement the same reaction like this:

```scala
run { case fetch(_, reply) + counter(0) => reply() }
```

Here is the complete code:

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

import java.time.LocalDateTime.now  
import java.time.temporal.ChronoUnit.MILLIS  

object C extends App {

  // declare molecule types
  val fetch = jS[Unit, Unit]
  val counter = jA[Int]
  val decr = jA[Unit]
  
  // declare reactions
  join(
    run { case fetch(_, reply) + counter(0) => reply() },
    run { case counter(n) + decr(_) => counter(n-1) }
  )
  
  // inject molecules
  
  val n = 10000
  val initTime = now
  counter(n)
  (1 to n).foreach( _ => decr() )
  fetch()
  val elapsed = initTime.until(now, MILLIS)
  println(s"Elapsed: $elapsed ms")
}
```

Some remarks:
- Molecules with unit values do require a pattern variable when used in the `case` construction.
For this reason, we write `decr(_)` and `fetch(_, reply)` in the match patterns.
However, these molecules can be injected simply as `decr()` and `fetch()`, since Scala inserts a `Unit` value automatically when calling functions.
- We declared both reactions in one join definition, because these two reactions share the input molecule `counter`.
- Pattern-matching on the molecule value (such as `counter(0)`) is limited in the current version of `JoinRun`, due to the quirks in Scala's `unapply` method:
Reactions work correctly if the molecule with the pattern-matched value is used only the _last_ input molecule.
This limitation may be lifted in a later version of `JoinRun`.
With this limitation, the usual pattern-matching facilities of Scala can be used in reaction definitions.
- The injected blocking molecule `fetch()` will not remain in the soup after the reaction is finished.
Actually, it would not make sense for `fetch()` to remain in the soup:
If a molecule remains in the soup after a reaction, it means that the molecule is going to be available for some later reaction without blocking its injecting call; but this is the behavior of a non-blocking molecule.
- Blocking molecules are like functions except that they will block until their reactions are not available.
If the relevant reaction never starts, a blocking molecule will block forever.
The runtime engine cannot detect this situation because it cannot determine whether the relevant input molecules for that reaction might become available in the future.
- If several reactions are available for the blocking molecule, one of these reactions will be selected at random.
- It is an error if a reaction does not reply to the calling process:
```scala
val f = jS[Unit, Unit]
val c = jA[Int]
join( run { case f(_,reply) + c(n) => c(n+1) } ) // forgot to reply!

f()
java.lang.Exception: Error: In Join{f/S => ...}: Reaction {f/S => ...} finished without replying to f/S
```
- Blocking molecules are printed with the suffix `"/S"`, to indicate that they involve synchronous behavior.

## Further details: Molecules and molecule injectors

Molecules are injected into the “chemical soup” using the syntax such as `c(123)`. Here, `c` is a value we define using a construction such as

```scala
val c = jA[Int]
```

In Join Calculus, an injected molecule must carry a value.
So the value `c` itself is not a molecule in the soup.
The value `c` is a **molecule injector**, - that is, a value that can be used to inject molecules of sort `c` into the soup.
The result of calling the injector when evaluating `c(123)` is a _side-effect_ which injects the molecule of sort `c` with value `123` into the soup.

If `c` is a non-blocking molecule, the call `c(123)` is non-blocking and immediately returns `Unit`.
The injector `c` has type `JA[Int]` and can be also created directly using the class constructor:

```scala
val c = new JA[Int]
```

For a blocking molecule, such as
```scala
val f = jS[Int, String]
```
the `f` is an injector that takes an `Int` value and returns a `String`.

Injectors for blocking molecules are essentially functions: their type is `JS[T, R]`, which extends `Function1[T, R]`.
The injector `f` could be equivalently defined by
```scala
val f = new JS[Int, String]
```

Once `f` is defined like this, an injection call such as
```scala
val x = f(123)
```
will inject a molecule of sort `f` with value `123` into the soup.

The calling process in `f(123)` will wait until some reaction consumes this molecule and “replies” with a `String` value.
Only after the reaction body executes the “reply action", the value `x` will be assigned, and the calling process will become unblocked and will continue its execution.

## Molecule names

For debugging purposes, molecules in `JoinRun` can have names.
These names have no effect on any concurrent computations.
For instance, the runtime engine will not check that each molecule is assigned a name, or that the names for different molecule sorts are different.
Molecule names are used only for debugging: they are printed when logging reactions and join definitions.

There are two ways of assigning a name to a molecule:
- specify the name explicitly, by using a class constructor;
- use the macros `jA` and `jS`.

Here is an example of defining injectors using explicit class constructors and molecule names:

```scala
val counter = new JA[Int]("counter")
val fetch = new JS[Unit, Int]("fetch")
```

This code is completely equivalent to the shorter code written using macros:

```scala
val counter = jA[Int]
val fetch = jS[Unit, Int]
```

These macros can read the names `"counter"` and `"fetch"` from the surrounding code context.
This functionality is intended as a syntactic convenience.

Each molecule injector as a `toString` method.
This method will return the molecule's name if it was assigned.
For blocking molecules, the molecule's name is followed by `"/S"`.

```scala
val x = new JA[Int]("counter")
val y = new JS[Unit, Int]("fetch")

x.toString // returns “counter"
y.toString // returns “fetch/S"
```

## More about the semantics of `JoinRun`

- Injectors are local values of class `JA` or `JS`, which both extend the abstract class `AbsMol`.
- Reactions are local values of class `Reaction`. Reactions are created using the method `run { case ... => ... }`.
- Only one `case` clause can be used in each reaction.
- Join definitions are values of class `JoinDefinition`. These values are not visible to the user: they are created in a closed scope by the `join` method.
- Injected molecules are _not_ Scala values!
The programmer has no direct access to the molecules in the soup, apart from being able to inject them.
Injected molecules cannot be, say, stored in a data structure or passed as arguments to functions.
But molecule injectors (as well as reactions) _are_ ordinary scala values.
- Blocking molecule injectors are local values of class `JS` that extends `Function1`.
- Join definitions are immutable once given.
- Molecule injectors are immutable after a join definition has been given where these molecules are used as inputs.

# Example 3: concurrent map/reduce

It remains to see how we can use the “chemical machine” for performing various concurrent computations.
For instance, it is perhaps not evident what kind of molecules and reactions must be defined, say, to implement a concurrent buffered queue or a concurent merge-sort algorithm.
Another interesting application would be a concurrent GUI interaction together with some jobs in the background.
Solving these problems in Join Calculus requires a certain paradigm shift.
In order to build up our “chemical” intuition, let us go through some more examples.

A map/reduce operation first takes an array `Array[A]` and applies a function `f : A => B` to each of the elements.
This yields an `Array[B]` of intermediate results.
After that, a “reduce”-like operation `reduceB : (B, B) => B`  is applied to that array, and the final result of type `B` is computed.

This can be implemented in sequential code like this:

```scala
val arr : Array[A] = ???

arr.map(f).reduce(reduceB)
```

Our task is to implement this as a concurrent computation.
We would like to perform all computations concurrently - both applying `f` to each element of the array, and also accumulating the final result.

For simplicity, we will assume that the `reduceB` operation is associative and commutative (that is, the type `B` is a commutative monoid).
In that case, we are apply the `reduceB` operation to array elements in arbitrary order, which makes our task easier.

Implementing the map/reduce operation does not actually require the full power of concurrency: a “bulk synchronous processing” framework such as Hadoop or Spark will do the job.
Our goal is to come up with a “chemical” approach to concurrent map/reduce.

Since we would like to apply the function `f` concurrently to values of type `A`, we need to put all these values on separate copies of some “carrier” molecule.

```scala
val carrier = jA[A]
```

We will inject a copy of the “carrier” molecule for each element of the initial array:

```scala
val arr : Array[A] = ???
arr.foreach(i => carrier(i))
```

As we apply `f` to each element, we will carry the intermediate results on molecules of another sort:

```scala
val interm = jA[B]
```
 
Therefore, we need a reaction of this shape:

```scala
run { case carrier(a) => val res = f(a); interm(res) }
```

Finally, we need to gather the intermediate results carried by `interm` molecules.
For this, we define the “accumulator” molecule `accum` that will carry the final result as we accumulate it by going over all the `interm` molecules.
We can also define a blocking molecule `fetch` that can be used to read the accumulated result from another process.

```scala
val accum = jA[B]
val fetch = jS[Unit, B]
```

At first we might write reactions for `accum` such as this one:

```scala
run { case accum(b) + interm(res) => accum( reduceB(b, res) ) },
run { case accum(b) + fetch(_, reply) => reply(b) }
```

Our plan is to inject an `accum(...)` molecule, so that this reaction will repeatedly consume every `interm(...)` molecule until all the intermediate results are processed.
Then we will inject a blocking `fetch()` molecule and obtain the final accumulated result.

However, there is a serious problem with this implementation: We will not actually find out when the work is finished.
Our idea was that the processing will stop when there are no `interm` molecules left.
However, the `interm` molecules are produced by previous reactions, which may take time.
We do not know when each `interm` molecule will be injected: there may be prolonged periods of absence of any `interm` molecules in the soup (while some reactions are still busy evaluating `f`).
The runtime engine cannot know which reactions will eventually inject some more `interm` molecules, and so there is no way to detect that the entire map/reduce job is done.

It is the programmer's responsibility to organize the reactions such that the “end-of-job” situation can be detected.
The simplest way of doing this is to count how many `accum` reactions have been run.

Let us change the type of `accum` to carry a tuple `(Int, B)`.
The first element of the tuple will now represent a counter, which indicates how many intermediate results we have already processed.
Reactions with `accum` will increment the counter; the reaction with `fetch` will proceed only if the counter is equal to the length of the array.

We will also include a condition on the counter that will start the accumulation when the counter is equal to 0.

```scala
val accum = jA[(Int, B)]

run { case interm(res) + accum((n, b)) if n > 0 => 
    accum((n+1, reduceB(b, res) )) 
  },
run { case interm(res) + accum((0, _))  => accum((1, res)) },
run { case fetch(_, reply) + accum((n, b)) if n == arr.size => reply(b) }    
```

Side note: due to the current limitations of `JoinRun`, the `accum` pattern matches must be written at the last place in the reactions.

We can now inject all `carrier` molecules, a single `accum((0, null))` molecule, and a `fetch()` molecule.
Because of the guard condition, the reaction with `fetch()` will not run until all intermediate results have been accumulated.

Here is the complete code for this example.
We will apply the function `f(x) = x*x` to elements of an integer array, and then compute the sum of the resulting array of squares.

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

object C extends App {

  // declare the "map" and the "reduce" functions  
  def f(x: Int): Int = x*x
  def reduceB(acc: Int, x: Int): Int = acc + x
  
  val arr = 1 to 100

  // declare molecule types
  val carrier = jA[Int]
  val interm = jA[Int]
  val accum = jA[(Int,Int)]
  val fetch = jS[Unit,Int]

  // declare the reaction for "map"
  join(
    run { case carrier(a) => val res = f(a); interm(res) }
  )

  // reactions for "reduce" must be together since they share "accum"
  join(
      run { case interm(res) + accum((n, b)) if n > 0 => 
        accum((n+1, reduceB(b, res) )) 
      },
      run { case interm(res) + accum((0, _))  => accum((1, res)) },
      run { case fetch(_, reply) + accum((n, b)) if n == arr.size => reply(b) } 
  )

  // inject molecules
  accum((0, 0))
  arr.foreach(i => carrier(i))
  val result = fetch()
  println(result) // prints 338350
}
```

# Molecules and reactions in local scopes

Since molecules and reactions are local values, they are lexically scoped within the block where they are defined.
If we define molecules and reactions in the scope of an auxiliary function, or even in the scope of a reaction body, these newly defined molecules and reactions will be encapsulated and protected from outside access.

To illustrate this feature of Join Calculus, let us implement a function that defines a “concurrent counter” and initializes it with a given value.

Our previous implementation of the concurrent counter has a drawback: The molecule `counter(n)` must be injected by the user and remains globally visible.
If the user injects two copies of `counter` with different values, the `counter + decr` and `counter + fetch` reactions will work unreliably, choosing between the two copies of `counter` at random.
We would like to inject exactly one copy of `counter` and then prevent the user from injecting any further copies of that molecule.

A solution is to define `counter` and its reactions within a function that returns the `decr` and `fetch` molecules to the outside scope.
The `counter` injector will not be returned to the outside scope, and so the user will not be able to inject extra copies of that molecule.

```scala
def makeCounter(initCount: Int): (JA[Unit], JS[Unit,Int]) = {
  val counter = jA[Int]
  val decr = jA[Unit]
  val fetch = jA[Unit, Int]
  
  join(
    run { counter(n) + fetch(_, r) => counter(n) + r(n)},
    run { counter(n) + decr(_) => counter(n-1) }
  )
  // inject exactly one copy of `counter`
  counter(initCount)
  
  // return these two injectors to the outside scope
  (decr, fetch)
}
```

The closure captures the injector for the `counter` molecule and injects a single copy of that molecule.
Users from other scopes cannot inject another copy of `counter` since the injector is not visible outside the closure.
In this way, it is guaranteed that one and only one copy of `counter` will be present in the soup.

Nevertheless, the users receive the injectors `decr` and `fetch` from the closure.
So the users can inject these molecules and start their reactions (despite the fact that these molecules are also locally defined, like `counter`).

The function `makeCounter` can be called like this:

```scala
val (d, f) = makeCounter(10000)
d() + d() + d() // inject 3 decrement molecules
val x = f() // fetch the current value
```

Also note that each invocation of `makeCounter` will create new, fresh molecules `counter`, `decr`, and `fetch` inside the closure, because each invocation will create a fresh local scope.
In this way, the user can create as many independent counters as desired.

This example shows how we can “hide” some molecules and yet use their reactions. 
A closure can define local reaction with several input molecules, inject some of these molecules initially, and return some (but not all) molecule constructors to the global scope outside of the closure.

# Example 4: concurrent merge-sort

Chemical laws can be “recursive”: a molecule can start a reaction whose reaction body defines further reactions and injects the same molecule.
Since each reaction body will have a fresh scope, new molecules and new reactions will be defined every time.
This will create a recursive configuration of new reactions, such as a linked list or a tree of reactions.

We will now figure out how to use “recursive chemistry” for implementing the merge-sort algorithm in Join Calculus.

The initial data will be an array, and we will therefore need a molecule to carry that array.
We will also need another molecule to carry the sorted result.

```scala
val mergesort = jA[Array[Int]]
val sorted = jA[Array[Int]]
```

The main idea of the merge-sort algorithm is to split the array in half, sort each half recursively, and then merge the two sorted halves into the resulting array.

```scala
join ( run { case mergesort(arr) =>
    if (arr.length == 1) sorted(arr) else {
      val (part1, part2) = arr.splitAt(arr.length / 2)
      // inject recursively
      mergesort(part1) + mergesort(part2)
    }
  }
)
```

We still need to take two sorted arrays and merge them.
Let us assume that an array-merging function `arrayMerge(arr1, arr2)` is already implemented.
We could then envision a reaction like this:

```scala
... run { case sorted1(arr1) + sorted2(arr2) => sorted( arrayMerge(arr1, arr2) ) }
```

Actually, we need to return the upper-level `sorted` molecule from merging the results carried by the lower-level `sorted1` and `sorted2` molecule.
In order to achieve this, we need to define the merging reaction _within the scope_ of the `mergesort` reaction:

```scala
join ( run { case mergesort(arr) =>
    if (arr.length == 1) sorted(arr) else {
      val (part1, part2) = arr.splitAt(arr.length / 2)
      // define lower-level "sorted" molecules
      val sorted1 = jA[Array[Int]]
      val sorted2 = jA[Array[Int]]
      join( run { case sorted1(arr1) + sorted2(arr2) => sorted( arrayMerge(arr1, arr2) ) } )
      // inject recursively
      mergesort(part1) + mergesort(part2)
    }
  }
)
```

This is still not right; we need to arrange the reactions such that the `sorted1`, `sorted2` molecules are injected by the lower-level recursive injections of `mergesort`.
The way to achieve this is to pass the injectors for the `sorted` molecules as values carried by the `mergesort` molecule.
We will then pass the lower-level `sorted` molecule injectors to the recursive calls of `mergesort`.

```scala
val mergesort = new JA[(Array[T], JA[Array[T]])]

join(
  run {
    case mergesort((arr, sorted)) =>
      if (arr.length <= 1) sorted(arr)
      else {
        val (part1, part2) = arr.splitAt(arr.length/2)
        // "sorted1" and "sorted2" will be the sorted results from lower level
        val sorted1 = new JA[Array[T]]
        val sorted2 = new JA[Array[T]]
        join(
          run { case sorted1(arr1) + sorted2(arr2) => sorted(arrayMerge(arr1, arr2)) }
        )
        // inject lower-level mergesort
        mergesort(part1, sorted1) + mergesort(part2, sorted2)
      }
  }
)
// sort our array at top level, assuming `finalResult: JA[Array[Int]]`
mergesort((array, finalResult))
```

The complete working example of concurrent merge-sort is in the file [MergesortSpec.scala](https://github.com/winitzki/joinrun-scala/blob/master/benchmark/src/test/scala/code/winitzki/benchmark/MergesortSpec.scala).


# Limitations of Join Calculus 

While designing the “abstract chemistry” for our application, we need to keep in mind certain limitations of Join Calculus.

First, we cannot detect the _absence_ of a given non-blocking molecule, say `a(1)`, in the soup.
This seems to be a genuine limitation of join calculus.

It seems that this limitation cannot be lifted by any clever combinations of blocking and non-blocking molecules; perhaps this can be even proved formally, but I haven't tried learning the formal tools for that.
I just tried to implement this but could not find appropriate reactions.
For instance, we could try injecting a blocking molecule that reacts with `a`.
If `a` is absent, the injection will block.
So the absence of `a` in the soup can be translated into blocking of a function call.
However, no programming language is able to detect whether a function call has been blocked, because the function call is by definition a blocking call!
All we can do is to detect whether the function call has returned within a given time, but here we would like to return instantly with the information that `a` is present or absent. 

Suppose we define a reaction using the molecule `a`, say `a() => ...`.
Even if we somehow establish that this reaction did not start within a certain time period, we cannot conclude that `a` is absent in the soup at that time!
It could happen that `a()` was present but got involved in some other reactions and was consumed by them, or that `a()` was present but the computer's CPU was simply so busy that our reaction could not yet start and is still waiting in the queue.

Another feature would be to introduce “inhibiting” conditions on reactions: a certain reaction can start when molecules `a` and `b` are present but no molecule `c` is present.
However, it is not clear that this extension of the Join Calculus would be useful.
The solution based on a “timeout” appears to be sufficient in practice.

The second limitation is that “chemical soups” running as different processes (either on the same computer or on different computers) are completely separate and cannot be “pooled”.

What we would like to do is to connect many chemical machines together, running perhaps on different computers, and to pool their individual “soups” into one large “common soup”.
Our program should then be able to inject lots of molecules into the common pool and thus organize a massively parallel, distributed computation, without worrying about which CPU computes what reaction.
However, in order to organize a distributed computation, we would need to split the tasks explicitly between the participating soups.
The organization and supervision of distributed computations, the maintenance of connections between machines, the handling of disconnections - all this remains the responsibility of the programmer and is not handled automatically by Join Calculus.

In principle, a sufficiently sophisticated runtime engine could organize a distributed Join Calculus computation completely transparently to the programmer.
It remains to be seen whether it is feasible to implement such a runtime engine.


# Some useful concurrency patterns

## Background jobs

A basic asynchronous task is to start a long background job and get notified when it is done.

A chemical model is easy to invent: we define a reaction with a single non-blocking input molecule.
The reaction will consume the molecule, do the long calculation, and then inject a `finished()` molecule.

One implementation is a function that will return an injector that will start the job. 

```scala
def submitJob[R](closure: Unit => R, finished: JA[R]): JA[R] = {
  val begin = new JA[Unit]
  
  join( run { case begin(_) => 
    val result = closure()
    finished(result) }
   )
   
   begin
}
```

The `finished` molecule should belong to another join definition.

Another implementation is a molecule that will start the job when injected.

We can put the `finished` injector into the molecule value, together with the closure that needs to be run.

We lose some polymorphism since Scala values cannot be parameterized by types.

```scala
val begin = new JA[(Unit => Any, JA[Any])]

join(
  run {
    case begin(closure, finished) => 
      val result = closure() 
      finished(result)
  }
)
```


## Waiting forever

Suppose we want to implement a function `wait_forever()` that blocks indefinitely, never returning.

The chemical model is that a blocking molecule `wait` reacts with another, non-blocking molecule `godot`; but `godot` never appears in the soup.

We also need to make sure that the molecule `godot()` is never injected into the soup.
So we declare `godot` locally within the scope of `wait_forever`, where will inject nothing into the soup.

```scala
def wait_forever: jS[Unit, Unit] = {
  val godot = jA[Unit]
  val wait = jS[Unit, Unit]
  
  join( run { case godot(_,r) + wait(_) => r() } )
  
  wait 
}
```

The function `wait_forever` will return a blocking molecule injector that will block forever, never returning any value. 

## Working with an external asynchronous APIs

TODO

## Reaction constructors

Reactions in Join Calculus are static - they must be specified at compile time and cannot be modified at runtime.
`JoinRun` goes beyond this limitation, since reactions in `JoinRun` are values created at run time.
For instance, we could create an array of molecules and reactions, where the size of the array is determined at run time.

However, reactions will not be activated until a join definition is made, which can be done only once.
(We cannot write a second join definition using an input molecule that already belongs to a previous join definition.)
For this reason, join definitions in `JoinRun` are still static in an important sense.

Nevertheless, a trick can help us define new reactions at runtime. 

TODO

# Other tutorials on Join Calculus

There are a few academic papers on Join Calculus and a few expository descriptions, such as the Wikipedia article or the JoCaml documentation.

I learned about the “Reflexive Chemical Abstract Machine” from the introduction in one of the [early papers on Join Calculus](http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.32.3078&rep=rep1&type=pdf).
This was the clearest of the expositions, but even then, initially I was only able to understand the “introduction” in that paper.

Do not start by reading these papers if you are a beginner in Join Calculus - you will only be unnecessarily confused, because those texts are intended for advanced computer scientists.
This tutorial is intended as an introduction to Join Calculus for beginners.

This tutorial is based on my [earlier tutorial for JoCaml](https://sites.google.com/site/winitzki/tutorial-on-join-calculus-and-its-implementation-in-ocaml-jocaml). (However, be warned that the JoCaml tutorial is unfinished and probably contains some mistakes in some of the more advanced code examples.)

See also [my recent presentation at _Scala by the Bay 2016_](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala).
([Talk slides are available](https://github.com/winitzki/talks/tree/master/join_calculus)).
