<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Constraints of the Chemical Machine (and how to overcome them)

While designing the chemical laws for an application, we need to keep in mind that the chemical paradigm limits what we can do.
As we will come to realize, these constraints are for our own good!

## Absence of molecules is undetectable

We cannot detect the _absence_ of a given non-blocking molecule, say `a(1)`, in the soup.
This seems to be a genuine limitation of the chemical machine paradigm.

It seems that this limitation cannot be lifted by any clever combinations of blocking and non-blocking molecules; perhaps this can be even proved formally, but I haven't tried learning the formal tools for that.
I just tried to implement this but could not find the right combination of reactions.

How could we possibly detect the absence of a given molecule, say `a(x)` with any value `x`?
We can emit a blocking molecule that reacts with `a(x)`.
If `a(x)` is absent, the emission will block.
So the absence of `a(x)` in the soup can be translated into blocking of a function call.
However, no programming language is able to detect whether a function call has been blocked, because the function call is by definition a blocking call!
All we can do is to detect whether the function call has returned within a given time, but here we would like to return instantly with the information that `a` is present or absent.

Suppose we define a reaction that consumes the molecule `a()`.
Even if we establish that this reaction did not start within a certain time period, we cannot conclude that `a()` is absent in the soup at that time!
It could happen that `a()` was present but got involved in some other reactions and was consumed by them, or that `a()` was present but the computer's CPU was simply so busy that our reaction could not yet start and is still waiting in a queue.

Another feature would be to introduce “inhibiting” conditions on reactions: a certain reaction can start when molecules `a` and `b` are present but no molecule `c` is present.
However, it is not clear that this extension of the chemical paradigm would be useful.
The reactions with “inhibiting” conditions will be unreliable because they will sometimes run and sometimes not run, depending on exactly when some molecules are emitted.
Since the programmer cannot control the duration of time taken by reactions, it seems that “inhibiting” conditions simply lead to a kind of indeterminism that the programmer cannot control at all.

Since we can expect molecules to be emitted at random and unpredictable times by concurrently running processes, it is always possible that a certain molecule is, at a given time, not present in the soup but is about to be emitted by some reaction because the reaction task is already waiting in the scheduler's queue.
If we added a feature to the chemical machine that explicitly detects the absence of a molecule,
we would merely make the program execution unreliable while giving ourselves an illusion of control.

With its present design,
the chemical machine forces the programmer to design the chemical laws in such a way
that the result of the execution of the program is the same even if random delays were inserted at any point when a molecule is emitted or a reaction is started.

## No remote pooling of molecules

Chemical soups running as different processes (either on the same computer or on different computers) are completely separate and cannot be pooled.

It could be useful to be able to connect many chemical machines together, running perhaps on different computers, and to pool their individual soups into one large “common soup”.
Our program should then be able to emit lots of molecules into the common pool and thus organize a massively parallel, distributed computation, without worrying about which CPU computes what reaction.

Some implementations of the chemical machine, notably [JoCaml](http://jocaml.inria.fr), provide a facility for sending molecules from one chemical soup to another.
However, in order to organize a distributed computation, we would need to split the tasks explicitly between the participating soups.
The organization and supervision of distributed computations, the maintenance of connections between machines, the handling of disconnections — all this remains the responsibility of the programmer and is not handled automatically by JoCaml.

In principle, a sufficiently sophisticated runtime engine could organize a distributed computation completely transparently to the programmer.
It remains to be seen whether it is feasible and/or useful to implement such a runtime engine.

## Chemistry is immutable

Reactions and reaction sites are immutable.
It is impossible to add more reactions at run time to an existing reaction site.
This limitation is enforced in `Chymyst` by making reaction sites immutable and invisible to the user.

After a reaction site declares a certain molecule as an input molecule for some reactions, it is impossible to add further reactions consuming that molecule.
It is also impossible to remove reactions from reaction sites, or to disable reactions.
The only way to stop certain reactions from running is to refrain from emitting some input molecules required by these reactions.  

However, it is possible to declare a reaction site with reactions computed _at run time_.
Since reactions are local values (as are molecule emitters), users can first create any number of reactions and store these reactions in an array, before declaring a reaction site with these reactions.
Once all desired reactions have been assembled, users can declare a reaction site that includes all the reactions from the array.

As an (artificial) example, consider the following pattern of reactions:

```scala
val finished = m[Unit]
val a = m[Int]
val b = m[Int]
val c = m[Int]
val d = m[Int]

site(
  go { case a(x) ⇒ b(x + 1) },
  go { case b(x) ⇒ c(x + 1) },
  go { case c(x) ⇒ d(x + 1) },
  go { case d(x) ⇒ if (x > 100) finished() else a(x + 1) }
)

a(10)

```

When this is run, the reactions will cycle through the four molecules `a`, `b`, `c`, `d` while incrementing the value each time, until the value 100 or higher is reached by the molecule `d`.

Now, suppose we need to write a reaction site where we have `n` molecules and `n` reactions instead of just four,
where `n` is a run-time parameter.
Since molecule emitters and reactions are local values, we can simply create them and store in a data structure:


```scala
val finished = m[Unit]
val n = 100 // `n` is computed at run time

// sequence of molecule emitters:
val emitters = (0 until n).map( i ⇒ new M[Int](s"a_$i"))
// this is equivalent to declaring:
// val emitters = Seq(
//    new M[Int]("a_0"),
//    new M[Int]("a_1"),
//    new M[Int]("a_2"),
//    ...
// )

// array of reactions:
val reactions = (0 until n).map{ i ⇒
  // create the i-th reaction with index
  val iNext = if (i == n - 1) 0 else i + 1
  val a = emitters(i) // We must define molecule emitters `a`
  val aNext = emitters(iNext) // and `aNext` as explicit local values,
  go { case a(x) ⇒ // because `case emitters(i)(x)` won't compile.
    if (i == n - 1 && x > 100) finished() else aNext(x + 1)
  }
}

// write the reaction site
site(reactions:_*)

// emit the first molecule
emitters(0)(10)

```

## Pipelined molecules

The chemical machine paradigm does not enforce any particular order on reactions or molecules.
However, it is often necessary in practical applications to ensure that e.g. requests are processed in the order they are received.
If each request is represented by a newly emitted molecule, we will be required to ensure that molecules start their reactions in the order of emission.

In principle, this can be implemented by, say, attaching an extra timestamp to each molecule and enforcing the order of molecule consumption via guard conditions on all relevant reactions.
But this method of implementation is inefficient and also burdens the programmer with extra bookkeeping, making the code non-declarative.

For this reason, `Chymyst` has special support for **pipelined molecules** - that is, molecules that are always consumed in the exact order they were emitted.

For each pipelined molecule, `Chymyst` allocates an (ordered) queue that stores molecule instances in the order they were emitted.
Whenever a reaction consumes a pipelined molecule, it is always the head of the queue that is consumed.

Pipelined molecules are detected automatically by `Chymyst` according to the conditions we will describe now.
First we need to consider whether the chemical machine semantics is compatible with pipelined molecules at all.

It turns out that, in a given chemical program, some molecules may be made pipelined without adverse effects, while others cannot be made pipelined.
The following example illustrates why this is so.
In this example, several reactions consume the same molecule but impose different guard conditions on the molecule value:

```scala
site(
  go { case c(0) + a(y) ⇒ ... },
  go { case c(y) if y > 0 ⇒ a(y) }
)

```

Suppose the molecule `c()` is implemented via an ordered queue, and we emit `c(0)`, `c(1)`, `c(2)` in this order.
The presence of `c(0)` at the head of the queue means that a reaction consuming `c(0)` must run first.

Thus, the presence of `c(0)` at the head of the queue will prevent any other copy of `c()` from being consumed,
until a molecule` a()` becomes available so that `c(0)` may be consumed and removed from the head of the queue.
But `a()`  is emitted only by the second reaction, which will never run since `c(1)` is not at the head of the queue.

So, implementing `c()` as a pipelined molecule will create a deadlock in this program.
Programs like this one will work correctly only if reactions are allowed to consume the `c()` molecules out of order.

However, the molecule `a()` can be made pipelined, since there are no conditions on its value, and the available instances of `a()` can be consumed in any order without creating deadlocks.

Many chemical programs can be implemented with pipelined molecules.
As another example, consider the "counter",

```scala
site(
  go { case c(x) + incr(_) ⇒ c(x + 1) },
  go { case c(x) + decr(_) if x > 0 ⇒ c(x - 1) }
)

```

In this program, the molecules `incr()` and `decr()` can be made pipelined without any adverse effects.

So we find that, in a given chemical program, some molecules can be made pipelined while others cannot.
`Chymyst` performs static (early run-time) analysis of the user's code and automatically assigns ordered queues to all molecules
that could admit pipelined semantics without creating problems.

For a molecule `c()` to be pipelined, the reactions consuming `c()` must be such that
the chemical machine only needs to inspect a single copy of the molecule `c()` in order to be able to select correctly the next reaction to run.

### Detecting pipelined molecules

Given a chemical program, how can we check whether a molecule `c()` can be pipelined?

Let us assume that the molecule `c()` has value type `T`.

There may exist values `x: T` such that no reactions will _ever_ consume the instance `c(x)` with that `x` -- neither now, nor in the future.
(This happens, for example, if `x` is such that no pattern or guard condition is ever satisfied in any of the available reactions. Molecule instances `c(y)` with some `y != x` might still be potentially consumed now or later.)
Let us call these values of `x` **ignorable** values for the molecule `c()`.
Molecule instances `c(x)` that carry an ignorable value `x` will never be consumed by any reactions and can be deleted from the soup immediately.

Let us now examine the main assumption about pipelined molecules:
For a molecule `c()` to be pipelined, the chemical program must be such that the next reaction consuming `c()` can be always found (with no deadlocks) by examining _only one_ instance `c(x)` of the molecule.

Two properties of the chemical program immediately follow from this requirement:

- (Property 1.) Every reaction may consume at most one instance of `c()`. (No reaction should consume multiple input `c()` molecules, e.g. `go { c(x) + c(y) ⇒ ... }`.)

- (Property 2.) If, for some non-ignorable value `x`, the molecule instance `c(x)` cannot be immediately consumed by any reaction at this time,
no other molecule instance `c(y)` with _any other_ non-ignorable `y != x` can be immediately consumed by any reaction either.
(If some reaction could consume`c(y)`, the program would become deadlocked by the presence of `c(x)` at the top of the queue.)

Property 2 is somewhat difficult to reason about, if formulated in this way.
By using Boolean logic, this property can be simplified and formulated as a certain criterion to be checked for all reactions consuming `c()`.
The formal derivation of the simplified property is in the next subsection.

To formulate the simplified property, let us assume that `c(x)` is at the head of the queue,
and let us ask whether a molecule instance `c(x)` can be consumed by a certain reaction, say `go { c(x) + d(y) + e(z) if g(x, y, z) ⇒ ... }`.
This reaction can start if

- a molecule instance `d(y)` is present with some `y`,
- a molecule instance `e(z)` is present with some `z`,
- the values `y` and `z` satisfy the guard condition `g(x, y, z)`.

We can write these conditions symbolically as a Boolean formula

`HAVE(d(y)) && HAVE(e(z)) && g(x, y, z)`.

Repeating the same consideration for each reaction consuming `c()`, we obtain a certain set of Boolean formulas of this kind, with various guard conditions and other molecules. 

Now, the conditions for `c()` to be pipelined are that

- each reaction's Boolean formula can be identically rewritten as a simple Boolean _conjunction_ of the form `p(x) && q(y, z, ...)`, where
- the Boolean function `p(x)` involves only the value `x` and must be the same for all reactions, while `q(...)` could be different for each reaction,
involving the presence of any other molecules and their specific values, but independent of `x`.

In other words, the condition for `c(x)` to be consumed by any reaction must depend on the value `x` in a way that does not involve other molecules.

To check whether any molecule `c()` satisfies this condition within a given chemical program, `Chymyst` goes through all reactions that consume `c(x)`,
determines the Boolean formula for that reaction, and tries to simplify that formula into a conjunction of the form `p(x) && q(y, z, ...)`.
If this succeeds with `p(x)` being _the same for all reactions_, `Chymyst` determines that the molecule `c()` can be pipelined.

`Chymyst` will then assign the `.isPipelined` property of the molecule emitter to `true` for that molecule.
In debug output, the names of pipelined molecules will be suffixed with `/P`.

Here is an example of a reaction site where some molecules are pipelined but others are not:

```scala
site(
  go { case a(x) if x > 0 + e(_) ⇒ ... },
  go { case a(x) + c(y) if x > 0 ⇒ ... },
  go { case d(z) + c(y) if y > 0 ⇒ ...}
)

```

In this program, `a(x)` can be consumed if and only if `x > 0`.
This condition is the same for the two reactions that consume `a()`.
Therefore, the condition for consuming `a(x)` has the form `x > 0 && q(...)` for some Boolean function `q()` that does not depend on `x`.
`Chymyst` will determine that `a()` can be pipelined.

The situation with the molecule `c()` is different:
The second reaction accepts `c(y)` with any `y` but the third reaction requires `y > 0`.
Therefore the Boolean condition for consuming `c(y)` has the form

`q1(HAVE(a(x)), x) || y > 0 && Q2(HAVE(d(z)))`

This formula cannot be decomposed into a conjunction of the form `p(y) && q(x, z, ...)`
So the molecule `c()` cannot be pipelined in this reaction site.

Finally, the molecule `d()` is pipelined because it is consumed without imposing any conditions on its value.
Such molecules can be always pipelined.

### Derivation of the simplified condition

This subsection shows a formal derivation of the pipelined condition using Boolean logic.
Readers not interested in this theory may skip to next section.

Consider a set of reactions, each consuming a single instance of `c(x)`.
For each reaction `r`, we have a Boolean function of the form `f`<sub>`r`</sub>`(x, y, z, HAVE(d(y)), HAVE(e(z)), ...)` that depends on a number of other variables.

Here, the symbolic expressions such as `HAVE(d(y))` are understood as simple Boolean variables.
Values of these variables, as well as values of `y`, `z`, etc., describe the current population of molecules in the soup, excluding the molecule `c(x)`.
For brevity, we denote all these "external" variables by `E` (these variables do not include `x`).

The condition for a molecule `c(x)` to be consumed by any reaction is the Boolean disjunction of all the per-reaction functions `f`<sub>`r`</sub>`(x, E)`.
Let us denote this disjunction by `F(x, E)`:

`F(x, E) = `f`<sub>`r_1`</sub>`(x, E) || f`<sub>`r_2`</sub>`(x, E) || ... || f`<sub>`r_n`</sub>`(x, E)`.

We will now show that the complicated requirements of Property 2 from the previous section are equivalent to the single requirement that

`F(x, E) = p(x) && q(E)`,

for some Boolean functions `p` and `q`.

Property 2 states that, for any `x` and at any time (i.e. for any `E`), one of the three conditions must hold:

1. The value `x` is ignorable.
2. The molecule `c(x)` can be consumed immediately by some reaction.
3. The molecule `c(x)` cannot be consumed immediately by any reaction, but also no other molecule instance `c(y)` with any `y != x` could be consumed immediately by any reaction, if that `c(y)` were present in the soup.

Let us formulate these conditions in terms of the function `F(x, E)`, where `x` and `E` describe the soup at the present time:

1. `∀ E1 : !F(x, E1)`
2. `F(x, E)`
3. `∀ x1 : !F(x1, E)`

The disjunction of these three Boolean formulas must be `true` for all `x` and `E`:

`∀ x : ∀ E : (∀ E1 : !F(x, E1)) || F(x, E) || (∀ x1 : !F(x1, E))`.

We will use some tricks of Boolean algebra to prove that this formula is identically equivalent to

`∀ x : ∀ E : F(x, E) == p(x) && q(E)`

where `p` and `q` are suitably defined Boolean functions.

To prove this, we will identically transform the expression under the quantifiers `∀ x : ∀ E :` into the form `F(x, E) == p(x) && q(E)`.

Note that, in Boolean logic, `a == b` is the same as `a && b || (!a && !b)`.
Thus our goal is to derive

(**) `∀ x : ∀ E : F(x, E) && p(x) && q(E) || (!F(x, E) && !(p(x) && q(E)))`.

The derivation proceeds in three steps.

(1) We have the identity `!(∀ x : !f(x)) = ∃ x : f(x)` for any Boolean function `f`.

Therefore, we can rewrite

- `(∀ E1 : !F(x, E1)) = !(∃ E1 : F(x, E1))`
- `(∀ x1 : !F(x1, E)) = !(∃ x1 : F(x1, E))`

We also note that these two expressions are functions of `x` and of `E` respectively.

Let us introduce names for these functions:

- Define `p(x) = ∃ E1 : F(x, E1)`.
- Define `q(E) = ∃ x1 : F(x1, E)`.

Then we can rewrite the original expression under the quantifiers as

`(∀ E1 : !F(x, E1)) || F(x, E) || (∀ x1 : !F(x1, E))`

= `F(x, E) || !p(x) || !q(E) = F(x, E) || !(p(x) && q(E))`.

(2) Another Boolean identity that holds for any Boolean functions `A` and `B` is

`A || !B = (A && B) || !B`.

We use this identity to rewrite the result of step (1) as

`F(x, E) || !(p(x) && q(E)) = F(x, E) && p(x) && q(x) || !(p(x) && q(E))`.

(3) We observe that, for any Boolean function `h(x)` and for any `a`,

`(∀ x : h(x)) = h(a) && (∀ x : h(x))`.

Adding an extra conjuction with `h(a)` does not change the value of `(∀ x : h(x))`:
If `(∀ x : h(x))` is true, it means that `h(x)` holds for all possible values of `x`, including `x = a`.

Using this transformation, we find that

- `!p(x) = (∀ E1 : !F(x, E1)) = !F(x, E) && (∀ E1 : !F(x, E1)) = !F(x, E) && !p(x)`
- `!q(E) = (∀ x1 : !F(x1, E)) = !F(x, E) && (∀ x1 : !F(x1, E)) = !F(x, E) && !q(E)`

It follows that 

`!(p(x) && q(E)) = !p(x) || !q(E) = !F(x, E) && !p(x) || !F(x, E) && !q(E)`

= `!F(x, E) && (!p(x) || !q(E)) = !F(x, E) && !(p(x) && q(E))`.

We use this identity to rewrite the result of step (2) as

`F(x, E) && p(x) && q(x) || !(p(x) && q(E))`

= `(F(x, E) && p(x) && q(E)) || (!F(x, E) && !(p(x) && q(E)))`

= `(F(x, E) == p(x) && q(E))`.

This is precisely the expression (**).
Therefore, we find that for all `x` and `E` the Boolean function `F(x, E)` is equal to the conjunction `p(x) && q(E)`.

Note that we have proved the decomposition of `F(x, E)` rather than each of the per-reaction functions `f`<sub>`r`</sub>`(x, E)`.
If each of the per-reaction functions is decomposable with the same `p(x)`, their disjunction `F(x, E)` is also decomposable;
but the converse does not hold.

`Chymyst` checks that each per-reaction Boolean function is decomposed as a conjunction `p(x) && ...` with the same `p(x)`.
Therefore, the condition actually checked by `Chymyst` is a sufficient (but not a necessary) condition for the molecule to be pipelined.

The result is that `Chymyst` might, in rare cases, fail to determine that some molecules could be pipelined.
The lack of pipelining is safe and will, at worst, lead to some degradation of performance.

It would have been nearly impossible to obtain `F(x, E)` symbolically and to implement the strict necessary condition,
because `x` and `E` are variables with values of arbitrary types (e.g. `Int`, `Double`, `String` and so on),
which are not easily described through Boolean algebra.

As an (admittedly artificial) example, consider the reaction site

```scala
site(
  go { case c(x) + d(y) if x >= 0 && y > 0 ⇒ ... },
  go { case c(x) + d(y) if x >= 0 && y < 1 ⇒ ... },
  go { case c(x) + d(y) if x < 0 ⇒ ... }
)

```

The Boolean condition for consuming `c(x)` has the form

`HAVE(d(y)) && (x >= 0 && y > 0 || x >= 0 && y < 1 || x < 0)`

This condition is equivalent to `HAVE(d(y))` because `y > 0 || y < 1` is equivalent to `true`.
Thus, the molecule `c()` can be pipelined.
However, `Chymyst` cannot deduce that `y > 0 || y < 1` is equivalent to `true` because simplifying numerical inequalities
goes far beyond Boolean logic and may even be undecidable in some cases. 

# Reaction constructors

Chemical reactions are static: they are specified at compile time and cannot be modified at run time.
`Chymyst` compiles with this limitation even though reactions in `Chymyst` are values created at run time.
For instance, we can create an array of molecules and reactions, where the size of the array is determined at run time.
We can easily define reactions for "dining philosophers" even if the number of philosophers is given at run time.

Nevertheless, reactions will not be activated until a reaction site is created as a result of calling `site()`, which can be done only once.
After calling `site()`, we cannot add or remove reactions.
We also cannot write a second reaction site using an input molecule that is already bound to a previous reaction site.
So, we cannot modify the list of molecules bound to a reaction site, and we cannot modify the reactions that may start there.
In other words, chemistry at a reaction site is immutable.

For this reason, reaction sites are static in an important sense that guarantees that chemical laws continue to work as designed, regardless of what the application code does at a later time.
This feature allows us to design chemistry in a modular fashion.
Each reaction site encapsulates some chemistry and monitors certain molecule emitters.
When user code emits a molecule, say `c()`, the corresponding reaction site has already fixed all the reactions that could possibly start due to the presence of `c()`.
Users can neither disable these reactions nor add another reaction that will also consume `c()`.
In this way, users are guaranteed that the encapsulated chemistry will continue to work correctly.

Nevertheless, reactions can be defined at run time.
There are several techniques we can use:

1. Define molecules whose values contain other molecule emitters, which are then used in reactions.
2. Incrementally define new molecules and new reactions, store them in data structures, and assemble a reaction site later.
3. Define a new reaction site in a function that takes arguments and returns new molecule emitters.
4. Define molecules whose values are functions that represent reaction bodies.

We already saw examples of using the first two techniques.
Let us now talk about the last two in some more detail.

## Reaction constructor as a function

Since molecule emitters are local values that close over their reaction sites, we can easily define a general “1-molecule reaction constructor” that creates an arbitrary reaction with a single input molecule.

```scala
def makeReaction[T](reaction: (M[T],T) ⇒ Unit): M[T] = {
  val a = new M[T]("auto molecule 1") // the name is just for debugging
  site( go { case a(x) ⇒ reaction(a, x) } )
  a
}

```

Since `reaction` is an arbitrary function, it can emit further molecules if needed.
In this way, we implemented a “reaction constructor” that can create an arbitrary reaction involving one input molecule.

Similarly, we could create reaction constructors for more input molecules:

```scala
def makeReaction2[T1, T2](reaction: (M[T1], T1, M[T2], T2) ⇒ Unit): (M[T1], M[T2]) = {
  val a1 = new M[T1]("auto molecule 1")
  val a2 = new M[T1]("auto molecule 2")
  site( go { case a1(x1) + a2(x2) ⇒ reaction(a1, x1, a2, x2) } )
  (a1, a2)
}

```

## Reaction constructor as a molecule

In the previous example, we have encapsulated the information about a reaction into a closure.
Since molecules can carry values of arbitrary types, we could put that closure onto a molecule.
In effect, this will yield a “universal molecule” that can define its own reaction.
(However, the reaction can have only one molecule as input.)

```scala
val u = new M[Unit ⇒ Unit]("universal molecule")
site( go { case u(reaction) ⇒ reaction() } )

```

To use this “universal molecule”, we need to supply a reaction body and put it onto the molecule while emitting.
In this way, we can emit the molecule with different reactions.

```scala
val p = m[Int]
val q = m[Int]
// emit u(...) to make the reaction u(x) ⇒ p(123)+q(234)
u({ _ ⇒ p(123) + q(234) })
// emit u(...) to make the reaction u(x) ⇒ p(0)
u({ _ ⇒ p(0) })

```

This example is artificial and perhaps not very useful; it just illustrates some of the capabilities of the chemical machine.

It is interesting to note that the techniques we just described are not special features of the chemical machine.
Rather, they follow naturally from embedding the chemical machine within a functional language such as Scala, which has local scopes and can treat functions as values.
The same techniques will work equally well if the chemical machine were embedded in any other functional language.

# Working with an external asynchronous APIs

We now consider the task of interfacing with an external library that does not use the chemical machine paradigm. 

Suppose we are working with an external library, such as an HTTP or database client, that has an asynchronous API via Scala's `Future`s.
In order to use such libraries together with `Chymyst`, we need to be able to pass freely between `Future`s and molecules.
The `Chymyst` standard library provides a basic implementation of this functionality.

To use the `Chymyst` standard library, add `"io.chymyst" %% "chymyst-lab" % "latest.integration"` to your project dependencies and import `io.chymyst.lab._`

## Attaching molecules to futures

The first situation is when the external library produces a future value `fut : Future[T]`, and we would like to automatically emit a certain molecule `f` when this `Future` is successfully resolved.
This is as easy as doing a `fut.map{x ⇒ f(123)}` on the future.
The library has helper functions that add syntactic sugar to `Future` in order to reduce boilerplate in the two typical cases:

- the molecule needs to carry the same value as the result value of the future: `fut & f`
- the molecule needs to carry a different but fixed value: `fut + f(123)`

## Attaching futures to molecules

The second situation is when an external library requires us to pass a future value that we produce.
Suppose we have a reaction that will eventually emit a molecule with a result value.
We now need to convert the emission event into a `Future` value, resolving to that result value when the molecule is emitted.

This is implemented by the `moleculeFuture` method.
This method will create at once a new molecule emitter and a new `Future` value.
The new molecule will be already bound to a reaction site.
We can then use the new molecule as output in our reactions.

```scala
import io.chymyst.lab._

val a = m[Int]

// emitting the molecule result(...) will resolve "fut"
val (result: M[String], fut: Future[String]) = moleculeFuture[String]

// define a reaction that will eventually emit "result(...)"
site( go { case a(x) ⇒ result(s"finished: $x") } )

// the external library takes our value "fut" and does something with it
ExternalLibrary.consumeUserFuture(fut)

// Now write some code that eventually emits `a` to start the reaction above.

```
