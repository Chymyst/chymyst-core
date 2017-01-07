<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Limitations of the Chemical Machine (and how to overcome them)

While designing the chemical laws for our application, we need to keep in mind that the chemical paradigm limits what we can do (for our own good).

1. We cannot detect the _absence_ of a given non-blocking molecule, say `a(1)`, in the soup.
This seems to be a genuine limitation of the chemical machine paradigm.

It seems that this limitation cannot be lifted by any clever combinations of blocking and non-blocking molecules; perhaps this can be even proved formally, but I haven't tried learning the formal tools for that.
I just tried to implement this but could not find a combination of reactions that will accomplish this.

For instance, we could try emitting a blocking molecule that reacts with `a`.
If `a` is absent, the emission will block.
So the absence of `a` in the soup can be translated into blocking of a function call.
However, no programming language is able to detect whether a function call has been blocked, because the function call is by definition a blocking call!
All we can do is to detect whether the function call has returned within a given time, but here we would like to return instantly with the information that `a` is present or absent.

Suppose we define a reaction using the molecule `a`, say `a() => ...`.
Even if we somehow establish that this reaction did not start within a certain time period, we cannot conclude that `a` is absent in the soup at that time!
It could happen that `a()` was present but got involved in some other reactions and was consumed by them, or that `a()` was present but the computer's CPU was simply so busy that our reaction could not yet start and is still waiting in a queue.

Another feature would be to introduce “inhibiting” conditions on reactions: a certain reaction can start when molecules `a` and `b` are present but no molecule `c` is present.
However, it is not clear that this extension of the chemical paradigm would be useful.
The solution based on a timeout appears to be sufficient in practice.

It appears plausible that the chemical machine cannot detect the absence of a molecule.
Since we can expect molecules to be emitted at random and unpredictable times by concurrently running processes, it is always possible that a certain molecule is, at a given time, not present in the soup but is about to be emitted by some reaction because the emitter has already been called and its task is already waiting in the queue.

The chemical machine forces the programmer to design the chemical laws in such a way that the result of the execution of the program is the same even if random delays were inserted at any point when a molecule is emitted or a reaction needs to be started.

2. Chemical soups running as different processes (either on the same computer or on different computers) are completely separate and cannot be pooled.

What we would like to do is to connect many chemical machines together, running perhaps on different computers, and to pool their individual soups into one large “common soup”.
Our program should then be able to emit lots of molecules into the common pool and thus organize a massively parallel, distributed computation, without worrying about which CPU computes what reaction.
However, in order to organize a distributed computation, we would need to split the tasks explicitly between the participating soups.
The organization and supervision of distributed computations, the maintenance of connections between machines, the handling of disconnections - all this remains the responsibility of the programmer and is not handled automatically by the chemical machine.

In principle, a sufficiently sophisticated runtime engine could organize a distributed computation completely transparently to the programmer.
It remains to be seen whether it is feasible and/or useful to implement such a runtime engine.

3. Reactions are immutable: it is impossible to add more reactions at run time to an existing reaction site.
(This limitation is enforced in `Chymyst` by making reaction sites immutable and invisible to the user.)

Once a reaction site declares a certain molecule as an input molecule for some reactions, it is impossible to add further reactions that consume this molecule.

However, it is possible to declare a reaction site with reactions computed at run time.
Since reactions are local values (as are molecule emitters), users can first create any number of reactions and store these reactions in an array, before declaring a reaction site with these reactions.
Once all desired reactions have been assembled, users can declare a reaction site that uses all the reactions from the array.

As an (artificial) example, consider the following pattern of reactions:

```scala
val finished = m[Unit]
val a = m[Int]
val b = m[Int]
val c = m[Int]
val d = m[Int]

site(
go { case a(x) => b(x+1) },
go { case b(x) => c(x+1) },
go { case c(x) => d(x+1) },
go { case d(x) => if (x>100) finished() else a(x+1) }
)

a(10)

```

When this is run, the reactions will cycle through the four molecules `a`, `b`, `c`, `d` while incrementing the value each time, until the value 100 or higher is reached by the molecule `d`.

Now, suppose we need to write a reaction site where we have `n` molecules and `n` reactions, instead of just four.
Suppose that `n` is a runtime parameter.
Since molecule emitters and reactions are local values, we can simply create them and store in a data structure:


```scala
val finished = m[Unit]
val n = 100 // `n` is computed at run time

// array of molecule emitters:
val emitters = (0 until n).map( i => new M[Int](s"a_$i"))
// this is equivalent to declaring:
// val emitters = Seq(
//    new M[Int]("a_0"),
//    new M[Int]("a_1"),
//    new M[Int]("a_2"),
//    ...
// )

// array of reactions:
val reactions = (0 until n).map{ i =>
  // create the i-th reaction with index
  val iNext = if (i == n-1) 0 else i+1
  val a = emitters(i) // We must define molecule emitters `a`
  val aNext = emitters(iNext) // and `aNext` as explicit local values,
  go { case a(x) => // because `case emitters(i)(x)` won't compile.
    if (i == n-1 && x > 100) finished() else aNext(x+1)
  }
}

// write the reaction site
site(reactions:_*)

// emit the first molecule
emitters(0)(10)

```


# Reaction constructors

Chemical reactions are static - they must be specified at compile time and cannot be modified at runtime.
`JoinRun` goes beyond this limitation, since reactions in `JoinRun` are values created at run time.
For instance, we could create an array of molecules and reactions, where the size of the array is determined at run time.

However, reactions will not be activated until a reaction site is made by calling `site`, which we can only do once.
(We cannot write a second reaction site using an input molecule that is already bound to a previous reaction site. More generally, we cannot modify a reaction site once it has been written.)

For this reason, reaction sites in `JoinRun` are still static in an important sense.
For instance, when we receive a molecule emitter `c` as a result of some computation, the reactions that can start by consuming `c` are already fixed.
We can neither disable these reactions nor add another reaction that will also consume `c`.

Nevertheless, we can achieve some more flexibility in defining reactions at runtime.
There are several tricks we can use:

- define new reactions by a closure that takes arguments and returns new molecule emitters
- define molecules whose values contain other molecule emitters, and use them in reactions
- define molecules whose values are functions that manipulate other molecule emitters
- incrementally define new molecules and new reactions, store them in data structures, and assemble a final reaction site later (see the example above in the section about limitations of the chemical machine)

### Packaging a reaction in a function with parameters

Since molecule emitters are local values that close over their reaction sites, we can easily define a general “1-molecule reaction constructor” that creates an arbitrary reaction with a single input molecule.

```scala
def makeReaction[T](reaction: (M[T],T) => Unit): M[T] = {
  val a = new M[T]("auto molecule 1") // the name is just for debugging
  site( go { case a(x) => reaction(a, x) } )
  a
}

```

Since `reaction` is an arbitrary function, it can emit further molecules if needed.
In this way, we implemented a “reaction constructor” that can create an arbitrary reaction involving one input molecule.

Similarly, we could create reaction constructors for more input molecules:

```scala
def makeReaction2[T1,T2](reaction: (M[T1],T1,M[T2],T2) => Unit): (M[T1],M[T2]) = {
  val a1 = new M[T1]("auto molecule 1")
  val a2 = new M[T1]("auto molecule 2")
  site( go { case a1(x1) + a2(x2) => reaction(a1, x1, a2, x2) } )
  (a1,a2)
}

```

### Packaging a reaction in a molecule

In the previous example, we have encapsulated the information about a reaction into a closure.
Since molecules can carry values of arbitrary types, we could put that closure onto a molecule.
In effect, the result is a “universal molecule” that can define its own reaction.
(However, the reaction can have only one molecule as input.)

```scala
val u = new M[Unit => Unit)]("universal molecule")
site( go { case u(reaction) => reaction() } )

```

To use this “universal molecule”, we need to supply a reaction body and put it into the molecule while emitting.
In this way, we can emit the molecule with different reactions.

```scala
val p = m[Int]
val q = m[Int]
// make a reaction u(x) => p(123)+q(234) and emit u
u({ _ => p(123) + q(234) })
// make a reaction u(x) => p(0) and emit u
u({ _ => p(0) })

```

This example is artificial and not very useful; it just illustrates some of the possibilities that the chemical paradigm offers us.

## Working with an external asynchronous APIs

Suppose we are working with an external library (such as HTTP or database access) that gives us asynchronous functionality via Scala's `Future` values.
In order to use such libraries together with `JoinRun`, we need to be able to convert between `Future`s and molecules.
The `JoinRun` library provides a basic implementation for this functionality.

### Attaching molecules to futures

The first situation is when the external library produces a future value `fut : Future[T]`, and we would like to automatically emit a certain molecule `m` when this `Future` is successfully resolved.
This is as easy as doing a `fut.map( m(123) )` on the future.
The library has helper functions that add implicit methods to `Future` in order to reduce boilerplate in the two typical cases:

- the molecule needs to carry the same value as the result value of the future: `fut & m`
- the molecule needs to carry a different value: `fut + m(123)`

### Attaching futures to molecules

The second situation is when an external library requires us to pass a future value that we produce.
Suppose we have a reaction that will eventually emit a molecule with a result value.
We now need to convert the emission event into a `Future` value, resolving to that result value when the molecule is emitted.

This is implemented by the `Library.moleculeFuture` method.
This method will create a new molecule and a new future value.
We can then use this molecule as output in some reaction.

```scala
val a = m[Int]

val (result: M[String], fut: Future[String]) = moleculeFuture[String]
// emitting the molecule result(...) will resolve "fut"

site( go { case a(x) => result(s"finished: $x") } ) // we define our reaction that will eventually emit "result(...)"

ExternalLibrary.consumeUserFuture(fut) // the external library takes our value "fut" and does something with it

// Here should be some chemistry code that eventually emits `a` to start the reaction above.
```

