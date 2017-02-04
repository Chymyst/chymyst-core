<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# The chemical machine paradigm

`Chymyst` adopts an unusual approach to declarative concurrent programming.
This approach is purely functional but does not use threads, actors, futures, or monads.
Instead, concurrent computations are performed by a special runtime engine that simulates chemical reactions.
This approach can be more easily understood by first considering the **chemical machine** metaphor.

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


![Reaction diagram a + b => a, a + c => ...](https://chymyst.github.io/joinrun-scala/reactions1.svg)

Of course, real-life chemistry does not allow a molecule to disappear without producing any other molecules.
But our chemistry is purely imaginary, and so the programmer is free to postulate arbitrary chemical laws.

To develop the chemical analogy further, we allow the chemical soup to hold many copies of each molecule.
For example, the soup can contain five hundred copies of `a` and three hundred copies of `b`, and so on.
We also assume that we can emit any molecule into the soup at any time.

It is not difficult to implement a simulator for the chemical behavior we just described.
Having specified the list of chemical laws and emitted some initial molecules into the soup, we start the simulation.
The chemical machine will run all the reactions that are allowed by the chemical laws.

We will say that in a reaction such as

`a + b + c ⇒ d + e`

the **input molecules** are  `a`, `b`, and `c`, and the **output molecules** are `d` and `e`.
A reaction can have one or more input molecules, and zero or more output molecules.

Once a reaction starts, the input molecules instantaneously disappear from the soup (we say they are **consumed** by the reaction), and then the output molecules are **emitted** into the soup.

The simulator will start many reactions concurrently whenever their input molecules are available.
As reactions emit new molecules into the soup, the simulator will continue starting new reactions whenever possible.

## Concurrent computations on the chemical machine

The chemical machine is implemented by the runtime engine of `Chymyst`.
Rather than merely watch as reactions happen, we are going to use the chemical machine for running actual concurrent programs.

To this end, `Chymyst` introduces three features:

1. Each molecule in the soup is now required to _carry a value_.
Molecule values are strongly typed: a molecule of a given sort (such as `a` or `b`) can only carry values of some fixed type (such as `Boolean` or `String`).

2. Since molecules must carry values, we now need to specify a value of the correct type whenever we emit a new molecule into the soup.

3. For the same reason, reactions that produce new molecules will now need to put values on each of the output molecules.
These output values must be _functions of the input values_, -- that is, of the values carried by the input molecules consumed by this reaction.
Therefore, each chemical reaction will now carry a Scala expression (called the **reaction body**) that will compute the new output values and emit the output molecules.

Let us see how the chemical machine can be programmed to run computations.

We will use syntax such as `b(123)` to denote molecule values.
In a chemical reaction, the syntax `b(123)` means that the molecule `b` carries an integer value `123`.
Molecules to the left-hand side of the arrow are the input molecules of the reaction; molecules on the right-hand side are the output molecules.

A typical reaction, equipped with molecule values and a reaction body, looks like this in pseudocode syntax:

```scala
a(x) + b(y) ⇒ a(z)
where z = computeZ(x,y) // -- reaction body

```

In this example, the reaction's input molecules are `a(x)` and `b(y)`; that is, the input molecules have chemical designations `a` and `b` and carry values `x` and `y` respectively.

The reaction body is an expression that captures the values `x` and `y` from the consumed input molecules.
The reaction body computes `z` out of `x` and `y`; in this example, this is done using the function `computeZ`.
The newly computed value `z` is placed onto the output molecule `a(z)`, which is emitted back into the soup.

Another example of a reaction is

```scala
a(x) + c(y) ⇒ println(x + y) // -- reaction body with no output molecules

```

This reaction consumes the molecules `a` and `c` as its input, but does not emit any output molecules.
The only result of running the reaction is the side-effect of printing the number `x + y`.

![Reaction diagram a(x) + b(y) => a(z), a(x) + c(y) => ...](https://chymyst.github.io/joinrun-scala/reactions2.svg)

The computations performed by the chemical machine are _automatically concurrent_:
Whenever input molecules are available in the soup, the runtime engine will start a reaction that consumes these input molecules.
If many copies of input molecules are available, the runtime engine could start several reactions concurrently.
The runtime engine can decide how many concurrent reactions to run depending on the number of available cores.

The reaction body can be a _pure function_ that computes output values solely from the input values carried by the input molecules.
If the reaction body is a pure function, it is completely safe (free of contention or race conditions) to execute concurrently several copies of the same reaction as different processes.
Each process will consume its own input molecules and will work with its own input values.
This is how the chemical machine achieves safe and automatic concurrency in a purely functional way,
with no global mutable state.

## The syntax of `Chymyst`

So far, we have been writing chemical laws with a kind of chemistry-resembling pseudocode.
The actual syntax of `Chymyst` is only a little more verbose:

```scala
import io.chymyst.jc._

// declare the molecule types
val a = m[Int] // a(...) will be a molecule with an integer value
val b = m[Int] // ditto for b(...)

// declare the reaction site and the available reaction(s)
site(
  go { case a(x) + b(y) =>
    val z = computeZ(x,y)
    a(z)
  }
)

```

The helper functions `m`, `site`, and `go` are defined in the `Chymyst` library.

The `site()` function call declares a **reaction site**, which can be visualized as a place where molecules gather and wait for their reaction partners.

## Example: Concurrent counter

We already know enough to start implementing our first concurrent program!

The task at hand is to maintain a counter with an integer value, which can be incremented or decremented by non-blocking concurrent requests.
For example, we would like to be able to increment and decrement the counter from different processes running at the same time.

To implement this in `Chymyst`, we begin by deciding which molecules we will need to define.
Since there is no global mutable state, it is clear that the integer value of the counter needs to be carried by a molecule.
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
The reactions must be such that the counter's value is incremented when we emit the `incr` molecule, and decremented when we emit the `decr` molecule.

So, it looks like we will need two reactions. Let us write a reaction site:

```scala
site(
  go { case counter(n) + incr(_) => counter(n + 1) },
  go { case counter(n) + decr(_) => counter(n - 1) }
)

```

Each reaction says that the new value of the counter (either `n + 1` or `n - 1`) will be carried by the new `counter(...)` molecule emitted by the reaction's body.
The previous molecule, `counter(n)`, will be consumed by the reactions.
The `incr()` and `decr()` molecules will be likewise consumed.

![Reaction diagram counter(n) + incr => counter(n+1) etc.](https://chymyst.github.io/joinrun-scala/counter-incr-decr.svg)

In `Chymyst`, a reaction site is created by calling `site(...)`.
A reaction site can declare one or more reactions.

In the present example, however, both reactions need to be written within the same reaction site.
Here is why:

Both reactions `{ counter + incr => ... }` and `{ counter + decr => ... }` consume the molecule `counter()`.
In order for any of these reactions to start, the molecule `counter()` needs to be present at some reaction site.
Therefore, the `incr()` and `decr()` molecules must be present at the _same_ reaction site, or else they cannot meet with `counter()` to start a reaction.
For this reason, both reactions need to be defined _together_ in a single reaction site.

After defining the molecules and their reactions, we can start emitting new molecules into the soup:

```scala
counter(100)
incr() // after a reaction with this, the soup will have counter(101)
decr() // after a reaction with this, the soup will again have counter(100)
decr() + decr() // after a reaction with these two, the soup will have counter(98)

```

The syntax `decr() + decr()` is just a chemistry-resembling syntactic sugar for emitting several molecules at once.

Note that `counter`, `incr` and `decr` are local values that we use as functions, e.g. by writing `incr()` and `decr()`, to actually emit the corresponding molecules.
For this reason, we refer to `counter`, `incr`, and `decr` as **molecule emitters**. 

It could happen that we are emitting `incr()` and `decr()` molecules too quickly for reactions to start.
This will result in many instances of `incr()` or `decr()` molecules being present in the soup, waiting to be consumed.
Is this a problem?

Recall that when the chemical machine starts a reaction, all input molecules are consumed first, and only then the reaction body is evaluated.
In our case, each reaction needs to consume a `counter` molecule, but only one instance of `counter` molecule is initially present in the soup.
For this reason, the chemical machine will need to choose whether the single `counter` molecule will react with an `incr` or a `decr` molecule.
Only when the incrementing or the decrementing calculation is finished, the new instance of the `counter` molecule (with the updated integer value) will be emitted into the soup.
This automatically prevents race conditions with the counter: There is no possibility of updating the counter value simultaneously from different reactions.

## Tracing the output

The code shown above will not print any output, so it is instructive to put some print statements into the reaction bodies.

```scala
import io.chymyst.jc._

// declare the molecule emitters and the value types
val counter = m[Int]
val incr = m[Unit]
val decr = m[Unit]

// helper function to be used in reactions
def printAndEmit(x: Int) = {
  println(s"new value is $x")
  counter(x)
}

// write the reaction site
site(
  go { case counter(n) + decr(_) => printAndEmit(n - 1) }
  go { case counter(n) + incr(_) => printAndEmit(n + 1) },
)

counter(100)
incr() // prints “new value is 101”
decr() // prints “new value is 100”
decr() + decr() // prints “new value is 99” and then “new value is 98”

```

## Debugging

`Chymyst` has some debugging facilities to help the programmer verify that the chemistry works as intended.

### Logging the contents of the soup

For debugging purposes, it is useful to see what molecules are currently present in the soup at a given reaction site (RS), waiting to react with other molecules.
This is achieved by calling the `logSoup` method on any of the molecule emitters.
This method will return a string showing the molecules that are currently present in the soup at that RS.
The `logSoup` output will also show the values carried by each molecule.

In our example, all three molecules `counter`, `incr`, and `decr` are declared as inputs at our RS, so we could use any of the emitters, say `decr`, to log the soup contents:

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

To get asynchronous, real-time logging information about the molecules being consumed or emitted and about the reactions being started, the user can set the logging level on the RS.
This is done by calling `setLogLevel` on any molecule emitter bound to that RS.

```scala
counter.setLogLevel(2)

```

After this, verbosity level 2 is set on all reactions involving the RS to which `counter` is bound.
This might result in a large printout if many reactions are happening.
So this facility should be used only for debugging or testing.

## Common errors

### Error: Emitting molecules with undefined chemistry

For each molecule, there must exist a single reaction site (RS) to which this molecule is bound -- that is, the RS where this molecule is consumed as input molecule by some reactions.
(See [Reaction Sites](chymyst-core.md#reaction-sites) for a more detailed discussion.)

It is an error to emit a molecule that is not yet defined as input molecule at any RS (i.e. not yet bound to any RS).

```scala
val x = m[Int]
x(100) // java.lang.Exception: Molecule x is not bound to any reaction site

```

The same error will occur if such emission is attempted inside a reaction body, or if we call `logSoup` or `setLogLevel` on the molecule emitter.

The correct way of using `Chymyst` is first to define molecules, then to create a RS where these molecules are used as inputs for reactions, and only then to start emitting these molecules.

The method `isBound` can be used to determine at run time whether a molecule has been already bound to a RS:

```scala
val x = m[Int]
x.isBound // returns `false`

site( go { case x(2) =>  } )

x.isBound // returns `true`

```

### Error: Redefining chemistry

Chemical laws are immutable and statically defined in a `Chymyst` program.
All reactions that consume a certain molecule must be declared in one RS.
Once it is declared that a certain molecule starts certain reactions, users cannot add new reactions that consume that molecule.

For this reason, it is an error to write a reaction whose input molecule is already _used as input_ at another RS.

```scala
val x = m[Int]
val a = m[Unit]
val b = m[Unit]

site( go { case x(n) + a(_) => println(s"have x($n) + a") } ) // OK, "x" is now bound to this RS.

site( go { case x(n) + b(_) => println(s"have x($n) + b") } )
// java.lang.Exception: Molecule x cannot be used as input since it is already bound to Site{a + x => ...}

```

What the programmer probably meant is that the molecule `x()` has two reactions that consume it.
Correct use of `Chymyst` requires that we put these two reactions together into _one_ reaction site:
 
```scala
val x = m[Int]
val a = m[Unit]
val b = m[Unit]

site(
  go { case x(n) + a(_) => println(s"have x($n) + a") },
  go { case x(n) + b(_) => println(s"have x($n) + b") }
) // OK

``` 

More generally, all reactions that share any input molecules must be defined together in a single RS.
However, reactions that use a certain molecule only as an output molecule can be declared in another RS.
Here is an example where we define one RS that computes a result and emits a molecule called `show`, which is bound to another RS:

```scala
val show = m[Int]
// reaction site where the “show” molecule is an input molecule
site( go { case show(x) => println(s"got $x") })

val start = m[Unit]
// reaction site where the “show” molecule is an output molecule (but not an input molecule)
site(
  go { case start(_) => val res = compute(???); show(res) }
)

``` 

## Order of reactions and nondeterminism

When a reaction site has enough waiting molecules for several different reactions to start, the runtime engine will choose the reaction at random, giving each candidate reaction an equal chance of starting.

The next figure shows a possibility of different reactions starting at a reaction site.
In this example, the soup contains one copy of the `counter` molecule, one copy of `incr`, and one copy of `decr`.
The `counter` molecule could either react with the `incr` molecule or with the `decr` molecule.
One of these reactions (shown in solid lines) have been chosen to actually start, which leaves the second reaction (shown with a dashed line) without the input molecule `counter` and, therefore, the second reaction cannot start.

![Reaction diagram counter + incr, counter + decr](https://chymyst.github.io/joinrun-scala/counter-multiple-reactions.svg)

Similarly, when there are several copies of the same molecule that can be consumed by a reaction, the runtime engine will make a choice of which copy of the molecule to consume.

The next figure shows the choice of input molecules among the ones present at a reaction site.
In this example, the soup contains one copy of the `counter` molecule and four copies of the `incr` molecule.
Each of the four `incr` molecules can react with the one `counter` molecule.
The runtime engine will choose which molecules actually react.
One reaction (shown in solid lines) will start, consuming the `counter` and `incr` molecules, while other possible reactions (shown in dashed lines) will not start.

![Reaction diagram counter + incr, counter + incr](https://chymyst.github.io/joinrun-scala/counter-multiple-molecules.svg)

After this reaction, the contents of the soup is one copy of the `counter` molecule (with the updated value) and the three remaining `incr` molecules.
At the next step, another one of the `incr` molecules will be chosen to start a reaction, as shown in the next figure:

![Reaction diagram counter + incr, counter + decr after reaction](https://chymyst.github.io/joinrun-scala/counter-multiple-molecules-after-reaction.svg)

Currently, `Chymyst` will _not_ fully randomize the input molecules but make an implementation-dependent choice.
A truly random selection of input molecules may be implemented in the future.

Importantly, it is _not possible_ to assign priorities to reactions or to molecules.
The chemical machine ignores the order in which reactions are listed in the `site(...)` call, as well as the order of molecules in the input list of each reaction.
Just for the purposes of debugging, molecules will be printed in alphabetical order of names, and reactions will be printed in an unspecified but fixed order.

The result is that the order in which reactions will start is non-deterministic and unknown.

If the priority of certain reactions is important for a particular application, it is the programmer's task to design the chemistry in such a way that those reactions start in the desired order.
This is always possible by using auxiliary molecules and/or guard conditions.

In fact, a facility for assigning priority to molecules or reactions would be counterproductive.
It will only give the programmer _an illusion of control_ over the order of reactions, while actually introducing subtle nondeterministic behavior.

To illustrate this on an example, suppose we would like to compute the sum of a bunch of numbers in a concurrent way.
We expect to receive many molecules `data(x)` with integer values `x`,
and we need to compute and print the final sum value when no more `data(...)` molecules are present.

Here is an (incorrect) attempt to design the chemistry for this program:

```scala
// Non-working code
val data = m[Int]
val sum = m[Int]
site (
  go { case data(x) + sum(y) => sum(x + y) }, // We really want the first reaction to be high priority
   
  go { case sum(x) => println(s"sum = $x") }  // and run the second one only after all `data` molecules are gone.
)
data(5) + data(10) + data(150)
sum(0) // expect "sum = 165"

```

Our intention was to run only the first reaction and to ignore the second reaction as long as `data(...)` molecules are available in the soup.
The chemical machine does not actually allow us to assign a higher priority to the first reaction.
But, if we were able to do that, what would be the result?

In reality, the `data(...)` molecules are going to be emitted concurrently at unpredictable times.
For instance, they could be emitted by several other reactions that are running concurrently.
Then it will sometimes happen that the `data(...)` molecules are emitted more slowly than we are consuming them at our reaction site.
When that happens, there will be a brief interval of time when no `data(...)` molecules are in the soup (although other reactions are perhaps about to emit some more of them).
The chemical machine will then run the second reaction, consume the `sum(...)` molecule and print the result, signalling (incorrectly) that the computation is finished.
Perhaps this failure will _rarely_ happen and will not show up in our unit tests, but at some point it is definitely going to happen in production.
This kind of nondeterminism illustrates why concurrency is widely regarded as a hard programming problem.

`Chymyst` will actually reject our attempted program and print an error message before running anything, immediately after we define the reaction site:

```scala
val data = m[Int]
val sum = m[Int]
site (
  go { case data(x) + sum(y) => sum(x + y) },
  go { case sum(x) => println(s"sum = $x") }
)

```

`Exception: In Site{data + sum => ...; sum => ...}: Unavoidable nondeterminism:`
`reaction data + sum => ... is shadowed by sum => ...`

The error message means that the reaction `sum => ...` will sometimes prevent `data + sum => ...` from running,
and that the programmer _has no control_ over this nondeterminism.

The correct implementation needs to keep track of how many `data(...)` molecules we already consumed,
and to print the final result only when we reach the total expected number of the `data(...)` molecules.
Since reactions do not have mutable state, the information about the remaining `data(...)` molecules has to be carried on the `sum(...)` molecule.
So, we will define the `sum(...)` molecule with type `(Int, Int)`, where the second integer will be the number of `data(...)` molecules that remain to be consumed.

The reaction `data + sum` should proceed only when we know that some `data(...)` molecules are still remaining.
Otherwise, `sum(...)` should start its own reaction and print the final result. 

```scala
val data = m[Int]
val sum = m[(Int, Int)]
site (
  go { case data(x) + sum((y, remaining)) if remaining > 0 => sum((x + y, remaining - 1)) },
  go { case sum((x, 0)) => println(s"sum = $x") }
)
data(5) + data(10) + data(150) // emit three `data` molecules
sum((0, 3)) // "sum = 165" printed

```

Now the chemistry is fully deterministic, without the need to assign priorities to reactions.

The chemical machine forces the programmer to design the chemistry in such a way that
the order of running reactions is completely determined by the data on the available molecules.

Another way of maintaining determinism is to avoid writing reactions that might shadow each other.
Here is equivalent code with just one reaction:

```scala
val data = m[Int]
val sum = m[(Int, Int)]
site (
  go { case data(x) + sum((y, remaining)) =>
      val newSum = x + y
      if (remaining == 1)  println(s"sum = $newSum")
      else  sum((newSum, remaining - 1)) 
     }
)
data(5) + data(10) + data(150) // emit three `data` molecules
sum((0, 3)) // expect "sum = 165" printed

```

The drawback of this approach is that the reaction became less declarative due to complicated branching code inside the reaction body.
However, this chemistry will run somewhat faster because no guard conditions need to be checked before scheduling a reaction.

## Summary so far

The chemical machine requires for its description:

- a list of defined molecules, together with their types;
- a list of reactions involving these molecules as inputs, together with reaction bodies.

These definitions comprise the chemistry of a concurrent program.

The user can define one or more reaction sites, each having one or more reactions.
We imagine a reaction site to be a virtual place where molecules arrive and wait for other molecules, in order to start reactions with them. 
Each molecule has only one reaction site where that molecule can be consumed by reactions.

For this reason, all reactions that have a common _input_ molecule must be declared at the same reaction site.
Different reaction sites may not have any common input molecules.

By defining molecules, reaction sites, and the reactions at each site, we can specify an arbitrarily complicated system of interacting concurrent processes. 

After defining the molecules and specifying the reactions, the code will typically emit some initial molecules into the soup.
The chemical machine will then start running all the possible reactions, constantly keeping track of the molecules consumed by reactions or newly emitted into the soup.

Let us recapitulate the core ideas of the chemical paradigm of concurrency:

- In the chemical machine, there is no mutable global state; all data is immutable and must be carried by some molecules.
- Each reaction specifies its input molecules, and in this way determines all the data necessary for computing the reaction body.
The chemical machine will automatically make this data available to a reaction, since the reaction can start only when all its input molecules are present in the soup.
- Each reaction also specifies a reaction body, which is a Scala expression that evaluates to `Any`. (The final result value of that expression is discarded.)
The reaction body can perform arbitrary computations using the input molecule values.
- The reaction body will typically compute some new values and emit new molecules carrying these values.
Emitting a molecule is a side effect of calling an emitter.
Emitters can be called at any time -- either within a reaction body or in any other code.
- Up to the side effect of emitting new molecules, the reaction body can be a pure function that only depends on the input data of the reaction.
In this case, many copies of the reaction can be safely executed concurrently when many sets of input molecules are available.
Also, the reaction can be safely and automatically restarted in the case of a transient failure
by simply emitting the input molecules again.

The chemical laws fully specify which computations need to be performed for the data on the given molecules.
Whenever multiple sets of data are available, the corresponding computations will be performed concurrently.

# Example: Declarative solution for “dining philosophers”

The ["dining philosophers problem"](https://en.wikipedia.org/wiki/Dining_philosophers_problem) is to run a simulation of five philosophers who take turns eating and thinking.
Each philosopher needs two forks to start eating, and every pair of neighbor philosophers shares a fork.

<img alt="Five dining philosophers" src="An_illustration_of_the_dining_philosophers_problem.png" width="400" />

The simplest solution of the “dining philosophers” problem is achieved using a molecule for each fork and two molecules per philosopher: one representing a thinking philosopher and the other representing a hungry philosopher.

- Each of the five “thinking philosopher” molecules (`thinking1`, `thinking2`, ..., `thinking5`) starts a reaction in which the process is paused for a random time and then the “hungry philosopher” molecule is emitted.
- Each of the five “hungry philosopher” molecules (`hungry1`, ..., `hungry5`) needs to react with _two_ neighbor “fork” molecules.
The reaction process is paused for a random time, and then the “thinking philosopher” molecule is emitted together with the two “fork” molecules previously consumed.

The complete code is shown here:

```scala
 /**
 * Print message and wait for a random time interval.
 */
def wait(message: String): Unit = {
  println(message)
  Thread.sleep(scala.util.Random.nextInt(20))
}

val hungry1 = m[Int]
val hungry2 = m[Int]
val hungry3 = m[Int]
val hungry4 = m[Int]
val hungry5 = m[Int]
val thinking1 = m[Int]
val thinking2 = m[Int]
val thinking3 = m[Int]
val thinking4 = m[Int]
val thinking5 = m[Int]
val fork12 = m[Unit]
val fork23 = m[Unit]
val fork34 = m[Unit]
val fork45 = m[Unit]
val fork51 = m[Unit]

site (
  go { case thinking1(_) => wait("Socrates is thinking");  hungry1() },
  go { case thinking2(_) => wait("Confucius is thinking"); hungry2() },
  go { case thinking3(_) => wait("Plato is thinking");     hungry3() },
  go { case thinking4(_) => wait("Descartes is thinking"); hungry4() },
  go { case thinking5(_) => wait("Voltaire is thinking");  hungry5() },

  go { case hungry1(_) + fork12(_) + fork51(_) => wait("Socrates is eating");  thinking1() + fork12() + fork51() },
  go { case hungry2(_) + fork23(_) + fork12(_) => wait("Confucius is eating"); thinking2() + fork23() + fork12() },
  go { case hungry3(_) + fork34(_) + fork23(_) => wait("Plato is eating");     thinking3() + fork34() + fork23() },
  go { case hungry4(_) + fork45(_) + fork34(_) => wait("Descartes is eating"); thinking4() + fork45() + fork34() },
  go { case hungry5(_) + fork51(_) + fork45(_) => wait("Voltaire is eating");  thinking5() + fork51() + fork45() }
)
// Emit molecules representing the initial state:
thinking1() + thinking2() + thinking3() + thinking4() + thinking5()
fork12() + fork23() + fork34() + fork45() + fork51()
// Now reactions will start and print to the console.

```

Note that a `hungry + fork + fork` reaction will consume a “hungry philosopher” molecule and two “fork” molecules,
so these three molecules will not be present in the soup during the time interval taken by the “eating” process.
Thus, neighbor philosophers will not be able to start eating until the two “fork” molecules are returned to the soup.
The decision of which philosophers start eating will be made randomly, and there will never be a deadlock.

The example code shown above is _fully declarative_: it describes what the “dining philosophers” simulation must do but not how to do it,
and the code is quite close to the English-language description of the problem.

The result of running this program is the output such as

```
Plato is thinking
Socrates is thinking
Voltaire is thinking
Descartes is thinking
Confucius is thinking
Plato is eating
Socrates is eating
Plato is thinking
Descartes is eating
Socrates is thinking
Voltaire is eating
Descartes is thinking
Confucius is eating
Voltaire is thinking
Plato is eating
Confucius is thinking
Socrates is eating
Socrates is thinking
Plato is thinking
Voltaire is eating

```
