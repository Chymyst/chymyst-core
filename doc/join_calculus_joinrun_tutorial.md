# Using `JoinRun` for concurrency

`JoinRun` is a library for general, unrestricted concurrency.
It follows the paradigm of [Join Calculus](https://en.wikipedia.org/wiki/Join-calculus)
and implemented as an embedded DSL (domain-specific language) in Scala.

Source code is available at [https://github.com/winitzki/joinrun-scala](https://github.com/winitzki/joinrun-scala).

# Previous work

Here are previous implementations of Join Calculus that I was able to find.
- The `Funnel` programming language: [M. Odersky et al., 2000](http://lampwww.epfl.ch/funnel/). This project was discontinued.
- _Join Java_: [von Itzstein et al., 2001-2005](http://www.vonitzstein.com/Project_JoinJava.html). This was a modified Java language. The project is not maintained. 
- The `JoCaml` language: [Official site](http://jocaml.inria.fr) and a publication about JoCaml: [Fournet et al. 2003](http://research.microsoft.com/en-us/um/people/fournet/papers/jocaml-afp4-summer-school-02.pdf). This is a dialect of OCaml implemented as a patch to the OCaml compiler. The project is still supported. 
- “Join in Scala” compiler patch: [V. Cremet 2003](http://lampwww.epfl.ch/~cremet/misc/join_in_scala/index.html). The project is discontinued.
- Joins library for .NET: [P. Crusso 2006](http://research.microsoft.com/en-us/um/people/crusso/joins/). The project is available as a binary .NET download from Microsoft Research.
- `ScalaJoins`, a prototype implementation in Scala: [P. Haller 2008](http://lampwww.epfl.ch/~phaller/joins/index.html). The project is not maintained.
- `ScalaJoin`: an improvement over `ScalaJoins`, [J. He 2011](https://github.com/Jiansen/ScalaJoin). The project is not maintained.
- Joinads, a not-quite-Join-Calculus implementation in F# and Haskell: [Petricek and Syme 2011](https://www.microsoft.com/en-us/research/publication/joinads-a-retargetable-control-flow-construct-for-reactive-parallel-and-concurrent-programming/). The project is not maintained.
- Proof-of-concept implementations of Join Calculus for iOS: [CocoaJoin](https://github.com/winitzki/AndroJoin) and Android: [AndroJoin](https://github.com/winitzki/AndroJoin). These projects are not maintained.

The implementation in `JoinRun` is based on ideas from Jiansen He's `ScalaJoin` as well as on CocoaJoin / AndroJoin.

# Other tutorials on Join Calculus

There are a few academic papers on Join Calculus and a few expository descriptions, such as the Wikipedia article or the JoCaml documentation.

I learned about the “Reflexive Chemical Abstract Machine” from the introduction in one of the [early papers on Join Calculus](http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.32.3078&rep=rep1&type=pdf).
This was the clearest of the expositions, but even then, initially I was only able to understand the “introduction” in that paper.

Do not start by reading these papers if you are a beginner in Join Calculus - you will only be unnecessarily confused, because those texts are intended for advanced computer scientists.
This tutorial is intended as an introduction to Join Calculus for beginners.

This tutorial is based on my [earlier tutorial for JoCaml](https://sites.google.com/site/winitzki/tutorial-on-join-calculus-and-its-implementation-in-ocaml-jocaml). (However, be warned that tutorial was left unfinished and probably contains some mistakes in some of the more advanced code examples.)

See also [my recent presentation at _Scala by the Bay 2016_](https://scalaebythebay2016.sched.org/event/7iU2/concurrent-join-calculus-in-scala).
([Talk slides are available](https://github.com/winitzki/talks/tree/master/join_calculus)).


# The chemical machine

The reader is assumed to have some familiarity with the `Scala` programming language.

Although this tutorial focuses on using `JoinRun` in Scala, one can easily implement
the core Join Calculus on top of any programming language as a library.
The main techniques and concepts of Join Calculus programming paradigm 
are independent of the base programming language.

In Join Calculus, concurrent computations are organized in a specific manner,
which is easiest to understand through the “chemical” analogy.

Imagine that we have a large tank of water where many different chemical substances are dissolved.
Several different chemical reactions could then become possible in this “chemical soup".
Reactions will proceed concurrently in different regions of the soup, as various molecules come together so that reactions could start.

The “chemistry” we are going to use will be completely imaginary and will have nothing to do with real-life chemistry.
For instance, we can postulate that there exist molecules `a`, `b`, `c`, and that they can react as follows:

`a + b ⇒ a`

`a + c ⇒` _nothing_

Real-life chemistry, of course, would not allow two molecules to react and to produce no other molecules as the result of the chemical reaction.
But our chemistry is imaginary, and the programmer is free to postulate arbitrary “chemical laws."

A typical chemical solution contains a very large number of individual molecules.
So we imagine that the “chemical soup” may contain many copies of each molecule.
For example, the soup can contain five hundred copies of `a` and three hundred copies of `b`, etc.
We also assume that we can inject any molecule into the soup at any time.

It is not difficult to implement a simulator for the “chemical soup” behavior as just described.
Having specified the allowed reactions, we wait until the soup contains molecules that can react.

In a reaction such as `a + b + c ⇒ d + e` we call `a`, `b`, and `c` 
the **input molecules** and `d`, `e` the **output molecules**.
Once this reaction starts, the input molecules instantaneously disappear from the soup,
 nd then new instances of output molecules are injected into the soup.

## Using chemistry for concurrent computation

Now, let us assume that this “chemical machine” has been already implemented.
Rather than just watch reactions happen, we would like to use this machine for executing some computations.

The basic idea is that we are going to give the machine some extra values and expressions to be computed whenever reactions occur:

- Each molecule carries a value. Molecule values are strongly typed: a molecule of a given sort can only carry values of some fixed specified type.
- Each reaction carries a function (the **reaction body**) that computes some values and puts these values on the output molecules.
The values of the input molecules are the arguments of the reaction body.

In `JoinRun`, we use the syntax like `a(123)` to denote molecule values.
In this example, `a(123)`, the molecule `a` carries an integer value.

A typical Join Calculus reaction (equipped with molecule values and a reaction body) may look like this:

```scala
a(x) + b(y) ⇒ val z = computeZ(x,y); a(z)
```

In this example, the reaction's input molecules are `a(x)` and `b(y)`;
that is, the input molecules have chemical designation `a` and `b`, and carry values `x` and `y` respectively.
The reaction body is a function that receives `x` and `y` as input arguments.
It computes a value `z` out of `x` and `y`, and puts that `z` onto the output molecule `a`.

Whenever input molecules are available in the soup, the "chemical simulator" runtime engine will start a reaction.
If many copies of input molecules are available, the runtime engine will start several reactions concurrently.
Note that each reaction depends only on the values of its input molecules.
So it is completely safe to execute concurrently several instances of the same reaction, starting from different sets of input molecules.
This is the way Join Calculus uses the "chemical simulator" to execute concurrent computations.

## The syntax of `JoinRun`

So far, we have been using some chemistry-resembling pseudocode to illustrate the structure of "chemical reactions".
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

## First example: concurrent counter

We would like to maintain a counter with an integer value, which can be incremented
or decremented by non-blocking, concurrently running operations.
(For example, we would like to be able to increment and decrement the counter from different processes running at the same time.)

To implement this using Join Calculus, we begin by deciding which molecules we will need to define.
It is clear that we will need a molecule that carries the integer value of the counter.

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

The reactions must be such that the counter is incremented when we inject the `incr` molecule,
and decremented when we inject the `decr` molecule.

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
This is called a **`join` definition**.
- In Join Calculus, all reactions that share input molecule must be defined in the same join definition.
Reactions that share no input molecules can (and should) be defined in separate join definitions.

After defining the molecules and their reactions, we can start injecting new molecules into the soup:

```scala
counter(100)
incr() // now the soup has counter(101)
decr() // now the soup again has counter(100)
decr()+decr() // now the soup has counter(98)
```

The alternative syntax `decr()+decr()` means injecting two molecules at once.
In the current version of `JoinRun`, this will be equivalent to injecting the molecules one by one.

It could be that we are injecting `incr()` and `decr()` molecules too quickly for reactions to start.
This will result in many instances of `incr()` or `decr()` molecules being present in the soup, waiting to be consumed.
This is not a problem since we are going to allow only one instance of the `counter` molecule to be present in the soup.
So there will be at most one reaction going on at any time, with either an `incr` or a `decr` molecule.
Thus, we cannot have any race conditions with the counter (due to updating the counter value simultaneously from different processes).

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
incr() // prints "new value is 101"
decr() // prints "new value is 100"
decr()+decr() // prints "new value is 99" and then "new value is 98"
```

There is also a debugging facility in `JoinRun`.
For a given molecule, there will be a single join definition to which this molecule "belongs"
(i.e. where this molecule is used as an input molecule for some reactions).
This join definition is accessed as `.joinDef` method on the molecule injector.
Additionally:
- the user can inspect that join definition's reactions;
- the user can see which molecules are currently present in the part of the soup
that pertains to that join definition (i.e. all molecules that are inputs in it)
using the `.printBag` method on the join definition.

After executing the code from the example above, here is how we could use the debugging facility:

```scala
counter.joinDef.toString // returns Some(Join{counter + incr => ...; counter + decr => ...})

counter.joinDef.get.printBag // returns "Join{counter + incr => ...; counter + decr => ...}
                             // Molecules: counter(98)"
```

## Common errors

### Injecting undefined molecules

It is an error to inject a molecule that is not yet defined as input molecule in any reactions.

```scala
val x = jA[Int]

x(100) // java.lang.Exception: Molecule x does not belong to any join definition
```

The same error will occur if such injection is attempted inside a reaction body.

The correct way of using `JoinRun` is first to define molecules,
then to describe reactions in a join definition,
and only then start injecting these molecules.


### Redefining input molecules

It is also an error to define a reaction whose input molecule is already used as input in another join definition.

```scala
val x = jA[Int]
val a = jA[Unit]
val b = jA[Unit]

join( run { case x(n) + a(_) => println(s"have x($n) + a") } ) // OK

join( run { case x(n) + b(_) => println(s"have x($n) + b") } )
// java.lang.Exception: Molecule x cannot be used as input since it was already used in Join{a + x => ...}
```

Correct use of Join Calculus requires that we put these two reactions in a single join definition:
 
```scala
val x = jA[Int]
val a = jA[Unit]
val b = jA[Unit]

join(
  run { case x(n) + a(_) => println(s"have x($n) + a") },
  run { case x(n) + b(_) => println(s"have x($n) + b") }
) // OK
``` 

More generally, all reactions that share any input molecules must be defined together in a single join definition.
However, reactions that use a molecule only as output can be in another join definition.
Here is an example:

TODO finish this example

```scala
val x = jA[Int]
val a = jA[Unit]
val b = jA[Unit]

join(
  run { case x(n) + a(_) => println(s"have x($n) + a") },
  run { case x(n) + b(_) => println(s"have x($n) + b") }
) // OK
``` 

### Nonlinear patterns

Join Calculus also requires that all input molecules for a reaction should be of different sorts.
It is not allowed to have a reaction with repeated input molecules, e.g. of the form `a + a => ...` where the molecule `a` is repeated.
The input molecule list that has a repeated molecule is called a "nonlinear pattern".

```scala
val x = jA[Int]

join(run { case x(n1) + x(n2) =>  })
// java.lang.Exception: Nonlinear pattern: x used twice
``` 

Sometimes it appears that using repeated input molecules is the most natural way of expressing the desired behavior of a program.
However, I believe it is always possible to introduce some new auxiliary molecules
and to rewrite the "chemistry laws" so that input molecules are not repeated
while the resulting computations give the same results.

## Order of reactions

When there are several different reactions that can be started, the runtime engine will choose the reaction at random.
Similarly, when there are several instances of the same molecule that can be used as input for a reaction,
the runtime engine will make a choice at random.
It is not possible to assign priorities to reactions or to molecules.
The order of reactions in a join definition is insignificant,
and the order of molecules in the input list is also insignificant.
The debugging facility will print the molecule names in alphabetical order, and reactions in an unspecified order.

The order in which reactions will start is non-deterministic and unknown.

## Molecule injectors and molecule names

TODO

## Summary

The “chemical machine” requires for its description:
- a list of defined molecules, together with their types;
- a list of “chemical reactions” involving these molecules as inputs, together with reaction bodies.

The user can define reactions in one or more join definitions.
One join definition encompasses all reactions that have some _input_ molecules in common.

After defining the molecules and specifying the reactions,
the user can start injecting molecules into the soup.

In this way, a complicated system of interacting concurrent processes
can be specified through a particular set of “chemical laws” and reaction bodies.

# Example 0: declarative solution of "dining philosophers"

TODO

# Blocking and non-blocking molecules

TODO

# More about the semantics of `JoinRun`

- Molecule injectors are local values
- Reactions are local values
- Join definitions are captured local values
- Injected molecules are not local values
- Blocking molecule injectors are functions
- Join definitions are immutable once given
- Molecule injectors are immutable after join definition has been given


TODO

# Example 1: concurrent map/reduce

TODO

# Molecules and reactions in local scopes

TODO

# Example 2: concurrent merge-sort

TODO

# User-defined thread pools

TODO

## Stopping a thread pool

TODO

# Fault tolerance

TODO

# Limitations in the current version

TODO

# Roadmap for the future

TODO

# < Perhaps delete most of the rest of the text since it's about OCaml >

TODO 

The abstract machine will evaluate the reaction body whenever the reaction occurs, before producing the output molecules.
(So reactions are not instantaneous any more, because evaluating the reaction bodies will take time.)

We may inject arbitrary new molecules to the soup at any time (not only at the initial time), also from within reactions.

Local new molecules can be defined within a closure and injected.


It is important that any given reaction can happen only when all of its input molecules are present in the soup. For example, reaction 1 can start only when at least one instance of each of the molecules a(), b(), and c() are present in the soup. Reaction 2 can start whenever at least one instance of the c() molecule is present.

The input molecules are separated by the symbol & so that we can remember that the reaction starts only when all of these input molecules are present in the soup. The output molecules are separated by the symbol & so that we remember that they are injected all together in the soup, at once.

In the real world, the presence of molecules in the soup is necessary but not sufficient: reactions start only when the input molecules move physically near each other. We cannot force the “meeting of molecules” to happen exactly when we want. All we can do is to stir the soup, giving the molecules a chance to move around and meet other molecules. So we cannot predict exactly when particular reactions will start happening.

Exactly the same holds for our “abstract chemical machine". Since we cannot see the movements of the molecules, all we can say is that reactions will start at a random or unpredictable times after injecting the molecules into the soup.

Let us watch an example of how the chemical machine operates. For this example, we assume that there are only two reactions -- the reactions 1 and 2 in the example above. Reaction 1 can start when a(), b(), and c() are present in the soup, while reaction 2 requires only the molecule c(), and so it can start whenever there is an instance of the molecule c() in the soup. 

Suppose that initially the soup contains five instances of the molecule c() but no other molecules. Reaction 1 cannot start since it requires molecules a() and b(), but we have only c(). Reaction 2 could start with any of the five copies of c(), or even with all of these c's -- in parallel. However, it is not guaranteed that five parallel processes with reaction 2 will start immediately. (Heuristically, we can imagine that reactions start reasonably quickly but not instantaneously.) So it is quite possible that one instance of c() is converted to a() & b() & d() by reaction 2, while some other instances of c() remain in the soup because reaction 2 has not yet started for them. In that case, reaction 1 could start. What we observe is that first, reaction 2 proceeds, and then, at some unknown time after a() & b() were injected into the soup by reaction 2, the machine starts reaction 1.

In order to use the chemical machine, we need to provide a complete description of the “chemical laws". For that, we need to write down two things:
A list of all possible reactions, like the reactions (1) and (2) above. This also defines all the possible molecules.
A list of molecules that are initially injected into the soup. (For instance, we could specify that initially there are ten a's and five b's, or whatever.)
We are completely free to define any number of molecules and reactions. The machine takes the initially injected molecules and runs for a while. Some reactions occur, perhaps producing new molecules, which then initiate new reactions, and so on.

Depending on the specific “chemical laws", the machine could run reactions forever, or stop. For instance, some reactions can become impossible if the required molecules disappear from the soup and never appear again. It is up to us to design the “chemical laws” so that the molecules appear and disappear in a useful way. We need to keep in mind that the order and the timing of the reactions is unpredictable.

As another simple example, consider a chemical machine with the following four reactions. (Reactions involving the same input molecules must be defined together, and joined using the keyword “or".)

def a() & b() = c()
or  b() = a()
or c() = d()
or d() = c() ;;

Suppose that initially the soup contains a() & b(). If the reaction a() & b() = c() occurs first, we will just have c() in the soup. Once we have c(), there will be infinitely many transitions between c() and d(), so the chemical machine will never stop. On the other hand, it may be that the reaction b() = a() occurs first. Then we will have two a's in the soup, and no further reactions will ever occur unless some new molecules are injected. This is an example of a program that permits the machine to either stop or go on forever, depending on the (unpredictable) choice of the initial reaction. Most probably, such a program is not what we would want.

It is our task to design the “abstract chemical laws” such that the reactions happen in some orderly fashion, so that the end state of the machine is predictable.
Using the chemical machine for doing computations


# Example

To test that our understanding is correct, let us run reactions (3) - (4) in the JoCaml system. Here is the output (one needs a double semicolon to force evaluation in the JoCaml interpreter).

$ jocaml
JoCaml version 3.12.1

# def c(z) = Printf.printf “reaction 5 consumes c(%d)\n” z; 0
;;
  def a(x) & b(y) = let z = x+y in ( Printf.printf “reaction 1 yields c(%d)\n” z; c(z) )
;;
val c : int Join.chan = <abstr>
val a : int Join.chan = <abstr>
val b : int Join.chan = <abstr>
# spawn a(2) & b(3) & c(4) ;;
- : unit = ()
# (* press Enter to flush I/O buffers *)
reaction 5 consumes c(4)
reaction 1 yields c(5)
reaction 5 consumes c(5)

As a brief comment, let us look at the types of the expressions inferred by JoCaml. After defining the reactions for a(x), b(x), c(x), we see that JoCaml has defined values “a", “b", “c” of type “int Join.chan". This type is an instance of the polymorphic type “'a Join.chan", which is the type for molecule constructors. A molecule constructor is a JoCaml value of type “'a Join.chan” and can be understood as a special kind of function that takes a value of type 'a and produces a molecule decorated with that value. In the above example, “c” is a molecule constructor of type “int Join.chan". The type “int” was inferred automatically - it is forced by the expression “x+y". In the “spawn” statement, we applied the function “c” to an integer 4 and obtained a copy of the molecule “c(4)". [We could write “c 4” instead of “c(4)", but we hold on to the convention of putting extra parentheses.] 

Now, it is important to realize that a fully constructed molecule, such as “c(4)", is not an OCaml value. We cannot write “let x = c(4) in ...” or “arr.(0) <- c(4)". Generally, we cannot use “a(4)” in any OCaml data structure or expression. (If we try, we get the message that the molecule constructor, “c", is not a function and “cannot be applied".) We are allowed to write “c(4)” only under “spawn” and when defining reactions. A fully constructed molecule, such as “c(4)", does not have an OCaml type! Nevertheless, the bare “c", i.e. the molecule constructor, is an OCaml value of type “int Join.chan". The molecule constructors can be stored in data structures, returned as result values by functions, and generally manipulated like any other OCaml values.

Once we have defined a reaction, we have also automatically defined the molecule constructors for all the input molecules. Here is an example illustrating all this:


# def a(x) & b(y) = let z = x+y in a(z);;
val a : int Join.chan = <abstr>
val b : int Join.chan = <abstr>
# let p = a;;
val p: int Join.chan = <abstr>
# let q = a(3);;
Characters 8-9:
  let q = a(3);;
          ^
Error: This expression is not a function; it cannot be applied

Note that the molecule constructors for the output molecules cannot be defined separately; all output molecules need to be known at the time of defining the reaction, or they need to be defined as input molecules in the same “def” statement. Here is what happens if we do not define output molecules but use them in a new reaction:

$ jocaml
JoCaml version 3.12.1

# def a(x) & b(y) = c(x+y);;
Characters 18-19:
  def a(x) & b(y) = c(x+y);;
                    ^
Error: Unbound value c
# (* let's define c first, then the reaction with a,b,c *)
def c(x) = 0;;
val c : 'a Join.chan = <abstr>
# def a(x) & b(y) = c(x+y);;
val a : int Join.chan = <abstr>
val b : int Join.chan = <abstr>


As we will see below, it is possible to define several reactions at once, so that each reaction is using molecules of other reactions.

Summary so far

What we have just described is the view of concurrent computations known as the “join calculus". In that view, one imagines a soup of “abstract chemical molecules” that interact randomly among themselves, according to a predefined set of possible “chemical reaction laws". Each “reaction” carries a computational “payload", which is an expression to be computed when the reaction starts. To use the chemical machine for computations, we first define all the reactions and the molecules allowed in our “abstract chemistry". We also define the computational payloads for reactions. Then we start the machine and inject some initial molecules into it. The machine automatically creates and synchronizes all the required concurrent processes for us. The processes wait until suitable molecules are present in the soup, then automatically evaluate the payload expressions, then perhaps go to sleep or create new processes, according to the custom rules of the “abstract chemistry". Computed values can be passed from one process to another through as “decoration values” of the molecules. In this way, a complicated system of interacting concurrent processes can be specified as a particular “chemical machine". 

In order to implement a particular concurrent program in the join calculus, we must somehow invent the appropriate “molecules” and design “reactions” that will create and destroy the molecules in such a way that the concurrent payload computations are coordinated correctly.

It remains to see how we can use the “chemical machine” for performing some concurrent computations. For instance, it is perhaps not evident what kind of “molecules” and “reactions” must be defined, say, to sort an array in parallel. Another interesting application would be a concurrent GUI interaction together with some jobs in the background. Before we consider these applications, let us look at a few more features of the JoCaml system.
More features

Here are the features of JoCaml that I did not yet talk about (in the order of decreasing complexity):

Synchronous (or “instant") molecules (the reply ... to construction)
Different reactions defined on the same molecules
OCaml expressions involving molecule constructors and molecules
"Remote injection” of molecules: TCP-IP socket connections to other “chemical machines” running either on the same physical computer or on remote computers

Synchronous ("instant") molecules

Normally, all reactions occur asynchronously, that is, at random times. Suppose we define this simple reaction,

# def a(x) & b(y) = print_int (x+y); 0 ;;
val a : int Join.chan = <abstr>
val b : int Join.chan = <abstr>

In JoCaml, we use def to define a reaction and at the same time to define the molecules occurring on the left-hand side of the def. The JoCaml system said that now two molecules are defined. What the JoCaml output does not say is that a reaction was defined as well, i.e. the chemical machine stored the payload expression “print_int (x+y)” in its internal memory and prepared to evaluate this expression when the molecules are present.

We can now inject some a and b molecules into the soup:

# spawn a(1) & b(2) ;;
- : unit = ()

The injection of these molecules is a non-blocking function. In other words, the spawn expression returns right away with the empty value, and our program continues to run. Perhaps, quite soon the reaction will start, the payload expression will be computed, and we will get 3 printed as a side effect of that. However, this computation is asynchronous. In other words, we will have to wait for an unknown duration of time until the “3” is printed. (If the chemical machine is very busy running lots of other reactions, we might have to wait a long time until our payload computation even begins.)

JoCaml introduces a special kind of molecules that are synchronous. Perhaps a fitting description of them is “instant molecules". When we inject an instant molecule into the soup, the chemical machine will immediately check whether any reaction involving this molecule is possible. If yes, the machine will immediately run this reaction and deliver the result value to the injecting expression. If no reaction is possible with this molecule, the injecting call will wait until a reaction becomes possible (e.g., until some other required molecule appears in the soup). Thus, injecting an instant molecule into the soup is a blocking function call. 

To summarize, instant molecules are different from the usual (asynchronous, or “slow") molecules in these respects: 

instant molecules are injected into the soup without the spawn keyword: instead of spawn a(3) we simply write a(3) if “a” is an instant molecule.
instant molecules have a result value (in addition to the decoration value), so e.g. “a(3)” returns a value, - just like an ordinary function call.
instant molecules are always immediately consumed by the reaction, i.e. they cannot remain in the soup when the reaction is finished.


How does JoCaml define the result value of an instant molecule? Let us imagine a reaction where the molecule “a(x)” is “slow” and the molecule “s(y)” is “instant". We would like the reaction between “a” and “s” to produce some other slow molecules “b” and “c", and at the same time we would like the molecule “s” to return the value x+y. Our wish can be written informally (i.e., not yet with the correct JoCaml syntax) as

a(x) & s(y) = b & c & (s returns x+y)

Suppose there is initially no “a” molecule in the soup. We want to use this reaction by evaluating an expression containing the molecule “s", say the expression s(3)*2. In this expression, “s(3)” looks like an ordinary (i.e. synchronous) function that blocks until some other reaction injects, say, “a(2)” into the soup. Then “s(3)” will return the value “5", while the molecules “b” and “c” will be injected into the soup. The entire expression s(3)*2 evaluates to 10.

JoCaml allows us to implement this reaction by using the following syntax,

def a(x) & s(y) = b() & c() & reply x+y to s

Once we have used the construction reply ... to with the name “s", the JoCaml system recognizes the molecule “s” as synchronous ("instant").

(Despite the English significance of the keywords “reply ... to", the value “x+y” is not our “reply to s", neither is this value “sent to s". This value is actually sent to the expression that called “s(3)” as if “s(3)” was a function call. It is perhaps easier to remember the correct semantics by writing something like “finish s returning x+y".)

It is important to note that the instant molecule “s” will not be present in the soup after the reaction is finished. It does not make sense for “s” to remain in the soup. If a molecule remains in the soup after a reaction, it means that the molecule is going to be available for some later reaction without blocking its injecting call; but this is the behavior of an asynchronous molecule.

To summarize: Instant molecules are like functions except that they will block when their reactions are not available. If the relevant reaction never becomes available, an instant molecule will block forever. If several reactions are available for the instant molecule, one of these reactions will be selected randomly.
Defining several reactions together

Suppose we want to define several reactions involving the same molecules; for example, the two reactions a(x) & b(y) = c(x+y) and a(x) & c(y) = a(x+y) both involving a(x). JoCaml requires, in this case, that we should define these two reactions simultaneously, using the “or” keyword:

def a(x) & b(y) = c(x+y)
or  a(x) & c(y) = a(x+y)

These two reactions cannot occur in parallel on the same copy of the “a” molecule, because a reaction first consumes its input molecules and then produces the output molecules. If many copies of “a", “b", and “c” molecules are available in the soup, then each copy may be involved in one of these eactions. If only one copy of the “a” molecule is present then only one of these two reactions will start. This is the central feature of join calculus that replaces synchronizations, locks, semaphores, and other low-level features of traditional concurrent programming.

Keep in mind that these two definitions actually specify not only the two reactions but also the fact that “a", “b", and “c” are (asynchronous) molecules. This is because “a", “b", and “c” all occur on the left-hand side of the definitions. Whatever occurs on the left-hand side of a “def” becomes a newly defined symbol. Thus, these two lines of JoCaml will define, at once, two reactions and three molecules.

Why is it necessary to define these two reactions together? This is because JoCaml defines new molecules only when they occur on the left-hand side of a reaction. If we define the reaction def a(x) & b(y) = c(x+y) alone, JoCaml will see that “a” and “b” must be molecules, but JoCaml has no way of guessing what kind of molecule “c” must be. Maybe “c” is an instant molecule, maybe a slow molecule, or maybe an ordinary function? There is not enough information for JoCaml to compile this reaction definition alone. Here is what happens if we try this in JoCaml:

# def a(x) & b(y) = c(x+y);;
Error: Unbound value c

We could try to get around this problem: first define the reaction a(x) & c(y) = a(x+y) , which will define both “a” and “c” as molecules, and then define a(x) & b(y) = c(x+y). But this will not work as we wanted. The problem is that each “def” construct defines new molecules on the left-hand side, just like each “let” construct defines a new variable.

# def a(x) & c(y) = a(x+y);;
val a : int Join.chan = <abstr>
val c : int Join.chan = <abstr>
# def a(x) & b(y) = c(x+y);;
val a : int Join.chan = <abstr>
val b : int Join.chan = <abstr>

JoCaml tells us that new molecules “a” and “b” were defined after the second reaction. The definition of “a” from the first reaction is shadowed by the second definition of “a". This is quite similar to the behavior of the “let” construct: “let x=1;; let x=2;;” defines a new variable x each time. For this reason, we need to define the two reactions together, connecting them by the “or” construct.

We can also use the “and” keyword to define several reactions together, but only when the left-hand sides of all the reactions involve all different input molecules:

def a(x) & b(y) = c(x+y)
and c(x) = print_int x ; a(x)

In this case, it is necessary to define the two reactions together: the first reaction uses the “c” molecule as output, and we have to define it as a molecule. We could define the “c” reaction first, but it uses the “a” molecule as output. Defining a reaction “a(x) & b(y) = c(x+y)” alone will be an error since c(...) is undefined. JoCaml has no separate facility for defining a molecule without also defining a reaction. So these two reactions must be defined simultaneously. (This is similar to defining mutually recursive functions.)

We could define these two reactions using the “or” keyword as well. There is no difference in the resulting behavior. However, we can use the “or” / “and” keywords in order to make clear our intention of defining reactions on different input molecules or the same input molecules. The JoCaml compiler will refuse a program that defines reactions on the same input molecules using the keyword “and":

# def a(x) & b(y) = c(x+y)
  and a(x) & c(y) = a(x+y);;
Error: Variable a is bound several times in this matching

The error message talks about “matching". Actually, the reaction definitions are treated as pattern-matching operations. The standard OCaml facility for pattern matching is also available for defining reactions. For instance, the decorating values could have a variant type, a list, etc., and a reaction can require a particular matching value. An example with the Option type:

def a(Some x) & b(y) = b(x+y) or a(None) = 0

In this case, we are allowed to define two different reactions, and the reaction will be chosen according to the decorating value of the molecule “a".

In JoCaml, the most general form of a reaction definition is “def (R1 or R2 or R3 or ...) and (R4 or R5 or ...) and ...", where “R1", “R2", etc. are various reactions. All the reactions connected by the “and” keyword must use different input molecules. It is admissible to connect all reactions with the “or” keyword, but then we ignore the compiler's ability to verify that some reactions must have different input molecules.
Using molecules in OCaml expressions

JoCaml is a superset of OCaml and uses the existing OCaml type system. What are the OCaml types of the molecules?

A molecule has the syntactic form of a function call with a single argument, like “a(2)". Here “2” is the “decorating value” of the molecule. Molecules without decorating values are not allowed, so we need to use the empty value (), e.g. “b()". (As we said before, the parentheses are optional; we could write “a 2” instead of “a(2)". But I find it visually helpful to write parentheses with molecules.)

Now, we have two kinds of molecules: instant and slow. Their roles in the OCaml type system are quite different.

"Instant” molecules have the type of functions, returning a value. If “s(2)” is an instant molecule returning an integer then “s(2)” has the type int and so “s” has the type int->int.

However, the story becomes more complicated with “slow” (i.e. asynchronous) molecules. A slow molecule such as “b(2)” is not an OCaml value at all! Slow molecules cannot be used in ordinary OCaml expressions; they cannot be, say, stored in an array or passed as arguments to functions.

# def b(x) = print_int x; 0;;
val b : int Join.chan = <abstr>
# b(2);;
Characters 0-1:
b(2);;
^
Error: This expression is not a function; it cannot be applied
# b;;
- : int Join.chan = <abstr>

We see that the “b” itself is an ordinary OCaml value that has the type int Join.chan. But this type is not a function type, so “b(2)” is not a valid OCaml expression.

Thus, we can regard “b” as the “constructor” for slow molecules of the form “b(x)". The OCaml value “b” can be passed to functions, stored in an array, or even used as the “decorating value” of another molecule. For example, this code creates a “higher-order” slow molecule that injects two copies of a given other molecule into the soup:

# def a(b,x) = b(x-1) & b(x-2);;
val a : (int Join.chan * int) Join.chan = <abstr>

Here we are using a tuple as the decorating value of the molecule “a". The molecule “a” contains the constructor of the molecule “b” in its decorating value. Since the constructor “b” is available, the reaction can inject the molecule “b(x)” into the soup as its output.

In this way one can also implement a “continuation-passing” style, passing molecule constructors to reactions that will inject new molecules.

While molecule constructors are ordinary OCaml values and can be used in any expression, a fully constructed slow molecule like “a(2)” is not an OCaml value and can be used only within a “molecule expression". This is a new, special kind of expression introduced by the JoCaml system. A “molecule expression” describes a slow molecule (or, more generally, a set of slow molecules) that are being injected into the soup, together with result values of fast molecules. Therefore, a “molecule expression” may be used only where it is appropriate to describe newly injected molecules: either after spawn or on the right-hand side of a def. (Result values of fast molecules can be used only within a def.)

Note that spawn NNN is an ordinary OCaml expression that immediately returns the empty value (), while def is by itself not an expression but a binding construction similar to let. The right-hand side of a “def” is a “molecule expression” that describes at once the payload expression and the output molecules of a reaction.

A “molecule expression” can be:

the special empty molecule, “0"
a single slow molecule like “a(2)"
the reply ... to construction, describing the result value of an instant molecule
several molecule expressions separated by “&": a(2) & b(3)
an arbitrary OCaml expression (a “payload expression") that evaluates to a molecule: for example f(); g(); a(2) or if f() then a(2) else b(3) where “f” and “g” are ordinary OCaml functions. Of course, all OCaml features like let ... in, pattern matching, etc. can be used for building the OCaml expression, as long as its final value is a molecule.
a while loop or a for loop containing molecules, e.g. for i = 1 to 10 do a(i) done -- note that all these ten molecules will be injected into the soup simultaneously rather than sequentially.

Reactions and molecules can be defined locally, lexically scoped within a “molecule expression". For this, the construction def ... in  is used. For example:

spawn (def a(x)=b(x) in a(2));;

The chemical machine will, of course, know about all defined reactions and molecules, including “a". But the OCaml program will not see “a” outside this spawn expression.
Molecule definitions in local scopes

The let ... in and def ... in constructions are similar in that they define local names. However, there are special considerations for the “def ... in expr” construction. In brief: this construction defines local molecules, which are then invisible outside the expression “expr", but these molecules and their reactions continue to run in the soup. (The “chemical machine” always knows about all molecules and reactions, local or not.)

As an example, let us try to implement an “asynchronous counter": we will increment a value every time a reaction starts, but we want to make this reaction local. We want to define a slow molecule, “inc()", which asynchronously increments the counter. However, we want to protect the counter from modification by any other means.

Our first idea is to define the counter as a reference but to hide this reference using a “let” definition. The first try does not work:

# let i = ref 0 in def inc() = incr i; 0 ;;
Syntax error

The problem is that “let i = expr” works only when “expr” is an expression, but “def ...” is by itself not an expression. We can try to fix this:

# let i = ref 0 in def inc() = incr i; 0 in ();;
- : unit = ()
# spawn inc();;
Error: Unbound value inc

Now we don't have a syntax error, but the result is still disappointing: the molecule “inc” is invisible outside the expression where it was defined locally. So we can try to define it globally, creating a reference inside a reaction:

# def inc() = let i = ref 0 in incr i; print_int !i; 0;;
val inc : unit Join.chan = <abstr>
# spawn inc();;
- : unit = ()
#
1
spawn inc();;
#
1

This does not work because the reference is initialized each time the reaction starts; this is similar to defining a reference locally within a function body, a classical blunder.

Another approach is to abandon the idea of using references, and instead to hold the value of the counter within a molecule. This solution is then purely functional. We will also implement a fast molecule, “getcounter()", which returns the current value of the counter synchronously.

# def inc() & c(n) = c(n+1) 
  or
  getcounter() & c(n) = c(n) & reply n to getcounter;;
val inc : unit Join.chan = <abstr>
val getcounter : unit -> int = <fun>
val c : int Join.chan = <abstr>
# spawn c(0) & inc() & inc() & inc();;
- : unit = ()
# getcounter();;
- : int = 3

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

Now the molecule “c” is safely hidden. It is guaranteed that only one copy of “c” will ever be present in the soup: Since this molecule is locally defined and not visible outside the closure, the user of make_async_counter is unable to inject any more copies of “c". However, the user receives the molecule constructors “getcounter” and “inc", thus the user can inject these molecules and start their reactions (despite the fact that these molecules are locally defined, like “c"). Each invocation of make_async_counter will create new, fresh molecules “inc", “getcounter", and “c", so the user may create as many independent counters as desired.

This example shows how we can “hide” some molecules and yet use their reactions. A closure can define local reaction with several input molecules, inject some of these molecules initially, and return some (but not all) molecule constructors to the global scope outside of the closure.


How to understand the JoCaml manual and other literature

My terminology in this tutorial differs from that in the official JoCaml documentation and in other tutorials about the join calculus (here and here). I talk about the “chemical machine", “molecules", “reactions", “injecting molecules into the soup", “instant molecules", and “slow molecules". The official documentation, like most papers and tutorials about the join calculus, talks about “channels", “ports", “messages", and “processes". I do not use the official terminology because, in my view, it is much easier to learn the join calculus in the “chemical” metaphor than through the “channel/message/process” metaphor.

Here is a dictionary of terminology:

 Official manual	 This tutorial	 Examples
 channel, channel name, port name	 constructor of a molecule	 r : 'a Join.chan  (* OCaml value *)
 message	 a fully constructed molecule	 r(2)  (* not an OCaml value *)
 there is a message on channel r	 a molecule is present in the soup	 (* r(2) has been injected *)
 asynchronous channel/message	 slow molecule	 def r(x)  & a() = b(x)  & a()
 process	 reaction	 (* a thread that computes a reaction's function body *)
 join definition	 definition of a reaction	 def r(x) & s() = print_int x;  r(x +1)
 spawning a process (*)	 injecting molecules into the soup	 spawn r(2) & s()
 spawning a process (*)	 emitting an output molecule after reaction	 (* right-hand side of a “def” contains a molecule expression  for the output molecules *)
 synchronous channel/message	 instant molecule	 def f() & b(x) = reply x to f & b(x+1)
 sending a message on channel (*)	 injecting a molecule into the soup	 spawn r(2)

In the cases marked by the asterisk (*), the mapping of the official terminology to my terminology is not one-to-one. Let me try to clarify the meaning of the terms as they are used in the official manual.

The official manual says:

"Channels, or port names, are the main new primitive values of JoCaml.

Users can create new channels with a new kind of def binding, which should not be confused with the ordinary value let binding. The right hand-side of the definition of a channel a is a process that will be spawned whenever some message is sent on a. Additionally, the contents of messages received on a are bound to formal parameters. 

Processes are the new core syntactic class of JoCaml. The most basic process sends a message on an asynchronous channel. Since only declarations and expressions are allowed at top-level, processes are turned into expressions by “spawning” them: they are introduced by the keyword “spawn”."

In my terminology, “channels” or “port names” are called “constructors for molecules", and “processes” are called “reactions". “Sending message on a channel r” means injecting a molecule, such as r(2), into the soup. So “sending messages on a channel” does not imply that we have a queue of messages on the channel, or that the messages are retrieved one by one. Molecules are not queued in any particular order, -- they are simply injected into the soup all together. Reactions can start if all the required molecules are present in the soup, or “when there are messages on the required channels".

The “contents of messages” mentioned in the manual is what I call the “decoration value” of the molecule. So, when the manual says that “we send a message with content 2 on channel r", I say “we inject the molecule r(2) into the soup".

When the manual says “process is spawned", I say “reaction is started". A “running process” is the running computation of a reaction's payload function. In my view, it is unfortunate that the keyword
"spawn” has been chosen for the operation of injecting the molecules. Evaluating a spawn expression will not necessarily start any computations or spawn any new processes or threads. Molecules by themselves cannot perform calculations; only after a reaction between (perhaps several) molecules has started, the chemical machine will perform a calculation associated with that reaction. 

Consider the reaction definition

def a(x) & b(y) = print_int x+y; 0

This is not a definition of the “channel a” or a “channel b” alone. This line of JoCaml code defines at once the molecule constructors “a” and “b” and a reaction between the two molecules. Note that the reaction will start only when both molecules “a” and “b” are present in the soup. If “a message arrives on channel a", that is, if only the “a(x)” molecule is present but not “b(y)", the reaction will not start. From the user's perspective, the channel is not “waiting” for messages to “arrive". Instead, the reaction itself is waiting until some “a(x)” and “b(y)” molecules are injected into the soup, either  as products of another reaction or by an explicit “spawn".

There is one (perhaps accidental) aspect of JoCaml that is better described by the “channel” metaphor. Namely, we are not allowed to define a reaction with several copies of the same molecule, such as a(x) & a(y) & a(z) = b(x+y+z). A straightforward application of the “chemical” intuition would allow this kind of reaction: in real chemistry, such reactions are, of course, commonplace. Now, the “channel/message” metaphor implies that there can be only one channel named “a". We may send three messages on this channel, but these three messages cannot be processed all at once. This analogy suggests that reactions with several copies of the same molecule ("port") are not allowed.

(Actually, the reason such reactions are not allowed is technical: the implementation of the chemical machine is simpler and more efficient if only one copy of each molecule is allowed to participate in a reaction. In principle, one could implement a more complicated chemical machine that permits such reactions. However, this limitation is not essential and can be easily circumvented by defining extra molecules, as we will see later.)
First steps in concurrent programming

Let us see how the “abstract chemistry” needs to be designed if we would like to perform some common tasks of concurrent programming. We will think in terms of “molecules” and “reactions” rather than in terms of “threads", “processes", “channels", or “messages". In this way we will learn how to use the join calculus for designing concurrent software.

The join calculus has only two primitives:

defining a chemical reaction between molecules, -- keyword def,
injecting of molecules into the soup, -- keyword spawn.

Computations are imposed as “payload expressions” on running reactions. Thus, we organize the concurrent computation by defining the possible reactions and injecting the appropriate molecules into the soup.

The JoCaml notation for reactions is actually so concise that one can design the reactions directly by writing executable code!

(To keep things clear in your head, think “define reaction” instead of “def” and “inject molecules” instead of “spawn". Keep in mind that a “def” defines a reaction together with  new input molecules. The “spawn” is not necessarily “spawning” any background threads; a “spawn” merely adds molecules to the soup, which may or may not start any reactions.)

Some limitations of JoCaml


While designing the “abstract chemistry", we need to keep in mind certain limitations of the JoCaml system:

We cannot detect the absence of a given slow molecule, say a(1), in the soup. This seems to be a genuine limitation of join calculus.

It seems that this limitation cannot be lifted by any clever combinations of slow and instant molecules; perhaps this can be even proved formally, but I haven't tried learning the formal tools for that. I just tried to implement this but could not find appropriate reactions. For instance, we could try injecting a fast molecule that reacts with “a". If “a” is absent, the injection will block. So the absence of “a” in the soup can be translated into blocking of a function call. But it is impossible, within any programming language, to detect whether a function call has been blocked, because the function call is by definition a blocking call! All we can do is to detect whether the function call has returned within a given time, but here we would like to return instantly with the information that “a” is present or absent. 

Suppose we define a reaction using the molecule “a", say “def a() = ...". If this reaction does not begin at a certain time, we cannot conclude that “a” is absent in the soup at that time! We can prepare another reaction to act like a “timeout", so that we can detect the fact that the “def a() = ...” did not start within, say, 5 seconds. But this also does not mean that the molecule “a()” was absent from the soup during these 5 seconds. It could be that “a()” was present but got involved in some other reactions and was consumed by them, or that “a()” was present but the computer's CPU was simply so busy that the “def a() = ...” reaction could not yet start and is still waiting in the queue.

The chemical machine could generate a special slow molecule, say, stalled(), to be injected when the chemical machine finds that currently no further reactions are possible. One could perhaps easily implement this extension to the chemical machine, which might be sometimes useful. But this is a crude mechanism, and we will still be unable to detect the absence of a particular slow molecule at a given time.

Another solution would be to introduce “inhibiting” conditions on reactions: a reaction can start when molecules “a", “b", “c” are present but no molecule “d” is present. However, I am not sure that this extension of the join calculus would be useful. The solution based on a “timeout” appears to be sufficient in practice.

Chemical soups running at different JoCaml instances are completely separate and cannot be “pooled".

What we would like to do is to connect many chemical machines together, running perhaps on different computers, and to pool their individual “soups” into one large “common soup". Our program will then be able to inject lots of molecules into the common pool and thus organize a massively parallel, distributed computation, without worrying about which CPU computes what reaction. However, the JoCaml system can only inject molecules to a specific remote soup. So, in order to organize a distributed computation, we need to split the tasks explicitly between the participating soups. The organization and supervision of distributed computations, the maintenance of connections between machines, the handling of disconnections - all this remains the responsibility of the JoCaml programmer and cannot be handled automatically.

JoCaml uses the same single-threaded garbage collector as OCaml. Thus, JoCaml cannot automatically use all cores on a multicore machine. One can run several instances of a JoCaml program on the same computer, and the OS will perhaps assign these processes to different cores. But then the programmer must explicitly distribute the computation between individual JoCaml instances.

Acknowledging these limitations, and hoping that (especially) the last two problems are technical and will be solved in the future, let us proceed to examine various common tasks of concurrent programming from the “chemical” perspective.

Background jobs

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

The only way to wait for something is by arranging a reaction that does not start until a certain molecule is present. Thus, we need a molecule that is absent from the soup until all our jobs are finished. Let us call this molecule “all_done()". We could define a reaction that notifies somebody of the completion of all jobs:

def all_done() = print_string “all done\n"; 0

Now, who will inject “all_done()” into the soup? 

When one job is finished, the job reaction cannot know whether other jobs are still running. Also, the chemical machine cannot perform a direct test for the absence of a molecule, or a direct test for the presence of a certain number of identical molecules. Thus, we cannot have a reaction that generates “all_done()” when all “job” molecules are consumed.

The only solution is to count the finished jobs by hand, knowing in advance how many jobs were started. So we need a reaction that knows how many jobs were started and generates “all_done()” when no jobs remain unfinished, but not before. Since we cannot have reactions that involve several instances of the same molecule, we need to hold the number of unfinished jobs as a decoration value on some molecule. Let us call this molecule “remains(n)". Each job can consume “remains(n)” when done and inject a new remains(n-1) molecule. If nothing remains, we can inject all_done() into the soup. 

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

This solution is flawed in several ways. At the end of the calculation shown above, the soup still contains four job(...) molecules. However, there are no remains(n) molecules, so no further reactions are actually running. If we want to keep the jobs running even after the all_done() molecule was generated, we can modify the definition of the reaction so that the remains(n) molecule is always kept in the soup. Nothing prevents us from injecting several remains(n) molecules into the soup at the same time, with different values of “n". We could prohibit this by encapsulating the remains(n) molecules, so that the user cannot make a mistake when injecting the molecules.

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


Now let us figure out the implementation of these examples in join calculus. We will be using the purely “chemical” approach to concurrent computation. We will never say the words “semaphore", “thread", “deadlock", “mutex", or “synchronize". Instead, we will talk about molecules and reactions. Our goal is to see what kind of tricks and patterns emerge from this paradigm.
Other limitations of join calculus

In order to implement a concurrent computation of many functions, one might want to define a separate molecule for each array element. However, this is not possible in the join calculus. The join calculus has these limitations, beyond those I described before:

We cannot define a computed set of input molecules. For instance, if we wanted to define a reaction with 1000 input molecules, we cannot write the input molecules as “a_n for n = 0, ..., 999". We would have to define the molecules named a000, a001, ..., a999 explicitly and statically (i.e. at compile time) in the JoCaml program. 
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
