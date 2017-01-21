<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

## Cigarette smokers

The "Cigarette smokers" problem is to implement four concurrent processes that coordinate assembly line operations to manufacture cigarettes with the 
workers smoking the individual cigarettes they manufacture. One process represents a supplier of ingredients on the assembly line, which we may call the 
pusher. The other processes are three smokers, each having an infinite supply of only one of the three required ingredients, which are matches, paper, and 
tobacco. We may give names to the smokers/workers as Keith, Slash, and Jimi. 

The pusher provides at random time intervals one unit each of two required ingredients (for example matches and paper, or paper and tobacco). The pusher is not allowed to coordinate with the smokers to use knowledge of which smoker 
needs which ingredients, he just supplies two ingredients at a time. We assume that the pusher has an infinite supply of the three 
ingredients available to him. The real life example is that of a core operating systems service having to schedule and provide limited distinct resources to 
other services where coordination of scarce resources is required.
 
Each smoker selects two ingredients, rolls up a cigarette using the third ingredient that complements the list, lights it up and smokes it. It is necessary
for the smoker to finish his cigarette before the pusher supplies the next two ingredients (a timer can simulate the smoking activity). We can think of the 
smoker shutting down the assembly line operation until he is done smoking.


We model the processes as follows:

| Supplier   | | Smoker 1   | |  Smoker 2    | |  Smoker 3 |
| --- | --- | --- | --- | --- | --- |  --- |
| select 2 random ingredients | | pick tobacco and paper | | pick tobacco and matches | | pick matches and paper |


Let us now figure out the chemistry that will solve this problem. We can think of the problem as a concurrent producer consumer queue with three competing 
consumers and the supplier simply produces random pairs of ingredients into the queue. For simplicity, we assume the queue has capacity 1, which is an 
assumption in the statement of the problem (smoker shuts down factory operation while he takes a smoke break, thus pausing the pusher).
 
It is important to think of a suitable data model to capture the state of the world for the problem, so we need to know when to stop and count how many 
cycles we go through, if we want to stop the computation. It may be useful to keep track of how many ingredients have been shipped or consumed but this does 
not look to be important for a minimal solution. We include inventory tracking because of some logging we do to represent the concurrent activities more 
explicitly; this inventory tracking does add a bit of complexity.

For counting, we will use a distinct molecule `count` dedicated just to that and emit it initially with a constant number. We also use a blocking molecule 
`check` that we emit when we reach a `count` of 0. This approach is same as in several other examples discussed here.

```scala
val count = m[Int]
val check = b[Unit, Unit]

site(tp) ( // reactions
  go { case pusher(???) + count(n) if n >= 1 => ??? // the supply reaction TBD
       count(n-1) // let us include this decrement now.
  },
  go { case count(0) + check(_, r) => r() }  // note that we use mutually exclusive conditions on count in the two reactions.
)
// emission of initial molecules in chemistry follows
// other molecules to emit as necessary for the specifics of this problem
count(supplyLineSize) // if running as a daemon, we would not use the count molecule and let the example/application run for ever.
check()

```

We now introduce one molecule for each smoker and their role should be symmetrical while capturing the information about their ingredient input requirements.
 Let us give names to smoker molecules as `Keith`, `Slash`, and `Jimi`. Let us assign one molecule per ingredient: `paper`, `matches`, and `tobacco`. This 
 will represent the last three reactions. We need to emit the molecules for `Keith`, `Slash`, and `Jimi` on start up, which can be combined (`Keith(()) + Slash(()) + Jimi(())`).

Here, we write up a helper function `enjoyAndResume` that is a refactored piece of code that is common to the three smokers, who, when receiving ingredients, make the 
 cigarette, smoke it while taking a break off work, shutting down the operations and then resuming operation to `pusher` when done, which is required as the 
 smoker must notify the `pusher` when to resume. The smoking break is a simple waste of time represented by a sleep. The smoker molecule must re-emit
 itself once done to indicate readiness of the smoker to get back to work to process the next pair of ingredients. 
 
 Notice here that we capture a state of the shipped inventory from the ingredient molecules, all of which carry 
 the value of the inventory and echo it back to the pusher so that he knows where he is at in his bookkeeping; the smokers collaborate and don't lie so 
 simply echo back the inventory as is (note that this is not necessary if we are not interested in tracking down how many ingredients have been used in 
 manufacturing).

```scala
def smokingBreak(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)
def enjoyAndResume(s: ShippedInventory) = {
   smokingBreak()
   pusher(s)
}
site(tp) ( // reactions
  // other reactions ...
   go { case Keith(_) + tobacco(s) + matches(_) => enjoyAndResume(s); Keith() },
   go { case Slash(_) + tobacco(s) + paper(_) => enjoyAndResume(s); Slash() },
   go { case Jimi(_) + matches(s) + paper(_) => enjoyAndResume(s); Jimi()}
)
// other initial molecules to be emitted
Keith() + Slash() + Jimi()

```

Now, we need the `pusher` molecule to generate a pair of ingredients randomly at time intervals. Paying attention to the statement of the problem, we notice 
that he needs to wait for a smoker to be done, hence as stated before, we simply need to emit the `pusher` molecule from the smoker reactions (a signal in 
conventional 
terminology) and the `pusher` molecule should be emitted on start up and should respond to the count molecule to evaluate the deltas in the 
`ShippedInventory`; the active `pusher` can be thought of as representing an active factory so we must emit its molecule on start up.
 
 We represent the shipped inventory as a case class with a count for each ingredient, we call it 
`ShippedInventory`. We integrate counting as previously discussed, introduce the random selection of ingredient pairs and emit the molecules for the pair of ingredients.

```scala
case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
site(tp) (
    go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1 =>
      scala.util.Random.nextInt(3) match { // select the 2 ingredients randomly
        case 0 =>
          val s = ShippedInventory(t+1, p, m+1)
          tobaccoShipment(s)
          matchesShipment(s)
        case 1 =>
          val s =  ShippedInventory(t+1, p+1, m)
          tobaccoShipment(s)
          paperShipment(s)
        case _ =>
          val s = ShippedInventory(t, p+1, m+1)
          matchesShipment(s)
          paperShipment(s)
      }
      count(n-1)
    }
)
count(supplyLineSize) 
pusher(ShippedInventory(0,0,0))
// other initial molecules to be emitted (the smokers and check)
  
```

The final code looks like this:

```scala
val supplyLineSize = 10
def smokingBreak(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)

case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
// this data is only to demonstrate effects of randomization on the supply chain and make content of logFile more interesting.
// strictly speaking all we need to keep track of is inventory. Example would work if pusher molecule value would carry Unit values instead.

val pusher = m[ShippedInventory] // pusher means drug dealer, in classic Comp Sci, we'd call this producer or publisher.
val count = m[Int]
val KeithInNeed = new M[Unit]("Keith obtained tobacco and matches to get his fix") // makes for more vivid tracing, could be plainly m[Unit]
val SlashInNeed = new M[Unit]("Slash obtained tobacco and matches to get his fix") // same
val JimiInNeed = new M[Unit]("Jimi obtained tobacco and matches to get his fix") // same

val tobaccoShipment = m[ShippedInventory] // this is not particularly elegant, ideally this should carry Unit but pusher needs to obtain current state
val matchesShipment = m[ShippedInventory] // same
val paperShipment = m[ShippedInventory] // same

val check = b[Unit, Unit] // blocking Unit, only blocking molecule of the example.

val logFile = new ConcurrentLinkedQueue[String]

site(tp) (
  go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1 =>
    logFile.add(s"$n,$t,$p,$m") // logging the state makes it easier to see what's going on, curious user may put println here instead.
    scala.util.Random.nextInt(3) match { // select the 2 ingredients randomly
      case 0 =>
        val s = ShippedInventory(t+1, p, m+1)
        tobaccoShipment(s)
        matchesShipment(s)
      case 1 =>
        val s =  ShippedInventory(t+1, p+1, m)
        tobaccoShipment(s)
        paperShipment(s)
      case _ =>
        val s = ShippedInventory(t, p+1, m+1)
        matchesShipment(s)
        paperShipment(s)
    }
    count(n-1)
  },
  go { case count(0) + check(_, r) => r() },

  go { case KeithInNeed(_) + tobaccoShipment(s) + matchesShipment(_) =>
    smokingBreak(); pusher(s); KeithInNeed()
  },
  go { case SlashInNeed(_) + tobaccoShipment(s) + paperShipment(_) =>
    smokingBreak(); pusher(s); SlashInNeed()
  },
  go { case JimiInNeed(_) + matchesShipment(s) + paperShipment(_) =>
    smokingBreak(); pusher(s); JimiInNeed()
  }
)

KeithInNeed() + SlashInNeed() + JimiInNeed()
pusher(ShippedInventory(0,0,0))
count(supplyLineSize) // if running as a daemon, we would not use count and let the example/application run for ever.

check()

```

There is a harder, more general treatment of the cigarette smokers problem, which has the `pusher` in charge of the rate of availability of ingredients, not 
having to wait for smokers, which the reader may think of using a producer-consumer queue with unlimited buffer in classic treatment of the problem. The 
solution is provided in code. Let us say that the change is very simple, we just need
to have pusher emit the pusher molecule instead
of the smokers doing so.  The pausing in the assembly line needs to be done within the `pusher` reaction, otherwise it is the same solution.

## Readers Writer Locks

The problem here is to give access to a shared resource where only one thread can write to a resource at any time and multiple threads can read data from 
the same resource concurrently, provided that the writer thread and the reader threads are not in contention for the same resource. In other words, the 
writer thread can get access to the resource only if no reader thread has access to the resource and a reader thread can get access only if the writer 
thread has no access. This concurrency scenario has applicability in memory/disk pages in operating systems or databases; standard terminology use exclusive
write lock and shared read locks.

The main idea is to use a single writer molecule and a collection of reader molecules, each of which is distinguished by a distinct name or key. 
Accordingly, we need molecules `val reader = m[String]` and `val writer = m[String]`; we then need to emit these molecules as follows `readers.foreach(n => 
reader(n))` with `readers` being an arbitrary collection of distinct names and  `writer("exclusive-writer")`. So we emit multiple `reader` molecules and a 
single `writer` molecule into the soup. The reactions will have to re-emit any of these molecules each time the reaction consumes one.

We need to log events as we go along. What needs to be captured is the identity of the molecule, its name, and the action taken by the molecule acquiring a
 lock or releasing a lock. _We ensure that there is no name collision among the `reader` molecules and the `writer` molecule._ The `LockEvent` will have a 
 `toString` method to enable debugging or troubleshooting. We also need to block a current thread and simulate access to a critical section of code, which 
 we do with methods `visitCriticalSection` and `leaveCriticalSection` tracking such events in a `logFile = new ConcurrentLinkedQueue[LockEvent]` for debugging or unit testing.

```scala
sealed trait LockEvent {
  val name: String
  def toString: String
}
case class LockAcquisition(override val name: String) extends LockEvent {
  override def toString: String = s"$name enters critical section"
}
case class LockRelease(override val name: String) extends LockEvent {
  override def toString: String = s"$name leaves critical section"
}
val logFile = new ConcurrentLinkedQueue[LockEvent]

def useResource(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 4.0 + 1.0).toLong)
def visitCriticalSection(name: String): Unit = {
  logFile.add(LockAcquisition(name))
  useResource()
}
def leaveCriticalSection(name: String): Unit = {
  logFile.add(LockRelease(name))
  ()
}

```

We need to consider the ending of the simulation, which we do with a `count = m[Int]` non-blocking molecule and a `check = b[Unit, Unit]` molecule as is 
often done here. We make the arbitrary decision that we will count down number of lock acquisitions by the `writer` molecule in way similar to the supplier
 or pusher for the cigarettes problem.
 
Now, the fun begins: we need to model how many readers are using the resource concurrently, which we do with molecule `readerCount[Int]`. So let us start 
with a draft, using some count down logic with a `writer` molecule reaction, ignoring helper functions and case classes we introduced already:
 
```scala
val count = m[Int]
val readerCount = m[Int]
val check = b[Unit, Unit]
val readers = "ABCDEFGH".toCharArray.map(_.toString).toVector // vector of letters as Strings.

val reader = m[String]
val writer = m[String]

site(tp)(
  go { case writer(name) + readerCount(0) + count(n) if n > 0 =>
    visitCriticalSection(name)
    writer(name)
    count(n - 1)
    readerCount(0)
    leaveCriticalSection(name)
  },
  go { case count(0) + readerCount(0) + check(_, r) => r() }, // readerCount(0) condition ensures we end when all locks are released.

  go { case readerCount(n) + reader(name)  =>
    readerCount(n+1)
    visitCriticalSection(name)
    readerCount(n - 1)
    leaveCriticalSection(name) // undefined count
    reader(name) 
  }
)
readerCount(0)
readers.foreach(n => reader(n))
val writerName = "exclusive-writer"
writer(writerName)
count(supplyLineSize)

check()

```

It does not even compile! Macros code tell us _Unconditional livelock: Input molecules should not be a subset of output molecules, with all trivial 
matchers for_ `(readerCount, reader) go { case readerCount(n) + reader(name)`. What went wrong? Well, yes, sure enough, we consume two molecules and emit 
three! `readerCount(n+1)` and `readerCount(n - 1)` with `reader(name)` count as three. Let us introduce an intermediate molecule in between the emission of
 the two `readerCount` emissions as `readerExit = m[String]`, which we do not emit into soup on start up:
 
```scala
val count = m[Int]
val readerCount = m[Int]

val check = b[Unit, Unit]

val readers = "ABCDEFGH".toCharArray.map(_.toString).toVector // vector of letters as Strings.

val readerExit = m[String]
val reader = m[String]
val writer = m[String]

site(tp)(
  go { case writer(name) + readerCount(0) + count(n) if n > 0 =>
    visitCriticalSection(name)
    writer(name)
    count(n - 1)
    readerCount(0)
    leaveCriticalSection(name)
  },
  go { case count(0) + readerCount(0) + check(_, r) => r() }, // readerCount(0) condition ensures we end when all locks are released.

  go { case readerCount(n) + readerExit(name)  =>
    readerCount(n - 1)
    leaveCriticalSection(name) // undefined count
    reader(name)
  },
  go { case readerCount(n) + reader(name)  =>
    readerCount(n+1)
    visitCriticalSection(name)
    readerExit(name)
  }
)
readerCount(0)
readers.foreach(n => reader(n))
val writerName = "exclusive-writer"
writer(writerName)
count(supplyLineSize)

check()

```

The simulation does not stop... We could replace `visitCriticalSection` and `leaveCriticalSection` with some tracing. What we see is that the `reader` 
molecules are reacting all the time continuously but the `writer` molecule never does. This is a starvation problem, the site is always consuming the 
`reader` molecule reactions as it gets data all the time and the readerCount might not reach a value of 0.

Let us have an exiting reader molecule yield by waiting for more incoming work to arrive before getting itself to read again, so we introduce 
`waitForUserRequest()` before emitting `reader(name)`:

```scala
go { case readerCount(n) + readerExit(name)  =>
  readerCount(n - 1)
  leaveCriticalSection(name) // undefined count
  waitForUserRequest() // gives a chance to writer to do some work
  reader(name)
}

```

It now works, so let's assemble the complete solution ignoring unit testing, which can be found in code. Unit test verifies no reader lock acquisition 
while a writer lock is active and no double locking by any lock prior to releasing.

```scala
val supplyLineSize = 25 // make it high enough to try to provoke race conditions, but not so high that sleeps make the test run too slow.

sealed trait LockEvent {
  val name: String
  def toString: String
}
case class LockAcquisition(override val name: String) extends LockEvent {
  override def toString: String = s"$name enters critical section"
}
case class LockRelease(override val name: String) extends LockEvent {
  override def toString: String = s"$name leaves critical section"
}
val logFile = new ConcurrentLinkedQueue[LockEvent]

def useResource(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 4.0 + 1.0).toLong)
def waitForUserRequest(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 4.0 + 1.0).toLong)
def visitCriticalSection(name: String): Unit = {
  logFile.add(LockAcquisition(name))
  useResource()
}
def leaveCriticalSection(name: String): Unit = {
  logFile.add(LockRelease(name))
  ()
}

val count = m[Int]
val readerCount = m[Int]

val check = b[Unit, Unit] // blocking Unit, only blocking molecule of the example.

val readers = "ABCDEFGH".toCharArray.map(_.toString).toVector // vector of letters as Strings.
// Making readers a large collection introduces lots of sleeps since we count number of writer locks for simulation and the more readers we have
// the more total locks and total sleeps simulation will have.

val readerExit = m[String]
val reader = m[String]
val writer = m[String]

site(tp)(
  go { case writer(name) + readerCount(0) + count(n) if n > 0 =>
    visitCriticalSection(name)
    writer(name)
    count(n - 1)
    readerCount(0)
    leaveCriticalSection(name)
  },
  go { case count(0) + readerCount(0) + check(_, r) => r() }, // readerCount(0) condition ensures we end when all locks are released.

  go { case readerCount(n) + readerExit(name)  =>
    readerCount(n - 1)
    leaveCriticalSection(name)
    waitForUserRequest() // gives a chance to writer to do some work
    reader(name)
  },
  go { case readerCount(n) + reader(name)  =>
    readerCount(n+1)
    visitCriticalSection(name)
    readerExit(name)
  }
)
readerCount(0)
readers.foreach(n => reader(n))
val writerName = "exclusive-writer"
writer(writerName)
count(supplyLineSize)

check()

```

## Dining savages

We have a tribe of savages that practises cannibalism. Accordingly, the tribe is assumed to have a large supply of prisoners or victims that will make it 
into a pot for communal eating. The concurrency rules are as follows. The pot has capacity for `m` victims at the most. It is generally assumed that there 
is an arbitrary number `k` of savages who may take turn eating one serving from the pot, which equates to a single victim. In the current presentation, we 
will assume that we have three eating savages, so we may associate a particular chemical reaction to a single eating savage. 

The savages may start taking turn eating only when the cook is not busy, which is from the time the pot is at full capacity until it is empty. Once the pot 
is empty, the savages wait for the pot to be replenished, which requires the cook to add one victim at a time into the pot.

As usual, we limit the simulation for some amount of time, here we choose a parameter `n` for the number of victims being consumed by the savages. For 
simplicity, here, we assume that `n` is a multiple of the pot capacity `m`.

The approach to solving the problem is to start with few molecules and model the problem more precisely starting with simplifying assumptions; in 
particular, it is easier to think of a single savage eating from the pot and generalize to `k` afterwards.

#### Hint: use a counter for filling the pot and one for consuming from it

In this presentation, we will log distinct events representative of the story into a concurrent queue of events sharing a printable (String) interface. This
 makes it easier to visualize a number of runs, debug, and provide some assertions at the end of the simulation. If the event is unique and parameter free, 
 we use a case object, otherwise a case class. We will show this logging aspect only at the end of the solution as it does not provide any particular 
 insight to concurrency modeling with Chymyst.
 
We can start with the parameters of the problem and a simulation error condition with a pot starting full and savages never eating, instead the amount of 
victims in the pot decays with time randomly, warming ourselves up to solving the specifics of the problem:

```scala
val maxPerPot = 7
// enemies of the tribe put together in a pot or capacity of pot in number of ingredients
val batches = 10
val supplyLineSize = maxPerPot * batches
val check = b[Unit, Unit]
val endSimulation = m[Unit]
val availableIngredientsInPot = m[Int]

sealed trait StoryEvent
val userStory = new ConcurrentLinkedQueue[StoryEvent]

def eatSingleServing(batchVictim: Int): Unit = {
  // userStory.add(VictimIsConsumedFromPot(batchVictim)) the parameter here is just an identifier
  // from the victims/ingredients in the pot starting with a high number
  Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)
  availableIngredientsInPot(batchVictim - 1) // one fewer serving.
}

site(tp)(
  go { case endSimulation(_) + check(_, r) => r() },
  go { case availableIngredientsInPot(n) =>
    // userStory.add(EndOfSimulation) (adding to concurrentQueue userStory
    if (n > 0) {
      eatSingleServing(n)
    } else endSimulation()
  }
)
availableIngredientsInPot(maxPerPot) // this molecule signifies pot is available for savages to eat.
check()

```

Now, we need to look at a molecule that will understand to run enough cycles of pot filling up and emptying itself. A `Cook` molecule carrying out an 
integer number from `supplyLineSize = maxPerPot * batches` should do. It will have to count down. The `check` molecule represent the end of simulation, 
which will occur once the cook has enough and the pot is empty, meaning the cook has gone through enough batches to have enough. When the cook's counter is
 at 0, he has had enough (second reaction below), however he will leave the pot full as he found it (the initial condition for `availableIngredientsInPot`)
 . In the third reaction, we start filling the pot with the molecule showing cook is busy adding to the pot for a particular batch 
 `busyCookingIngredientsInPot`.
 
```scala
go { case CookHadEnough(_) + availableIngredientsInPot(0) + check(_, r) => r()},
go { case Cook(0) =>
      CookHadEnough()
      availableIngredientsInPot(maxPerPot)
},
go { case Cook(n) + availableIngredientsInPot(0) if n > 0 => // cook gets activated once the pot reaches an empty state.
  pauseForIngredient()
  busyCookingIngredientsInPot(1) // switch of counting from availableIngredientsInPot to busyCooking indicates we're refilling the pot.
  Cook(n - 1)
}

```

We need however to have the cook fill the pot completely and so we need a new reaction to increment the ingredients to the cooking batch size, so a 
counting reaction for `busyCookingIngredientsInPot`. While counting up the ingredients in the batch, we must continue to count down the number of victims 
in the simulation. Once the cook finishes his batch and can no longer add any, the cook signifies to savages that they can eat by emitting 
`availableIngredientsInPot(maxPerPot)`. 

We can now write

```scala
go { case Cook(m) + busyCookingIngredientsInPot(n) if m > 0 =>
  if (n < maxPerPot) {
    pauseForIngredient()
    busyCookingIngredientsInPot(n + 1)
    Cook(m - 1)
  } else {
    availableIngredientsInPot(maxPerPot) // switch of counting from busyCooking to availableIngredientsInPot indicates we're consuming the pot.
    Cook(m)
  }
}

```
 
Running through this, we run into a problem with
 
```scala
go { case availableIngredientsInPot(n) =>
  if (n > 0) {
    eatSingleServing(n)
  } else endSimulation()
}

```

and need to introduce a `savage` molecule to consume the ingredients

```scala
go { case savage(_) + availableIngredientsInPot(n) =>
  if (n > 0) {
    eatSingleServing(n)
    savage()
  }
}

```

We are now ready to augment the solution with a single savage:

```scala
val maxPerPot = 7
// enemies of the tribe put together in a pot or capacity of pot in number of ingredients
val batches = 10
val supplyLineSize = maxPerPot * batches
val check = b[Unit, Unit]
val endSimulation = m[Unit]
val availableIngredientsInPot = m[Int]

val Cook = m[Int] // counts ingredients consumed, so after a while decides it's enough.
val CookHadEnough = m[Unit]
val busyCookingIngredientsInPot = m[Int] // Cook's counter to add ingredients to the pot
val savage = m[Unit]

sealed trait StoryEvent
val userStory = new ConcurrentLinkedQueue[StoryEvent]

def eatSingleServing(batchVictim: Int): Unit = {
  // userStory.add(VictimIsConsumedFromPot(batchVictim)) the parameter here is just an identifier
  // from the victims/ingredients in the pot starting with a high number
  Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)
  availableIngredientsInPot(batchVictim - 1) // one fewer serving.
}

def pauseForIngredient(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)

site(tp)(
  go { case Cook(0) =>
    CookHadEnough()
    availableIngredientsInPot(maxPerPot)
  },
  go { case CookHadEnough(_) + availableIngredientsInPot(0) + check(_, r) => r()},
  go { case savage(_) + availableIngredientsInPot(n) =>
    if (n > 0) {
      eatSingleServing(n)
      savage()
    }
  },
  go { case Cook(n) + availableIngredientsInPot(0) if n > 0 => // cook gets activated once the pot reaches an empty state.
    pauseForIngredient()
    busyCookingIngredientsInPot(1) // switch of counting from availableIngredientsInPot to busyCooking indicates we're refilling the pot.
    Cook(n - 1)
  },

  go { case Cook(m) + busyCookingIngredientsInPot(n) if m > 0 =>
    if (n < maxPerPot) {
      pauseForIngredient()
      busyCookingIngredientsInPot(n + 1)
      Cook(m - 1)
    } else {
      availableIngredientsInPot(maxPerPot) // switch of counting from busyCooking to availableIngredientsInPot indicates we're consuming the pot.
      Cook(m)
    }
  }

)
Cook(supplyLineSize)
availableIngredientsInPot(maxPerPot) // this molecule signifies pot is available for savages to eat.
savage()
check()

```

At this point, we can replace the reaction with `savage` with one reaction per savage participating in the meal, with molecules `Ivan`, `Patrick` and 
`Anita`. We can also introduce logging to capture events.

```scala
val maxPerPot = 7 // enemies of the tribe put together in a pot or capacity of pot in number of ingredients
val batches = 10
val supplyLineSize = maxPerPot * batches
val check = b[Unit, Unit]

sealed trait StoryEvent {
  def toString: String
}
case object CookRetires extends StoryEvent {
  override def toString: String = "cook is done, savages may eat last batch"
}
case object CookStartsToWork extends StoryEvent {
  override def toString: String = "cook finds empty pot and gets to work"
}
case object EndOfSimulation extends StoryEvent {
  override def toString: String =
    "ending simulation, no more ingredients available, savages will have to fish or eat berries or raid again"
}
final case class CookAddsVictim(victimsToBeCooked: Int, batchVictim: Int) extends StoryEvent {
  override def toString: String =
    s"""cook finds unfilled pot and gets cracking with $batchVictim-th enemy ingredient"
    | "for current batch with $victimsToBeCooked victims to be cooked"""
}
final case class CookCompletedBatch(victimsToBeCooked: Int) extends StoryEvent {
  override def toString: String =
    s"cook notices he finished adding all ingredients with $victimsToBeCooked victims to be cooked"
}
final case class SavageEating(name: String, batchVictim: Int) extends StoryEvent {
  override def toString: String = s"$name about to eat ingredient # $batchVictim"
}

val Cook = m[Int] // counts ingredients consumed, so after a while decides it's enough.
val CookHadEnough = m[Unit]
val busyCookingIngredientsInPot = m[Int]
val Ivan = m[Unit]   // a savage (consumer)
val Patrick = m[Unit] // a savage
val Anita = m[Unit] // a savage
val availableIngredientsInPot = m[Int]

val userStory = new ConcurrentLinkedQueue[StoryEvent]

def pauseForIngredient(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)
def eatSingleServing(savage: String, batchVictim: Int): Unit = {
  userStory.add(SavageEating(savage, batchVictim))
  Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)
  availableIngredientsInPot(batchVictim - 1) // one fewer serving.
}

site(tp)(
  go { case Cook(0) =>
    userStory.add(CookCompletedBatch(0))
    userStory.add(CookAddsVictim(0, 1))
    userStory.add(CookRetires)
    CookHadEnough()
    availableIngredientsInPot(maxPerPot)
  },
  go { case CookHadEnough(_) + availableIngredientsInPot(0) + check(_, r) =>
    userStory.add(EndOfSimulation)
    r()
  },
  go { case Cook(n) + availableIngredientsInPot(0) if n > 0 => // cook gets activated once the pot reaches an empty state.
    userStory.add(CookStartsToWork)
    pauseForIngredient()
    busyCookingIngredientsInPot(1) // switch of counting from availableIngredientsInPot to busyCooking indicates we're refilling the pot.
    Cook(n - 1)
  },

  go { case Cook(m) + busyCookingIngredientsInPot(n) if m > 0 =>
    userStory.add(CookAddsVictim(m, n))
    if (n < maxPerPot) {
      pauseForIngredient()
      busyCookingIngredientsInPot(n + 1)
      Cook(m - 1)
    } else {
      userStory.add(CookCompletedBatch(m))
      availableIngredientsInPot(maxPerPot) // switch of counting from busyCooking to availableIngredientsInPot indicates we're consuming the pot.
      Cook(m)
    }
  },
  go { case Ivan(_) + availableIngredientsInPot(n) if n > 0 => eatSingleServing("Ivan", n) + Ivan()  },
  go { case Patrick(_) + availableIngredientsInPot(n) if n > 0 => eatSingleServing( "Patrick", n) + Patrick()  },
  go { case Anita(_) + availableIngredientsInPot(n) if n > 0 => eatSingleServing( "Anita", n) + Anita()  }

)
Patrick() + Ivan() + Anita() + Cook(supplyLineSize) // if running as a daemon, we would not count down for the Cook.
availableIngredientsInPot(maxPerPot) // this molecule signifies pot is available for savages to eat.
check()

```

To generalize the chemistry to an arbitrary population of savages we introduce an indexed sequence of `savages` containing names or Strings. We then 
replace the three molecules for Ivan, Patrick, and Anita of type `m[Unit]` with a single molecule `savage` carrying a name value (`m[String]`).
 
Instead of emitting the three savage molecules at initialization, we emit all savages molecules as `savages.foreach(savage)`. Finally, we generalize the 
three specific savage reactions into the following one, selecting a random savage to emit (take turn) once the current savage is done eating:

```scala
go { case savage(name) + availableIngredientsInPot(n) if n > 0 =>
     eatSingleServing(name, n)
     val randomSavageName = savages(scala.util.Random.nextInt(savages.size))
     savage(randomSavageName) // emit random savage molecule
   }
   
```

Full code is available in examples.

