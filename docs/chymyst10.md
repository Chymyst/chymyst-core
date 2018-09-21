# Distributed programming in the chemical machine

All programs considered in the previous chapters ran within a single JVM process on a single (possibly multi-core) computer.
We now consider the extension of the chemical machine paradigm to distributed programming, where programs run concurrently and in parallel on several OS processes and/or on several computers.
The resulting paradigm is the distributed chemical machine (DCM).
Similarly to how the single-process chemical machine (CM) provides a declarative approach to concurrent programming, the DCM provides a declarative approach to distributed concurrent programming.

In what way is a CM program declarative?
In CM, the programmer merely specifies that certain computations need to be run concurrently and in parallel.
Computations are started automatically as long as the required input data is available.
The input data needs to be specially marked as "destined for" concurrent computations (i.e. emitted on molecules).
However, the code does not explicitly start new tasks at certain times, nor does it need explicitly to wait for or to synchronize processes that run in parallel.

Similarly, the DCM requires the programmer merely to declare certain molecules as having the property of being distributed.
The runtime of the DCM will automatically distribute the chosen data across all JVM processes that participate in a given cluster.
We will call each of these processes a **DCM peer** running on the cluster.
From the programmer's point of view, it makes no difference whether a DCM peer is a JVM process that runs on the same machine or on a different machine within the cluster.

So, the DCM extends the CM by introducing **distributed molecules** in addition to **local molecules**. 
The DCM runtime will also run local reactions with local molecules in the same way CM does.
The programmer's job remains to declare certain molecules and reactions, and to emit certain initial molecules, such as to implement the required application logic.

## Cluster configuration

The coordination of distibuted computations for DCM is performed by [Apache ZooKeeper](https://zookeeper.apache.org/).
When a DCM program runs on a cluster, each participating DCM peer must be connected to a running ZooKeeper instance.

The information about connecting to the cluster, such as the ZooKeeper URL and other parameters, are encapsulated in a value of type `ClusterConfig`.
The user code must create this value before a DCM program can be run.

```scala
implicit val clusterConfig = ClusterConfig(
  url = "localhost:2345",
  user = "zkUser",
  password = "zkPassword"
)

```

## Distributed molecules

An emitter for a distributed molecule is defined using the class constructor `DM` or the macro `dm`:

```scala
val a = dm[Int]
val c = new DM[Int]("c") // same as val c = dm[Int]

```

Distributed molecules differ from local molecules in two ways:

- they use a different emitter type: `DM` instead of `M`
- defining a DM needs an implicit `ClusterConfig` value to be available in scope

Distributed molecules are always non-blocking.

Defining reactions and emitting a distributed molecule is done in the same way as with local molecules:

```scala
val a = dm[Int] // Distributed molecule.
val c = m[Int] // Local molecule.
site( go { case a(x) + c(y) ⇒ ... })

(1 to 100).foreach(a)

```

Reactions may consume and emit distributed and/or local molecules in any combination.
The data carried by distributed molecules will be stored on the ZooKeeper and, in this way, made available to all the DCM peers in the cluster.
Thus, distributed molecules are emitted _into the cluster_, not into a local reaction site.
At any time, any DCM peer may start a reaction that consumes any of the available distributed molecules.

## Example: distributed map/reduce

In [Chapter 2](chymyst02.md) we have implemented concurrent map/reduce with the following code:

```scala
// Declare the "map" and the "reduce" functions.
def f(x: Int): Int = x * x
def reduceB(acc: Int, x: Int): Int = acc + x

val arr = 1 to 100

// Declare molecule emitters.
val carrier = m[Int]
val interm = m[(Int, Int)]

// Declare the reaction for the "map" step.
site(
  go { case carrier(x) ⇒ val res = f(x); interm((1, res)) }
)

// Declare the reaction for the "reduce" step,
// keeping track of the number of partially reduced items.
site(
  go { case interm((n1, x1)) + interm((n2, x2)) ⇒
    val x = reduceB(x1, x2)
    val n = n1 + n2
    if (n == arr.length) println(x) else interm((n, x))
  }
)

// Emit initial molecules.
arr.foreach(i ⇒ carrier(i))

```

To make this program distributed, we begin by defining the `carrier()` and `interm()` molecules as DMs.
The only changes in the code are the different emitter types and the addition of an implicit `ClusterConfig`:

```scala
implicit val clusterConfig = ??? // Perhaps, read some configuration files here.

// Declare the "map" and the "reduce" functions.
def f(x: Int): Int = x * x
def reduceB(acc: Int, x: Int): Int = acc + x

val arr = 1 to 100

// Declare distributed molecule emitters.
val carrier = dm[Int]
val interm = dm[(Int, Int)]

// Declare the reaction for the "map" step.
site(
  go { case carrier(x) ⇒ val res = f(x); interm((1, res)) }
)

// Declare the reaction for the "reduce" step,
// keeping track of the number of partially reduced items.
site(
  go { case interm((n1, x1)) + interm((n2, x2)) ⇒
    val x = reduceB(x1, x2)
    val n = n1 + n2
    if (n == arr.length) println(x) else interm((n, x))
  }
)

// Emit initial molecules.
arr.foreach(carrier)

```

With virtually no code changes, we have created a first distributed application!

To clarify how the DCM will run this program, consider several DCM peers running exactly the same program code.
Each of the DCM peers will emit 100 copies of the molecules `carrier()` carrying different values.
Since the molecule `carrier()` is distributed, all these copies will be consumed by all DCM peers in parallel and in arbitrary order to start the "map" reaction.
Each DCM peer may run several concurrent copies of the "map" reaction; the code leaves this decision to the implementation.
In any case, the result will be that a number of molecules `interm()` are emitted back into the cluster.

After that, the "reduce" reactions will be started by the DCM peers, all running in parallel.
All the `interm()` molecules will be consumed until the final result is obtained.

This DCM program computes a final `Int` value and prints it.
However, two problems remain with this code:

- All the participating DCM peers run the same code, emitting the initial `carrier()` molecules via `arr.foreach(carrier)`. We need to run `arr.foreach(carrier)` only once, rather than many times.
- The DCM peer that performs the final "reduce" step is chosen non-deterministically. We need more control over the code that obtains the final value; for example, we need to send this value to a specific, designated DCM peer.

We can implement these requirements if we add a reaction that reports the final value.
While this reaction is defined in all DCM peers, a single designated peer (call it the "driver") will actually run that reaction.
The same DCM peer will emit the initial molecules.
We omit the code that defines `isDriver` to specify which DCM peer is the driver; our intention is to have `isDriver == true` only in a single, designated DCM peer. 

```scala
implicit val clusterConfig = ??? // Perhaps, read some configuration files here.

val isDriver: Boolean = ??? // Read from configuration.

// Declare the "map" and the "reduce" functions.
def f(x: Int): Int = x * x
def reduceB(acc: Int, x: Int): Int = acc + x

val arr = 1 to 100

// Declare distributed molecule emitters.
val carrier = dm[Int]
val interm = dm[(Int, Int)]

// Declare the reaction for the "map" step.
site(
  go { case carrier(x) ⇒ val res = f(x); interm((1, res)) }
)

val result = dm[Int] // Distributed molecule.

// Declare the reaction for the "reduce" step,
// keeping track of the number of partially reduced items.
site(
  go { case interm((n1, x1)) + interm((n2, x2)) ⇒
    val x = reduceB(x1, x2)
    val n = n1 + n2
    if (n == arr.length) result(x) else interm((n, x))
  }
)

site(
  go { case result(x) if isDriver ⇒ println(x) }
)

// Emit initial molecules.
if (isDriver) arr.foreach(carrier)

```

The final result will now be printed on the designated DCM peer, regardless of where the final "reduce" step was performed.
As before, all DCM peers run identical code; only the configuration files or the command-line options may be set differently at deployment time.

## Identifying the distributed data across DCM peers

### The requirement of identical reaction code

Just as with local molecules, it is necessary to bind distributed molecules to reaction sites,
which is done by defining reactions that consume them.

Reactions that consume DMs must have _the same Scala code_ in every DM peer.
In this way, the DCM can identify the distributed data and share it automatically across instances.
The distributed data is intended to be consumed by an arbitrary participating DM peer,
which is safe only if every DM peer runs _the same reaction code_ consuming the data. 

To see this, consider two DCM peers.
Suppose that the first DCM peer runs a program such as

```scala
val a = dm[Int]
val c = dm[Int]
val f: Int ⇒ Int = ???
site( go { case a(x) ⇒ c(f(x)) } ) // Some computation.
...

```

while the second DCM peer runs

```scala
val a = dm[Int]
val c = dm[Int]
val g: Int ⇒ Int = ???
site( go { case a(x) ⇒ c(g(x)) } ) // Another computation.
...

```

Both peers are be able to consume a copy of the distributed molecule `a()`.
However, the resulting computations will be different, and the programmer has no control over the choice of peer that consumes various copies of `a()`.
This kind of non-determinism appears to be undesirable.
Therefore, `Chymyst` requires that all DCM peers should define identical Scala code for reactions that consume a given distributed molecule such as `a()`. 

If two DCM peers run two different programs as shown above, the DCM will consider their definitions of `a()` as incompatible.
The result will be that the molecule `a()` will _not_ be shared between these two DCM peers.

It is unimportant that both programs call the molecule `"a"`, or that the local variable is named `a`.
Molecule names and variable names do not determine the semantics of chemical programs.

### Reactions defined in local scopes

Now consider two DCM peers that ???

```scala
val a = dm[Int]
val c = dm[Int]
val f: Int ⇒ Int = ???
site( go { case a(x) ⇒ c(f(x)) } ) // Some computation.
...

```



First, the DCM peers need to identify which of the locally defined DM emitters corresponds to `a()`.

It is not possible to use the _name_ `"a"` of this molecule, since there may be several molecules named `a` in various scopes.
In `Chymyst`, molecule names are used for debugging purposes only, and do not affect any computations.

