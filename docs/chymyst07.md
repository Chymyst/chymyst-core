# Patterns of concurrency

To get more familiar with programming the chemical machine, let us now implement a number of simple concurrent programs.
These programs are 

## Background jobs

A basic asynchronous task is to start a long background job and get notified when it is done.

A chemical model is easy to invent: the reaction needs no data to start (the calculation can be inserted directly in the reaction body).
So we define a reaction with a single non-blocking input molecule that carries a `Unit` value.
The reaction will consume the molecule, do the long calculation, and then emit a `finished(...)` molecule that carries the result value on it.

A convenient implementation is to define a function that will return an emitter that starts the job.

```scala
/**
* Prepare reactions that will run a closure and emit a result upon its completion.
*
* @tparam R The type of result value
* @param closure The closure to be run
* @param finished A previously bound non-blocking molecule to be emitted when the calculation is done
* @return A new non-blocking molecule that will start the job
*/
def submitJob[R](closure: Unit => R, finished: M[R]): M[R] = {
  val startJobMolecule = new M[Unit]

  site( go { case startJobMolecule(_) =>
    val result = closure()
    finished(result) }
   )

   startJobMolecule
}

```

The `finished` molecule should be bound to another reaction site.

Another implementation of the same idea will put the `finished` emitter into the molecule value, together with the closure that needs to be run.

However, we lose some polymorphism since Scala values cannot be parameterized by types.
The `startJobMolecule` cannot have type parameters and has to accept `Any` as a type:

```scala
val startJobMolecule = new M[(Unit => Any, M[Any])]

site(
  go {
    case startJobMolecule(closure, finished) =>
      val result = closure()
      finished(result)
  }
)

```

A solution to this difficulty is to create a method that is parameterized by type and returns a `startJobMolecule`:

```scala
def makeStartJobMolecule[R]: M[(Unit => R, M[R])] = {
  val startJobMolecule = new M[(Unit => R, M[R])]

  site(
    go {
      case startJobMolecule(closure, finished) =>
        val result = closure()
        finished(result)
   }
  )
  startJobMolecule
}

```

## Waiting forever

Suppose we want to implement a function `wait_forever()` that blocks indefinitely, never returning.

The chemical model is that a blocking molecule `wait` reacts with another, non-blocking molecule `godot`; but `godot` never appears in the soup.

We also need to make sure that the molecule `godot()` is never emitted into the soup.
So we declare `godot` locally within the scope of `wait_forever`, where we'll emit nothing into the soup.

```scala
def wait_forever: B[Unit, Unit] = {
  val godot = m[Unit]
  val waiting_for = b[Unit, Unit]

  site( go { case  waiting_for(_, r) + godot(_) => r() } )
  // forgot to emit `godot` here, which is key to starve this reaction.
  waiting_for
}

```

The function `wait_forever` will return a blocking molecule emitter that, when called, will block forever, never returning any value.

## Rendezvous, or `java.concurrent.Exchanger`

The "rendezvous" problem is to implement two concurrent processes that perform some computations and wait for each other like this:

| Process 1 | |  Process 2 |
| --- | --- | --- |
| `val x1 =` compute something | | `val x2 =` compute something |
| send `x1` to Process 2, wait for reply | | send `x2` to Process 1, wait for reply |
| `val y1 =` what Process 2 computed as its `x2` | | `val y2 =` what Process 1 computed as its `x1` |
| `val z = further_computations_1(y1)` | | `val z = further_computations_2(y2)` |

Let us now figure out the chemistry that will solve this problem.

The two processes must be reactions (since any computation that runs in the chemical machine is a reaction).
These reactions must start by consuming some initial molecules.
Let us start by defining these molecules and reactions, leaving undefined places for the next steps:

```scala
val begin1 = m[Unit]
val begin2 = m[Unit]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    ??? // send x1 to Process 2 somehow
    val y1 = ??? // receive value from Process 2
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    ??? // send x2 to Process 1 somehow
    val y2 = ??? // receive value from Process 2
    val z = further_computation_2(y2)
   }
)
begin1() + begin2() // emit both molecules to enable starting the two reactions

```

So far, so good.
Look at what happens in in Process 1 after `x1` is computed.
The next step is to send the value `x1` to Process 2.
The only way we can send data to any other process is by emitting a molecule.
Therefore, here we must be emitting _some molecule_.

Now, either Process 1 or Process 2 is already running by this time, and so it won't help if we emit a molecule that Process 2 consumes as input.
Therefore, we must emit a new molecule that neither Process 1 nor Process 2 consume as input.

Also note that each process must wait until the other process sends back its value. 
Therefore, the new molecule must be a blocking molecule.

Let's say that each process would emit its own blocking molecule at this point, and let's call these molecules `barrier1` and `barrier2`.
The code will look like this:

```scala
val begin1 = m[Unit]
val begin2 = m[Unit]

val barrier1 = b[Unit,Unit]
val barrier2 = b[Unit,Unit]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    barrier1(x1) 
    ??? // send x1 to Process 2 somehow
    val y1 = ??? // receive value from Process 2
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    barrier2(x2)
    ??? // send x2 to Process 1 somehow
    val y2 = ??? // receive value from Process 2
    val z = further_computation_2(y2)
   }
)
begin1() + begin2() // emit both molecules to enable starting the two reactions

```

Now we note that blocking molecules can receive reply values.
Therefore, the call to `barrier1` may receive a reply value.
This is exactly what we need!
Let's make `barrier1` return the value that Process 2 sends, and vice versa.

```scala
val begin1 = m[Unit]
val begin2 = m[Unit]

val barrier1 = b[Int,Int]
val barrier2 = b[Int,Int]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    val y1 = barrier1(x1) // receive value from Process 2 
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    val y2 = barrier2(x2) // receive value from Process 2
    val z = further_computation_2(y2)
   }
)
begin1() + begin2() // emit both molecules to enable starting the two reactions

```

At this point, the molecules `barrier1` and `barrier2` are not yet consumed by any reactions.
We now need to define some reaction that consumes these molecules.
It is clear that what we need is a reaction that exchanges the values these two molecules carry.
The easiest solution is to just let these two molecules react with each other.
The reaction will then reply to both of them, passing the values as required. 

```scala
go { case barrier1(x1, reply1) + barrier2(x2, reply2) => reply1(x2); reply2(x1) }

```

The final code looks like this:

```scala
val begin1 = m[Unit]
val begin2 = m[Unit]

val barrier1 = b[Int,Int]
val barrier2 = b[Int,Int]

site(
  go { case begin1(_) =>
    val x1 = 123 // some computation
    val y1 = barrier1(x1) // receive value from Process 2 
    val z = further_computation_1(y1)
   },
  go { case begin2(_) =>
    val x2 = 456 // some computation
    val y2 = barrier2(x2) // receive value from Process 2
    val z = further_computation_2(y2)
   },
   go { case barrier1(x1, reply1) + barrier2(x2, reply2) => reply1(x2); reply2(x1) }
)
begin1() + begin2() // emit both molecules to enable starting the two reactions

```

This functionality is essentially that of `java.concurrent.Exchanger`.

## Cigarette smokers

The "Cigarette smokers" problem is to implement four concurrent processes that coordinate assembly line operations to manufacture cigarettes with the 
workers smoking the individual cigarettes they manufacture. One process represents a supplier of ingredients on the assembly line, which we may call the 
pusher. The other processes are three smokers, each having an infinite supply of only one of the three required ingredients, which are matches, paper, and 
tobacco. We may give names to the smokers/workers as Keith, Slash, and Jimi. 

The pusher provides at random time intervals one unit each of two required ingredients (for example matches and paper, or paper and tobacco). The pusher is not allowed to coordinate with the smokers to use knowledge of which smoker 
needs which ingredients, he just supplies two ingredients at a time on a conveyor belt. We assume that the pusher has an infinite stream of the three 
ingredients available to him. The real life example is that of a core operating systems service having to schedule and provide limited distinct resources to 
other services where coordination of scarce resources is required.
 
Each smoker selects two ingredients, rolls up a cigarette using the third ingredient that complements the list, lits it up and smokes it. It is necessary
 for the smoker to finish his cigarette before the pusher supplies the next two ingredients (a timer can simulate the smoking activity).


We model the processes as follows:

| Supplier   | | Smoker 1   | |  Smoker 2    | |  Smoker 3 |
| --- | --- | --- | --- | --- | --- |  --- |
| select 2 random ingredients | | pick tobacco and paper | | pick tobacco and matches | | pick matches and paper |


Let us now figure out the chemistry that will solve this problem. We can think of the problem as a concurrent producer consumer queue with three competing 
consumers and the supplier simply produces random pairs of ingredients into the queue. For simplicity, we assume the queue has capacity 1.

It also helps to draw parallels with the Dining Philosophers problem realizing that we can think of three philosophers as consumers and the constraint on 
matching up the three ingredients differently for the three smokers is a bit analog to the contention of selecting the forks for the Dining Philosophers.

As for the philosophers, we will need to keep track of a pair of states (molecules) for each consumer or smoker, when one is waiting for ingredients (in need
 of a fix) and when one when the smoker's requirement is fulfilled (having his fix). This parallels the eating-thinking dual state.
 
It is important to think of a suitable data model to capture the state of the world for the problem, so we need to know when to stop and count how many 
cycles we go through, if we want to stop the computation. It may be useful to keep track of how many ingredients have been shipped or consumed but this does 
not look to be important for a minimal solution (it is included for readers interested in inventory cost tracking and to represent that the state is carried 
across the reactions and is not stored as mutable data anywhere).

It is important to represent the ingredients as individual molecules and not as pairs of molecules to properly represent the 3-way dependence of the smokers 
on the pairs of ingredients. We will represent the ingredients dynamically by representing the producer consumer queue, so we call the molecules shipments.

One must ensure that we satisfy the chemistry laws, notably avoid unavoidable nondeterminism, which can occur if two distinct reactions in the same site 
contain the same input molecules. This guides towards a solution where the pusher is presented in a single reaction, not three. We capture that with the 
first reaction in the solution.

The final code looks like this:

```scala
val supplyLineSize = 10
    def smoke(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)

    case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
    case class SupplyChainState(inventory: Int, shipped: ShippedInventory)

    val pusher = new M[SupplyChainState]("Pusher has delivered some unit")
    val KeithsFix = new E("Keith is smoking having obtained tobacco and matches and rolled a cigarette")
    val SlashsFix = new E("Slash is smoking having obtained tobacco and paper and rolled a cigarette")
    val JimisFix = new E("Jimi is smoking having obtained matches and paper and rolled a cigarette")

    val KeithInNeed = new E("Keith is in need of tobacco and matches")
    val SlashInNeed = new E("Slash is in need of tobacco and paper")
    val JimiInNeed = new E("Jimi is in need of matches and paper")

    val tobaccoShipment = new M[SupplyChainState]("tobacco shipment")
    val matchesShipment = new M[SupplyChainState]("matches shipment")
    val paperShipment = new M[SupplyChainState]("paper shipment")

    val pusherDone = new E("done")
    val check = new EE("check") // blocking Unit, only blocking molecule of the example.

    site(tp) (
      go { case pusher(SupplyChainState(n, ShippedInventory(t, p, m))) =>
        scala.util.Random.nextInt(3) match { // select the 2 ingredients randomly
          case 0 =>
            val s = SupplyChainState(n-1, ShippedInventory(t+1, p, m+1))
            tobaccoShipment(s)
            matchesShipment(s)
          case 1 =>
            val s = SupplyChainState(n-1, ShippedInventory(t+1, p+1, m))
            tobaccoShipment(s)
            paperShipment(s)
          case _ =>
            val s = SupplyChainState(n-1, ShippedInventory(t, p+1, m+1))
            matchesShipment(s)
            paperShipment(s)
        }
        if (n == 1) pusherDone()
      },
      go { case KeithsFix(_) => KeithInNeed() },
      go { case SlashsFix(_) => SlashInNeed() },
      go { case JimisFix(_) => JimiInNeed() },
      go { case pusherDone(_) + check(_, r) => r() },

      go { case KeithInNeed(_) + tobaccoShipment(s) + matchesShipment(_) =>
        KeithsFix(); smoke(); pusher(s)
      },
      go { case SlashInNeed(_) + tobaccoShipment(s) + paperShipment(_) =>
        SlashsFix(); smoke(); pusher(s)
      },
      go { case JimiInNeed(_) + matchesShipment(s) + paperShipment(_) =>
        JimisFix(); smoke(); pusher(s)
      }
    )

    KeithInNeed(()) + SlashInNeed(()) + JimiInNeed(())
    pusher(SupplyChainState(supplyLineSize, ShippedInventory(0,0,0)))
    check()
```