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

Once a reaction starts, the input molecules instantaneously disappear from the soup, and then the output molecules are injected into the soup.

The simulator can start many reactions concurrently whenever their input molecules are available.

## Using chemistry for concurrent computation

The runtime engine of `JoinRun` implements such a “chemical machine simulator”.
Now, rather than merely watch as reactions happen, we are going to use this machine for practical computations.

The basic idea is that we are going to give the machine some values and expressions to be computed whenever reactions occur:

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

For a given molecule, there is always a single join definition (JD) to which this molecule “belongs” - that is, the JD where this molecule is consumed as input molecule by some reactions.
This JD is accessed as `.joinDef` method on the molecule injector.
Additionally:
- the user can see the input molecules used by reactions in that JD;
- using the `.printBag` method, the user can see which molecules are currently present in the soup owned by that JD (i.e. all molecules that are inputs in it).

After executing the code from the example above, here is how we could use the debugging facility:

```scala
counter.joinDef.get // returns Join{counter + incr => ...; counter + decr => ...}

counter.joinDef.get.printBag // returns “Join{counter + incr => ...; counter + decr => ...}
                             // Molecules: counter(98)"
```

## Common errors

### Injecting molecules without defined reactions

It is an error to inject a molecule that is not yet defined as input molecule in any JD.

```scala
val x = jA[Int]

x(100) // java.lang.Exception: Molecule x does not belong to any join definition
```

The same error will occur if such injection is attempted inside a reaction body.

If a molecule does not yet belong to any JD, its `.joinDef` method will return `None`. 

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
  println(s"Elapsed: ${initTime.until(now, MILLIS)} ms")
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
The calling process will wait until some reaction consumes this molecule and "replies" with a `String` value.
Only after the reaction body executes the "reply action", the value `x` will be assigned, and the calling process will become unblocked and will continue its execution.

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

x.toString // returns "counter"
y.toString // returns "fetch/S"
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
In order to build our intuition, let us go through some simple examples.

TODO

# Molecules and reactions in local scopes

Since molecules and reactions are local values, they are lexically scoped within the block where they are defined.
So, we can define new local molecules and reactions within an auxiliary function, or even within another reaction body.
Because of local scoping, these newly defined molecules and reactions can be effectively encapsulated and protected from outside access.

To illustrate this feature of Join Calculus, let us implement a function that will define a “concurrent counter” and initialize it with a given value.
By design, the user will not have access to the `counter` injector.
Thus we can guarantee the correct functionality of the counter.

TODO

This works but has a drawback: the counter molecule “c(0)” must be injected by hand and remains globally visible. If the user injects two copies of “c” with different values, the “inc” and “getcounter” reactions will work unreliably, choosing between the two copies of “c” at random. We would like to hide this molecule, so that the user will be unable to inject more copies of this molecule.

A clean solution requires us to create the molecule “c” locally but to make the molecules “inc” and “getcounter” globally visible. This can be accomplished if we return the molecule constructors “inc” and “getcounter” as a result value of a closure that defines and injects the molecule “c” internally.

# let make_async_counter init_value = 
   def inc() & c(n) = c(n+1) 
    or
   getcounter() & c(n) = c(n) & reply n to getcounter
   in
   spawn c(init_value);
   (inc, getcounter);;
val make_async_counter : int -> unit Join.chan * (unit -> int) = <fun>
# let (inc, getcounter) = make_async_counter 0;;
val inc : unit Join.chan = <abstr>
val getcounter : unit -> int = <fun>
# spawn inc() & inc() & inc();;
- : unit = ()
# getcounter();;
- : int = 3

Now the molecule “c” is safely hidden. It is guaranteed that only one copy of “c” will ever be present in the soup: Since this molecule is locally defined and not visible outside the closure, the user of make_async_counter is unable to inject any more copies of “c”. However, the user receives the molecule constructors “getcounter” and “inc", thus the user can inject these molecules and start their reactions (despite the fact that these molecules are locally defined, like “c"). Each invocation of make_async_counter will create new, fresh molecules “inc", “getcounter", and “c", so the user may create as many independent counters as desired.

This example shows how we can “hide” some molecules and yet use their reactions. A closure can define local reaction with several input molecules, inject some of these molecules initially, and return some (but not all) molecule constructors to the global scope outside of the closure.


# Example 4: concurrent merge-sort

TODO

# User-defined thread pools

TODO

## Stopping a thread pool


TODO

# Fault tolerance

TODO

# Other tutorials on Join Calculus

There are a few academic papers on Join Calculus and a few expository descriptions, such as the Wikipedia article or the JoCaml documentation.

I learned about the “Reflexive Chemical Abstract Machine” from the introduction in one of the [early papers on Join Calculus](http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.32.3078&rep=rep1&type=pdf).
This was the clearest of the expositions, but even then, initially I was only able to understand the “introduction” in that paper.

Do not start by reading these papers if you are a beginner in Join Calculus - you will only be unnecessarily confused, because those texts are intended for advanced computer scientists.
This tutorial is intended as an introduction to Join Calculus for beginners.

This tutorial is based on my [earlier tutorial for JoCaml](https://sites.google.com/site/winitzki/tutorial-on-join-calculus-and-its-implementation-in-ocaml-jocaml). (However, be warned that tutorial was left unfinished and probably contains some mistakes in some of the more advanced code examples.)

See also [my recent presentation at _Scala by the Bay 2016_](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala).
([Talk slides are available](https://github.com/winitzki/talks/tree/master/join_calculus)).


# < Perhaps delete most of the rest of the text since it's about OCaml >


# Limitations of Join Calculus 

While designing the “abstract chemistry” for our application, we need to keep in mind certain limitations of Join Calculus system:

- We cannot detect the _absence_ of a given non-blocking molecule, say `a(1)`, in the soup.
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

The runtime engine could be modified so that it injects a special non-blocking molecule, say, `stalled()`, whenever no further reactions are currently possible.
One could perhaps easily implement this extension to the chemical machine, which might be sometimes useful.
But this is a crude mechanism, and we will still be unable to detect the absence of a particular molecule at a given time.

Another solution would be to introduce “inhibiting” conditions on reactions: a certain reaction can start when molecules `a` and `b` are present but no molecule `d` is present.
However, it is not clear that this extension of the join calculus would be useful.
The solution based on a “timeout” appears to be sufficient in practice.

- Chemical soups running as different processes (either on the same computer or on different computers) are completely separate and cannot be “pooled”.

What we would like to do is to connect many chemical machines together, running perhaps on different computers, and to pool their individual “soups” into one large “common soup”.
Our program will then be able to inject lots of molecules into the common pool and thus organize a massively parallel, distributed computation, without worrying about which CPU computes what reaction.
However, in order to organize a distributed computation, we would need to split the tasks explicitly between the participating soups.
The organization and supervision of distributed computations, the maintenance of connections between machines, the handling of disconnections - all this remains the responsibility of the programmer and is not handled automatically.

# Example: Background jobs

A basic asynchronous task is to start a long background job and get notified when it is done.

A chemical model is easy to invent: we define a reaction with a single slow molecule and a payload. The reaction will consume the molecule and stop.

def a() = do_some_work(); 0

The reaction starts whenever the molecule “a()” is injected into the soup. The function do_some_work can perform arbitrary side-effects, including notifying somebody about the progress and the completion of the computations.

For convenience, we can define an OCaml function that will perform an arbitrary task in the background in this way:

# let do_in_background work = def a() = work(); 0 in spawn a();;
val do_in_background : (unit -> unit) -> unit = <fun>
# do_in_background (fun () -> print_string “all done\n");;
- : unit = ()
all done

We can see that the work was indeed done in the background because the return value, “()", was obtained before the message was printed.

Waiting forever

Suppose we want to implement a function wait_forever() that blocks indefinitely, never returning. The chemical model: an instant molecule reacts with another, slow molecule; but the slow molecule never appears in the soup.

def godot() & wait_for_godot() = reply () to wait_for_godot;

We also need to make sure that the molecule godot() is never injected into the soup. So we declare godot locally within the wait_forever function, and we will inject nothing into the soup ("spawn 0").

let wait_forever() = 
def godot() & wait_for_godot() = reply() to wait_for_godot in
spawn 0; wait_for_godot() ;; 

Parallel processing of lists

We would like to call a function f : 'a -> unit on each element of a list s : 'a list. All function calls should be run concurrently in arbitrary order.

The chemical model: for each element x of the list, we use a separate reaction. All these reactions have the same payload: the application of f to x. Thus, we need a single molecule that carries x as the decoration value. For more flexibility, we can include the function f in the decoration value. The reaction is defined like this:

def a(f,x) = f x; 0

To start the reactions, we need to inject the “a” molecules into the soup, and we need to inject as many instances of the molecule as elements in the list. So we would need to write spawn a(f,x1) & a(f,x2) & ... & a(f,xN). How can we perform this operation, given a list of values and a function? We could evaluate each spawn separately, using the standard list iterator:

List.iter (fun x -> spawn a(f,x)) [0,1,2];;

This will inject three copies of the slow molecule “a” into the soup. Three reactions will eventually start (in unknown order).

Another possibility is to use the special loop syntax for “spawn":

spawn for i = 0 to 2 do a(f, i) done;;
Waiting for completion of many jobs

Suppose we want to start a number of background jobs, all of the same kind, and we need to wait (synchronously) until all of those jobs are finished. It is clear that each job is running as a payload on some reaction. Thus, we need to wait until all reactions of a given kind are finished. Let us denote by “job()” the molecule that starts each of these reactions.

The only way to wait for something is by arranging a reaction that does not start until a certain molecule is present. Thus, we need a molecule that is absent from the soup until all our jobs are finished. Let us call this molecule “all_done()”. We could define a reaction that notifies somebody of the completion of all jobs:

def all_done() = print_string “all done\n"; 0

Now, who will inject “all_done()” into the soup? 

When one job is finished, the job reaction cannot know whether other jobs are still running. Also, the chemical machine cannot perform a direct test for the absence of a molecule, or a direct test for the presence of a certain number of identical molecules. Thus, we cannot have a reaction that generates “all_done()” when all “job” molecules are consumed.

The only solution is to count the finished jobs by hand, knowing in advance how many jobs were started. So we need a reaction that knows how many jobs were started and generates “all_done()” when no jobs remain unfinished, but not before. Since we cannot have reactions that involve several instances of the same molecule, we need to hold the number of unfinished jobs as a decoration value on some molecule. Let us call this molecule “remains(n)”. Each job can consume “remains(n)” when done and inject a new remains(n-1) molecule. If nothing remains, we can inject all_done() into the soup. 

Our first try for the “job” reaction is this:

def job(do_work) & remains(n) = do_work(); if n>1 then remains(n-1) else all_done()

Initially we inject one instance of remains(n) and n instances of the job(...) molecule into the soup. Each job(...) molecule could carry its own workload function. When all_done() appears, we are sure that all jobs have finished. Let us test this: each job will simply print a digit from 0 to 9.

# spawn remains(4);; (* we consider things done when 4 jobs are finished *)
- : unit = ()
# spawn for i=0 to 9 do job(fun () -> print_int i) done;; (* but we inject 10 jobs into the soup *)
- : unit = ()
# 
0987all done
spawn remains(3);; (* now we consider things done when 3 jobs are finished *)
- : unit = ()
654all done

This solution is flawed in several ways. At the end of the calculation shown above, the soup still contains four job(...) molecules. However, there are no remains(n) molecules, so no further reactions are actually running. If we want to keep the jobs running even after the all_done() molecule was generated, we can modify the definition of the reaction so that the remains(n) molecule is always kept in the soup. Nothing prevents us from injecting several remains(n) molecules into the soup at the same time, with different values of “n”. We could prohibit this by encapsulating the remains(n) molecules, so that the user cannot make a mistake when injecting the molecules.

There is another, more serious flaw in the present code; can you see it?

The flaw is that the remains(n) molecule is consumed by the job reaction (and injected only when a job is finished). While one job is computing its do_work() function, the remains(n) molecule is not in the soup. So only one job can run at any one time. We lost the concurrency!

In order to restore the concurrency, we need to make sure that  remains(n) molecule is always present in the soup. The updating of the value of “n” must be synchronous, but we would like this to be done while other jobs are running. Therefore, we need another reaction for updating of the value of “n” in  remains(n). This reaction must be triggered asynchronously at the end of each job; let us define a triggering molecule for this, called done(). The updating reaction could look like this:

def remains(n) & done() = if n>1 then remains(n-1) else all_done()

The job reaction is then simplified: 

def job(do_work) = do_work(); done()

The process looks like this: we inject several “job” molecules and one “remains” molecule. All jobs molecules can start at once, producing eventually a bunch of “done” molecules. These “done” molecules react one by one with the single “remains” molecule. All the “job” reactions can run in parallel, but only one “remains” reaction runs at any one time.

An alternative implementation is to make the “done” molecule an instant molecule. Then the reactions look like this:

def
 job(do_work) = do_work(); done(); 0
and
 remains(n) & done() = reply () to done & if n>1 then remains(n-1) else all_done();;

Now each “job” reaction starts concurrently but blocks at the instant “done” call.

It seems that the chemical model is quite flexible and allows us to configure the concurrent computations in pretty much any manner.

Further examples

 I would like to consider three examples now:

Given an array of functions, produce an array of their result values. The functions should be evaluated asynchronously in parallel.
Given an array of integers, compute their sum. All pairwise summations should be evaluated in parallel in arbitrary order, and the partial sums should be also added in parallel in arbitrary order.
Sort an array of integers using the merge-sort algorithm, again doing as much as possible in parallel.


Now let us figure out the implementation of these examples in join calculus. We will be using the purely “chemical” approach to concurrent computation. We will never say the words “semaphore", “thread", “deadlock", “mutex", or “synchronize”. Instead, we will talk about molecules and reactions. Our goal is to see what kind of tricks and patterns emerge from this paradigm.
Other limitations of join calculus

In order to implement a concurrent computation of many functions, one might want to define a separate molecule for each array element. However, this is not possible in the join calculus. The join calculus has these limitations, beyond those I described before:

We cannot define a computed set of input molecules. For instance, if we wanted to define a reaction with 1000 input molecules, we cannot write the input molecules as “a_n for n = 0, ..., 999”. We would have to define the molecules named a000, a001, ..., a999 explicitly and statically (i.e. at compile time) in the JoCaml program. 
We cannot define a reaction with a variable number of input molecules, say a reaction that starts when a(x) is present or when a(x) and b(y) are present. This is impossible since the reaction body will have to check whether the value “y” is defined, which is not allowed in OCaml: all values must be defined, or else you get a compile-time error.
It is also impossible to define a reaction with duplicated molecules. We cannot have a reaction that starts when, say, exactly three molecules a(x), a(y), a(z) are present.

We cannot specify reactions that start under a computed condition depending on the set of input molecules. In join calculus, a reaction starts when all its input molecules, statically specified, are present; the chemical engine can only check that these input molecules are present. We cannot specify any additional computations to determine the set of input molecules for a reaction.

For example, if we wanted to have 1000 molecules named a000, a001, ..., a999, and a reaction that starts when any subset of a 100 of them are present, the chemical engine would have to perform a computation on the set of input molecules before deciding whether to start a reaction. By design, the join calculus prohibits any nontrivial computations before deciding to start a reaction.

For the same reason, we cannot specify that a reaction should start when some computed condition holds on the decoration values of the input molecules. 

So we cannot have a reaction a(x) & b(y) “when p(x,y)” = ... that starts only when some computed condition p(x,y) holds on the values x, y and otherwise does not start. We can only perform a static pattern-matching on the values x,y; so we can specify that the reaction starts only when x has a specific value out of a fixed, statically specified set of values. Also, the pattern variables on the left-hand side of a reaction must be all different;  in particular, we cannot specify a reaction such as “a(x) & b(x) = ...", starting only when the molecules “a” and “b” carry equal values. The chemical machine will not accept any additional computations or conditions for starting a reaction.

Nevertheless, it seems that the join calculus can express essentially all concurrent computations. We will implement the examples now.

Evaluate many functions in parallel and store results


Each evaluation of a function must be a separate reaction. The payload of that reaction can assign the element of the array with the result value. So we only need one molecule for this reaction. Let this molecule be called “a” and carry, as decoration values, the function, the array, and the array index.

let n = 10;;
let tasks = (* array of functions, each returns 1 *)
  Array.make n (fun () -> 1);;
let results = Array.make n 0;;
def a(f,arr,i) = arr.(i) <- f(); 0;;
for i = 0 to n-1 do 
  spawn a(tasks.(i),results,i) 
done;;

This works; however, the “results” array is updated asynchronously, and the final array will be ready at an unknown time when all 100 reactions are finished. Certainly, we would like to be notified when this happens! As we have seen before, we need a counter that will show how many computations remain. This counter will start a new reaction when all computations are finished. This counter also needs a separate reaction for updating itself. How will we write the reaction for the counter?

It is impossible in join calculus to wait until no more “a” molecules are present. Therefore, the counter should explicitly know how many reactions were started.

To maintain the counter, we need to check how many “a” reactions have been completed. Let us produce an additional molecule, “ready", at the end of each “a” reaction, so that the counter could react with the “ready” molecules.

So we modify the reaction for “a” like this:

def a(f,arr,i) = arr.(i) <- f(); ready()
and ready() & remains(k) = if k>1 then remains(k-1) else all_done()
and all_done() = print_string “all done\n"; 0;;
spawn remains(n);;


The advantage of this approach is that the counting is performed asynchronously, independently of the “a” reaction. Thus, all “a” reactions can run in parallel, producing many “ready” molecules, while the “remains” molecule counts and consumes each of the “ready” molecules one by one.

This code works; here is the full test code for JoCaml. I added some printing so that we can watch the progress.

# let test n = 
 let tasks = Array.make n (fun () -> 1) in 
 let results = Array.make n 0 in
 def
   a(f,arr,i) = arr.(i) <- f(); print_int i; print_newline(); ready() 
and
   ready() & remains(k) = if k>1 then remains(k-1) else all_done() 
and 
   all_done() = print_string “all done\n"; 0 
in

spawn remains(n); 
for i = 0 to n-1 do 
 spawn a(tasks.(i),results,i) 
done 
in 
test 10;;
0
2
1
3
5
6
4
7
9
8
all done
- : unit = ()


As we can see, the tasks were processed concurrently in somewhat arbitrary order.
