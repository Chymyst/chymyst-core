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
