<link href="{{ site.github.url }}/tables.css" rel="stylesheet">

# The chemical machine paradigm

`JoinRun`/`Chymyst` adopts an unusual approach to declarative concurrent programming.
This approach is purely functional but does not use threads, actors, futures, or monads.
It is easiest to understand this approach by using the **chemical machine** metaphor.

## Simulation of chemical reactions

Imagine that we have a large tank of water where many different chemical substances are dissolved.
Different chemical reactions are possible in this “chemical soup”, as various molecules come together and react, producing other molecules.
Reactions could start at the same time (i.e. concurrently) in different regions of the soup.

Chemical reactions are written like this:

HCl + NaOH ⇒ NaCl + H<sub>2</sub>O

A molecule of hydrochloric acid (HCl) reacts with a molecule of sodium hydroxide (NaOH) and yields a molecule of salt (NaCl) and a molecule of water (H<sub>2</sub>O).

Since we are going to simulate reactions in a computer, we make the “chemistry” completely arbitrary.
We can define molecules of any sort, and we can postulate arbitrary reactions between them.

For instance, we can postulate that there exist three sorts of molecules called `a`, `b`, `c`, and that they can react as follows:

`a + b ⇒ a`

`a + c ⇒` [_nothing_]


![Reaction diagram a + b => a, a + c => ...](http://chymyst.github.io/joinrun-scala/reactions1.svg)

Of course, real-life chemistry does not allow a molecule to disappear without producing any other molecules.
But our chemistry is purely imaginary, and so the programmer is free to postulate arbitrary chemical laws.

To develop the chemical analogy further, we allow the chemical soup to hold many copies of each molecule.
For example, the soup can contain five hundred copies of `a` and three hundred copies of `b`, and so on.
We also assume that we can inject any molecule into the soup at any time.

It is not difficult to implement a simulator for the chemical behavior we just described.
Having specified the list of chemical laws and injected some initial molecules into the soup, we start the simulation.
The chemical machine will run all the reactions that are allowed by the chemical laws.

We will say that in a reaction such as

`a + b + c ⇒ d + e`

the **input molecules** are  `a`, `b`, and `c`, and the **output molecules** are `d` and `e`.
A reaction can have one or more input molecules, and zero or more output molecules.

Once a reaction starts, the input molecules instantaneously disappear from the soup (they are “consumed” by the reaction), and then the output molecules are injected into the soup.

The simulator will start many reactions concurrently whenever their input molecules are available.

## Concurrent computations on the chemical machine

The chemical machine is implemented by the runtime engine of `JoinRun`.
Now, rather than merely watch as reactions happen, we are going to use this engine for running actual concurrent programs.

To this end, we are going to modify the chemical machine as follows:

1. Each molecule in the soup is required to _carry a value_. Molecule values are strongly typed: A molecule of a given sort (such as `a` or `b`) can only carry values of some fixed type (such as `Boolean` or `String`).

2. Since molecules must carry values, we now need to specify a value of the correct type when we inject a new molecule into the soup.

3. For the same reason, reactions that produce new molecules will now need to put values on each of the output molecules. These output values must be _functions of the input values_, -- that is, of the values carried by the input molecules consumed by this reaction. Therefore, each reaction will now need to carry a Scala expression (called the **reaction body**) that will compute the new output values and inject the output molecules.

In this way, the chemical machine can be programmed to run arbitrary computations.

We will use syntax such as `b(123)` to denote molecule values.
In a chemical reaction, the syntax `b(123)` means that the molecule `b` carries an integer value `123`.
Molecules to the left-hand side of the arrow are input molecules of the reaction; molecules on the right-hand side are output molecules.

A typical reaction (equipped with molecule values and a reaction body) looks like this in pseudocode syntax:

```scala
a(x) + b(y) ⇒ a(z)
where z = computeZ(x,y) // -- reaction body

```

In this example, the reaction's input molecules are `a(x)` and `b(y)`; that is, the input molecules have chemical designations `a` and `b` and carry values `x` and `y` respectively.

The reaction body is an expression that captures the values `x` and `y` from the input molecules.
The reaction body computes a value `z` out of `x` and `y` using the function `computeZ` (or any other code as needed).
The newly computed value `z` is placed onto the output molecule `a`, which is injected back into the soup.

Another example of reaction is

```scala
a(x) + c(y) ⇒ println(x+y) // -- reaction body with no output molecules

```

This reaction consumes the molecules `a` and `c` but does not inject any output molecules.
The only result of running the reaction is the side-effect of printing the number `x+y`.

![Reaction diagram a(x) + b(y) => a(z), a(x) + c(y) => ...](http://chymyst.github.io/joinrun-scala/reactions2.svg)

The computations performed by the chemical machine are _automatically concurrent_:
Whenever input molecules are available in the soup, the runtime engine will start a reaction that consumes these input molecules.
If many copies of input molecules are available, the runtime engine could start several reactions concurrently.
(The runtime engine can decide how many reactions to run depending on system load and the number of available cores.)

The reaction body can be a _pure function_ that computes output values solely from the input values it receives from its input molecules.
If the reaction body is a pure function, it is completely safe (free of contention or race conditions) to execute concurrently several copies of the same reaction as different processes.
Each process will consume its own input molecules and will work with its own input values.
This is how the chemical machine achieves safe and automatic concurrency in a purely functional way.

## The syntax of `JoinRun`

So far, we have been using a kind of chemistry-resembling pseudocode to illustrate the structure of reactions in `JoinRun`.
This pseudocode was designed to prepare us for the actual syntax of `JoinRun`, which is only a little more verbose:

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

// declare the molecule types
val a = m[Int] // a(...) will be a molecule with an integer value
val b = m[Int] // ditto for b(...)

// declare the reaction site and the available reaction(s)
site(
  run { case a(x) + b(y) =>
    val z = computeZ(x,y)
    a(z)
  }
)

```

The helper functions `m`, `site`, and `run` are defined in the `JoinRun` library.

The `site` call declares a **reaction site**, which can be visualized as a place where molecules gather and wait for their reaction partners.
We will talk later in more detail about reaction sites.

## Example: Concurrent counter

We already know enough to start implementing our first concurrent program!

The task at hand is to maintain a counter with an integer value, which can be incremented or decremented by non-blocking concurrent requests.
(For example, we would like to be able to increment and decrement the counter from different processes running at the same time.)

To implement this in `JoinRun`, we begin by deciding which molecules we will need to define.
Since there is no global state, it is clear that the integer value of the counter needs to be carried by a molecule.
Let's call this molecule `counter` and specify that it carries an integer value:

```scala
val counter = m[Int]

```

The increment and decrement requests must be represented by other molecules.
Let us call them `incr` and `decr`.
These molecules do not need to carry values, so we will define the `Unit` type as their value type:

```scala
val incr = m[Unit]
val decr = m[Unit]

```

Now we need to define the chemical reactions.
The reactions must be such that the counter's value is incremented when we inject the `incr` molecule, and decremented when we inject the `decr` molecule.

So, it looks like we will need two reactions. Let us write a reaction site:

```scala
site(
  run { case counter(n) + incr(_) => counter(n+1) },
  run { case counter(n) + decr(_) => counter(n-1) }
)

```

Each reaction says that the new value of the counter (either `n+1` or `n-1`) will be carried by the new counter molecule injected by the reaction's body.
The previous counter molecule (with its old value `n`) will be consumed by the reactions.
The `incr` and `decr` molecules will be likewise consumed.

![Reaction diagram counter(n) + incr => counter(n+1) etc.](http://chymyst.github.io/joinrun-scala/counter-incr-decr.svg)

In `JoinRun`, a reaction site is created by the call to `site(...)`, which can contain one or several reactions.
Why did we write the two reactions in one site?

Note that both reactions `counter + incr => ...` and `counter + decr => ...` need the molecule `counter` as part of their input.
In order for any of these reactions to start, the molecule `counter` needs to be present at some reaction site,
and thus the `incr` and `decr` molecules must be present at the _same_ reaction site (otherwise they won't get together with `counter` to start a reaction).
For this reason, both reactions need to be defined _together_ in a single reaction site.

After defining the molecules and their reactions, we can start injecting new molecules into the soup:

```scala
counter(100)
incr() // now the soup has counter(101)
decr() // now the soup again has counter(100)
decr() + decr() // now the soup has counter(98)

```

The syntax `decr() + decr()` is a shorthand for injecting several molecules at once.

Note that `counter`, `incr` and `decr` are local values, which we are now calling as functions, e.g. `incr()` and `decr()`, to actually inject the molecules.
For this reason, we call `counter`, `incr`, and `decr` **molecule injectors**. 

It could happen that we are injecting `incr()` and `decr()` molecules too quickly for reactions to start.
This will result in many instances of `incr()` or `decr()` molecules being present in the soup, waiting to be consumed.
Is this a problem?

Recall that when the chemical machine starts a reaction, all input molecules are consumed first, and only then the reaction body is evaluated.
In our case, each reaction needs to consume a `counter` molecule, but only one instance of `counter` molecule is initially present in the soup.
For this reason, the chemical machine will need to choose whether the single `counter` molecule will react with an `incr` or a `decr` molecule.
Only when the incrementing or the decrementing calculation is finished, the new instance of the `counter` molecule (with the updated integer value) will be injected into the soup.
This automatically prevents race conditions with the counter: There is no possibility of updating the counter value simultaneously from different reactions.

## Tracing the output

The code shown above will not print any output, so it is perhaps instructive to put some print statements into the reaction bodies.

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

// declare the molecule injectors and the value types
val counter = m[Int]
val incr = m[Unit]
val decr = m[Unit]

// helper function to be used in reactions
def printAndInject(x: Int) = {
  println(s"new value is $x")
  counter(x)
}

// write the reaction site
site(
  run { case counter(n) + decr(_) => printAndInject(n-1) }
  run { case counter(n) + incr(_) => printAndInject(n+1) },
)

counter(100)
incr() // prints “new value is 101"
decr() // prints “new value is 100"
decr() + decr() // prints “new value is 99” and then “new value is 98"

```

## Debugging

`JoinRun` has some debugging facilities to help the programmer verify that the chemistry works as intended.

### Logging the contents of the soup

For debugging purposes, it is useful to see what molecules are currently present in the soup and waiting to react with other molecules, at a given reaction site.
This is achieved by calling the `logSoup` method on any of the molecule injectors.
This method will return a string showing the molecules that are currently present in the soup at that RS.
The `logSoup` output will also show the values carried by each molecule.

In our example, all three molecules `counter`, `incr`, and `decr` are declared as inputs at our reaction site, so we could use any of the injectors, say `decr`, to log the soup contents:

```
> println(decr.logSoup)
Site{counter + decr => ...; counter + incr => ...}
Molecules: counter(98)

```

The debug output contains two pieces of information:

- The RS which is being logged: `Site{counter + decr => ...; counter + incr => ...}`
Note that the RS is identified by the reactions that are declared in it.
The reactions are shown in a shorthand notation, which only mentions the input molecules.

- The list of molecules that are currently waiting in the soup at that RS, namely `Molecules: counter(98)`.
In this example, there is presently only one copy of the `counter` molecule, carrying the value `98`.

Also note that the debug output is limited to the molecules that are declared as input at that RS.
We say that these molecules are **bound** to that RS.
The RS will look at the presence or absence of these molecules when it decides which reactions to start.

### Molecule names

A perceptive reader will ask at this point:
How did the program know the names `counter`, `decr`, and `incr` when we called `logSoup`?
These are names of local variables we defined using `val counter = m[Int]` and so on.
Ordinarily, Scala code does not have access to these names.

The magic is actually performed by the method `m`, which is a macro that looks up the name of the enclosing variable.
The same effect can be achieved without macros at the cost of more boilerplate:

```scala
val counter = new M[Int]("counter")
// completely equivalent to `val counter = m[Int]`

```

Descriptive names of molecules are very useful for visualizing the reactions, as well as for debugging and logging.
In this tutorial, we will always use macros to define molecules.

### Logging the flow of reactions and molecules

To get asynchronous, real-time logging information about the molecules being consumed or injected and about the reactions being started, the user can set the logging level on the RS.
This is done by calling `setLogLevel` on any molecule injector bound to that RS.

```scala
counter.setLogLevel(2)

```

After this, verbosity level 2 is set on all reactions involving the RS to which `counter` is bound.
This might result in a large printout if many reactions are happening.
So this facility should be used only for debugging or testing.

## Common errors

### Error: Injecting molecules without defined reactions

For each molecule, there must exist a single reaction site (RS) to which this molecule is bound -- that is, the RS where this molecule is consumed as input molecule by some reactions.
(See [Reaction Sites](joinrun.md#reaction-sites) for a more detailed discussion.)

It is an error to inject a molecule that is not yet defined as input molecule at any RS (i.e. not yet bound to any RS).

```scala
val x = m[Int]
x(100) // java.lang.Exception: Molecule x is not bound to any reaction site

```

The same error will occur if such injection is attempted inside a reaction body, or if we call `logSoup` on the molecule injector.

The correct way of using `JoinRun` is first to define molecules, then to create a RS where these molecules are used as inputs for reactions, and only then to start injecting these molecules.

The method `isBound` can be used to determine at run time whether a molecule has been already bound to a reaction site:

```scala
val x = m[Int]
x.isBound // returns `false`

site( run { case x(2) =>  } )

x.isBound // returns `true`

```

### Error: Redefining input molecules

It is also an error to write a reaction whose input molecule was already used as input at another RS.

```scala
val x = m[Int]
val a = m[Unit]
val b = m[Unit]

site( run { case x(n) + a(_) => println(s"have x($n) + a") } ) // OK, "x" is now bound to this RS.

site( run { case x(n) + b(_) => println(s"have x($n) + b") } )
// java.lang.Exception: Molecule x cannot be used as input since it is already bound to Site{a + x => ...}

```

Correct use of `JoinRun` requires that we put these two reactions together into _one_ reaction site:
 
```scala
val x = m[Int]
val a = m[Unit]
val b = m[Unit]

site(
  run { case x(n) + a(_) => println(s"have x($n) + a") },
  run { case x(n) + b(_) => println(s"have x($n) + b") }
) // OK

``` 

More generally, all reactions that share any input molecules must be defined together in a single RS.
However, reactions that use a certain molecule only as an output molecule can be (and should be) written in another RS.
Here is an example where we define one RS that computes a result and sends it on a molecule called `show`, which is bound to another RS:

```scala
val show = m[Int]
// reaction site where the “show” molecule is an input molecule
site( run { case show(x) => println(s"") })

val start = m[Unit]
// reaction site where the “show” molecule is an output molecule (but not an input molecule)
site(
  run { case start(_) => val res = compute(...); show(res) }
)

``` 

### Error: Nonlinear pattern

`JoinRun` also requires that all input molecules for a reaction should be of different chemical sorts.
It is not allowed to have a reaction with repeated input molecules, e.g. of the form `a + a => ...` where the molecule of sort `a` is repeated.
An input molecule pattern with a repeated molecule is called a “nonlinear pattern”.

```scala
val x = m[Int]
site(run { case x(n1) + x(n2) =>  })
// java.lang.Exception: Nonlinear pattern: x used twice

``` 

Sometimes it appears that repeating input molecules is the most natural way of expressing the desired behavior of certain concurrent programs.
However, I believe it is always possible to introduce some new auxiliary molecules and to rewrite the chemistry so that input molecules are not repeated, while the resulting computations give the same results.
This limitation could be lifted in a later version of `JoinRun` if it proves useful to do so.

## Order of reactions and nondeterminism

When a reaction site has enough waiting molecules for several different reactions to start, the runtime engine will choose the reaction at random, giving each candidate reaction an equal chance of starting.

TODO: Drawing of several possible reactions at a reaction site

Similarly, when there are several copies of the same molecule that can be consumed as input by a reaction, the runtime engine will make a choice of which copy of the molecule to consume.

TODO: Drawing of several possible input molecules for a reaction

Currently, `JoinRun` will _not_ fully randomize the input molecules but make an implementation-dependent choice.
A truly random selection of input molecules may be implemented in the future.

Importantly, it is _not possible_ to assign priorities to reactions or to molecules.
The chemical machine ignores the order in which reactions are listed in the `site(...)` call, as well as the order of molecules in the input list of each reaction.
Just for the purposes of debugging, molecules will be printed in alphabetical order of names, and reactions will be printed in an unspecified but fixed order.

The result is that the order in which reactions will start is non-deterministic and unknown.

If the priority of certain reactions is important for a particular application, it is the programmer's task to design the chemistry in such a way that those reactions start in the desired order.
This is always possible by using auxiliary molecules and/or guard conditions.

In fact, a facility for assigning priority to molecules or reactions would be self-defeating.
It will only give the programmer _an illusion of control_ over the order of reactions, while actually introducing subtle nondeterministic behavior.

To illustrate this on an example, suppose we would like to compute the sum of a bunch of numbers in a concurrent way.
We expect to receive many molecules `data(x)` with integer values `x`,
and we need to compute and print the final sum value when no more `data(...)` molecules are present.

Here is an (incorrect) attempt to design the chemistry for this program:

```scala
val data = m[Int]
val sum = m[Int]
site (
  run { case data(x) + sum(y) => sum(x+y) }, // We really want the first reaction to be high priority
   
  run { case sum(x) => println(s"sum = $x") }  // and run the second one only after all `data` molecules are gone.
)
data(5) + data(10) + data(150)
sum(0) // expect "sum = 165"

```

Our intention was to run only the first reaction and to ignore the second reaction as long as `data(...)` molecules are available in the soup.
The chemical machine does not actually allow us to assign a higher priority to the first reaction.
But, if we were able to do that, what would be the result?

In reality, the `data(...)` molecules are going to be injected concurrently at unpredictable times.
(For instance, they could be injected by several other reactions that run concurrently.)
Then it could happen that the `data(...)` molecules are injected somewhat more slowly than we are consuming them at our reaction site.
If that happens, there will be a brief interval of time when no `data(...)` molecules are in the soup (although other reactions are perhaps about to inject some more of them).
The chemical machine will then run the second reaction, consume the `sum(...)` molecule and print the result, signalling (incorrectly) that the computation is finished.
Perhaps this failure will _rarely_ happen and will not show up in our unit tests, but at some point it is definitely going to happen in production.

This kind of nondeterminism is the prime reason concurrency is widely regarded as a hard programming problem.

`JoinRun` will actually reject our attempted program and print an error message before running anything, immediately after we define the reaction site:

```scala
val data = m[Int]
val sum = m[Int]
site (
  run { case data(x) + sum(y) => sum(x+y) },
  run { case sum(x) => println(s"sum = $x") }
)

Exception: In Site{data + sum => ...; sum => ...}: Unavoidable nondeterminism: reaction data + sum => ... is shadowed by sum => ...

```

The error message means that the reaction `sum => ...` will sometimes prevent `data + sum => ...` from running,
and that the programmer has no control over this nondeterminism.

The correct way of implementing this task is to keep track of how many `data(...)` molecules we already consumed,
and to print the final reasult only when we reach the total expected number of the `data(...)` molecules.
Since reactions do not have mutable state, the information about the remaining `data(...)` molecules has to be carried on the `sum(...)` molecule.
So, we will define the `sum(...)` molecule with type `(Int,Int)`, where the second integer will be the number of `data(...)` molecules that remain to be consumed.

The reaction `data + sum` should proceed only when we know that some `data(...)` molecules are still remaining.
Otherwise, `sum(...)` should start its own reaction and print the final result. 

```scala
val data = m[Int]
val sum = m[(Int, Int)]
site (
  run { case data(x) + sum((y, remaining)) if remaining > 0 => sum((x+y, remaining - 1)) },
  run { case sum((x, 0)) => println(s"sum = $x") }
)
data(5) + data(10) + data(150) // inject three `data` molecules
sum((0, 3)) // expect "sum = 165" printed

```

Now the chemistry is fully deterministic, and no priority needs to be explicitly assigned.

The chemical machine forces the programmer to design the chemistry in such a way that
the order of running reactions is completely determined by the data on the available molecules.

Another way of maintaining determinism is to avoid writing reactions that might shadow each other.
Here is equivalent code with just one reaction:

```scala
val data = m[Int]
val sum = m[(Int, Int)]
site (
  run { case data(x) + sum((y, remaining)) =>
      val newSum = x + y
      if (remaining == 1)  println(s"sum = $newSum")
      else  sum((newSum, remaining-1)) 
     }
)
data(5) + data(10) + data(150) // inject three `data` molecules
sum((0, 3)) // expect "sum = 165" printed

```


## Summary so far

The chemical machine requires for its description:

- a list of defined molecules, together with their types;
- a list of reactions involving these molecules as inputs, together with reaction bodies.

These definitions comprise the chemistry of a concurrent program.

The user can define one or more reaction sites, each having one or more reactions.
We imagine a reaction site to be a virtual place where molecules arrive and wait for other molecules, in order to start reactions with them. 
For each molecule, there is only one reaction site at which it should go in order to be consumed by some reactions.

For this reason, all reactions that have a common _input_ molecule must be declared at the same reaction site.
Different reaction sites must have no input molecules in common.

In this way, we can specify an arbitrarily complicated system of interacting concurrent processes by defining molecules, reaction sites, and the reactions at each site.

After defining the molecules and specifying the reactions, the user can inject some initial molecules into the soup.
The chemical machine will start running all the possible reactions and keeping track of the molecules consumed by reactions or injected into the soup.

Let us recapitulate the core ideas of the chemical paradigm of concurrency:

In the chemical machine, there is no mutable global state; all data is immutable and must be carried by some molecules.

Each reaction specifies its input molecules, and in this way determines all the data necessary for computing the reaction body.
The chemical machine will automatically make this data available, since a reaction can start only when all its input molecules are present in the soup.

Each reaction also specifies a reaction body, which is a Scala expression that evaluates to `Any`. (However, the result value of that expression is discarded.)
The reaction body can perform arbitrary computations using the input molecule values.
It can also inject new molecules into the soup.
Injecting a molecule is a side effect of calling a molecule injector, which can be called at any time within a reaction body or in any other code.

Up to this side effect, the reaction body can be a pure function, if it only depends on the input data of the reaction.
In this case, many copies of the reaction can be safely executed concurrently if many sets of input molecules are available.
Also, the reaction can be safely and automatically restarted in the case of a transient failure
by simply injecting the input molecules again.

The chemical laws fully specify which computations need to be performed for the data on the given molecules.
Whenever multiple sets of data are available, the corresponding computations will be performed concurrently.

### Chemical designations of molecules vs. molecule names vs. local variable names 

Each molecule has a specific chemical designation, such as `sum`, `counter`, and so on.
These chemical designations are not actually strings `"sum"` or `"counter"`.
(The names of the local variables and the molecule names are chosen purely for convenience.)

We could define a local alias for a molecule injector, for example like this:

```scala
val counter = m[Int]
val q = counter

```

This code will copy the molecule injector `counter` into another local value `q`.
However, this does not change the chemical designation of the molecule.
The injector `q` will inject the same molecules as `counter`; that is, molecules injected with `q(...)` will react in the same way and in the same reactions as molecules injected with `counter(...)`.

The chemical designation of the molecule specifies two aspects of the concurrent program:

- the chemical designations of other input molecules (besides this one) required to start a reaction;
- the computation to be performed when all the required input molecules are available.

# Example: Declarative solution for “dining philosophers"

The ["dining philosophers problem"](https://en.wikipedia.org/wiki/Dining_philosophers_problem) is to run a simulation of five philosophers who take turns eating and thinking.
Each philosopher needs two forks to start eating, and every pair of neighbor philosophers shares a fork.

![Five dining philosophers](An_illustration_of_the_dining_philosophers_problem.png | width=400)

The simplest solution of the “dining philosophers” problem is achieved using a molecule for each fork and two molecules per philosopher: one representing a thinking philosopher and the other representing a hungry philosopher.

A “thinking philosopher” molecule (`t1`, `t2`, ..., `t5`) causes a reaction in which the process is paused for a random time and then the “hungry philosopher” molecule is injected.
A “hungry philosopher” molecule (`h1`, ..., `h5`) needs to react with _two_ neighbor “fork” molecules. The reaction process is paused for a random time, and then the “thinking philosopher” molecule is injected together with the two “fork” molecules.

The complete code is shown here:

```scala
 /**
 * Random wait. Also, print the name of the molecule.
 */
def rw(m: Molecule): Unit = {
  println(m.toString)
  Thread.sleep(scala.util.Random.nextInt(20))
}

val h1 = new M[Int]("Aristotle is eating")
val h2 = new M[Int]("Kant is eating")
val h3 = new M[Int]("Marx is eating")
val h4 = new M[Int]("Russell is eating")
val h5 = new M[Int]("Spinoza is eating")
val t1 = new M[Int]("Aristotle is thinking")
val t2 = new M[Int]("Kant is thinking")
val t3 = new M[Int]("Marx is thinking")
val t4 = new M[Int]("Russell is thinking")
val t5 = new M[Int]("Spinoza is thinking")
val f12 = new M[Unit]("Fork between 1 and 2")
val f23 = new M[Unit]("Fork between 2 and 3")
val f34 = new M[Unit]("Fork between 3 and 4")
val f45 = new M[Unit]("Fork between 4 and 5")
val f51 = new M[Unit]("Fork between 5 and 1")

site (
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
// inject molecules representing the initial state:
t1() + t2() + t3() + t4() + t5()
f12() + f23() + f34() + f45() + f51()
// Now reactions will start and print to the console.

```

Note that an `h + f + f` reaction will consume a “hungry philosopher” molecule and two “fork” molecules, so these three molecules will not be present in the soup during the time interval taken by the `h + f + f` reaction.
Thus, neighbor philosophers will not be able to start eating until the two “fork” molecules are returned to the soup by that reaction.
The decision of which philosophers start eating will be made randomly, and there will never be a deadlock.

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

It is interesting to note that this example code is fully declarative: it describes what the “dining philosophers” simulation must do (but not how to do it),
and the code is quite close to the English-language description of the problem.

