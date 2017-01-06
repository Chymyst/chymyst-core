<link href="{{ site.github.url }}/tables.css" rel="stylesheet">

## Cigarette smokers

The "Cigarette smokers" problem is to implement four concurrent processes that coordinate assembly line operations to manufacture cigarettes with the 
workers smoking the individual cigarettes they manufacture. One process represents a supplier of ingredients on the assembly line, which we may call the 
pusher. The other processes are three smokers, each having an infinite supply of only one of the three required ingredients, which are matches, paper, and 
tobacco. We may give names to the smokers/workers as Keith, Slash, and Jimi. 

The pusher provides at random time intervals one unit each of two required ingredients (for example matches and paper, or paper and tobacco). The pusher is not allowed to coordinate with the smokers to use knowledge of which smoker 
needs which ingredients, he just supplies two ingredients at a time. We assume that the pusher has an infinite supply of the three 
ingredients available to him. The real life example is that of a core operating systems service having to schedule and provide limited distinct resources to 
other services where coordination of scarce resources is required.
 
Each smoker selects two ingredients, rolls up a cigarette using the third ingredient that complements the list, lits it up and smokes it. It is necessary
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
    val check = new EE("check") 

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
 smoker must notify the `pusher` when to resume. The smoking break is a simple waste of time represented by a sleep. The smoker molecule must re-inject 
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
   Keith(()) + Slash(()) + Jimi(())

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
    val KeithInNeed = new E("Keith obtained tobacco and matches to get his fix") // makes for more vivid tracing, could be plainly m[Unit]
    val SlashInNeed = new E("Slash obtained tobacco and matches to get his fix") // same
    val JimiInNeed = new E("Jimi obtained tobacco and matches to get his fix") // same

    val tobaccoShipment = m[ShippedInventory] // this is not particularly elegant, ideally this should carry Unit but pusher needs to obtain current state
    val matchesShipment = m[ShippedInventory] // same
    val paperShipment = m[ShippedInventory] // same

    val check = new EE("check") // blocking Unit, only blocking molecule of the example.

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

    KeithInNeed(()) + SlashInNeed(()) + JimiInNeed(())
    pusher(ShippedInventory(0,0,0))
    count(supplyLineSize) // if running as a daemon, we would not use count and let the example/application run for ever.

    check()
```

There is a harder, more general treatment of the cigarette smokers problem, which has the `pusher` in charge of the rate of availability of ingredients, not 
having to wait for smokers, which the reader may think of using a producer-consumer queue with unlimited buffer in classic treatment of the problem. The 
solution is provided in code. Let us say that the change is very simple, we just need
 to have pusher emit the pusher molecule instead
 of the smokers doing so.  The pausing in the assembly line needs to be done within the `pusher` reaction, otherwise it is the same solution.
 
 ## Saddle Points
 
 A saddle point represents a point three dimensions that is a local maximum according to one dimension and a local minimum to the other. We think either of 
 the sitting point of a horse saddle or the highest point of a mountain pass. Mathematicians also think of calculus but that is not the topic here at all. 
 What we have is a matrix of data with an ordering relation, we will assume here integers, and are interested in finding an element of the matrix that is the
  minimum of a row and the maximum of a column. In the solution presented here, we implicitly assume that the numbers of the matrix are distinct, i.e. there 
  is a unique minimum in each row and a unique maximum in each column. We also work with a matrix of size 4x4, logic assumes the matrix is square. We are 
  more interested here in contrasting the difference of the solution between the sequential computation and the parallel computation using chemistry than the
   exact assumptions of the problem.
 
 A few observations are helpful:
   - there can be no saddle point, one saddle point, or several ones. Think of nature to picture this. Imagine a cone for the no saddle point case, where 
   values of the matrix represent elevation on a map. One saddle point can be a mountain pass in an area that consists of two peaks and multiple saddle 
   points could be found in a larger area.
   - computing the minimum (maximum) of `n` numbers requires `n-1` comparisons sequentially and we make an assumption that parallelism does not help 
   (actually a divide and conquer that is recursive should help using parallelism but we ignore that, this can only help a better parallel algorithm)
   - we can identify location of the minima of `n` rows as `n` jobs or tasks and similarly for the maxima of `n` columns.
   - we can match the minima with the maxima in sequential fashion as follows: if `i`-th row contains a min at `j` inquire whether max of `j`-th column is 
   also `i`. We can filter each row to see whether that condition holds and retains the saddle points as a solution.
   
   The unit test for Saddle Points uses a matrix with a single saddle point at `(2,1)` with value 37 using indices 0 to 3 for the 4 rows and columns. 
   ```scala
   val sample =
         Array(12, 3, 11, 21,
           14, 7, 57, 26,
           61, 37, 53, 59,
           55, 6, 12, 12)
   ```
   It requires a sequential computation to validate that the chemistry evaluation is the same. We will afford some intentionally suboptimal bookkeeping of 
   positions in matrix and value for simplicity of computation using `case class PointAndValue` below with `Point` representing a coordinate or entry of the 
   matrix. The key sequential method is `getSaddlePointsSequentially` below, which uses the last 2 items of our above observations (with `dim` being a range 
   of `[0, 3]`). Helper methods `seqMinR` and `seqMaxC` compute sequentially the min of a single row and the max of a single column respectively. We use these 
   helper methods for the parallel chemistry solution as well (assuming probably incorrectly that it's optimal in parallel). The return type effectively 
   assumes uniqueness of min and max within the sequence, so we assume here the elements are distinct.
   
   ```scala
   type Point = (Int, Int)
   case class PointAndValue(value: Int, point: Point) extends Ordered[PointAndValue] {
     def compare(that: PointAndValue): Int = this.value compare that.value
   }
   def seqMinR(i: Int, pointsWithValues: Array[PointAndValue]): PointAndValue =
     pointsWithValues.filter { case PointAndValue(v, (r, c)) => r == i }.min
   
   def seqMaxC(i: Int, pointsWithValues: Array[PointAndValue]): PointAndValue =
     pointsWithValues.filter { case PointAndValue(v, (r, c)) => c == i }.max

   def getSaddlePointsSequentially(pointsWithValues: Array[PointAndValue]): IndexedSeq[PointAndValue] = {
     val minOfRows = for { i <- dim } yield seqMinR(i, pointsWithValues)
     val maxOfCols = for { i <- dim } yield seqMaxC(i, pointsWithValues)

     // now intersect minOfRows with maxOfCols using the positions we keep track of.
     minOfRows.filter(minElem => maxOfCols(minElem.point._2).point._1 == minElem.point._1)
   }
   ```
   
Our task now is to find a chemistry solution to compute the above efficiently in parallel. First we need to launch `2*n` jobs to compute the mins and maxs 
for all rows and columns in parallel. We realize that this is essentially structurally equivalent to the `n` rendezvous problem and reuse that chemistry. 
What is truly distinct from the rendezvous problem is the final computation or reaction that identifies the saddle points. Here it is:
  ```scala
  go { case saddlePoints(sps) + minFoundAt(pv1) + maxFoundAt(pv2) if pv1 == pv2 => // the key matching happens here.
          saddlePoints(pv1::sps)
        }
   ```
   Assuming we have a molecule `minFountAt` with the context of coordinate and value as PointValue `pv1` and a corresponding molecule `maxFoundAt` for a 
   potentially distinct point `pv2` which we actually need to be the same, we just discovered a saddle point! So we use a guard to ensure we compute the mins
    and maxs **only** if they represent the same coordinate to force the chemistry to try out all required pair combinations and do the hard matching work 
    for us (like molecules in the soup that are compatible must match each other). We then need to collect the results in a reduce style (map-reduce) as per 
    MapReduceSpec.scala. For this we use a list of saddle points `sps`, which we can extend with our new saddle point `pv1` (`pv2` would do as well).
    
  Now we need to generate our `2*n` tasks and identify when we're all done executing them. With a change of variable `m=2*n`, we have the problem of 
  scheduling `m` tasks and identify when we are done and that is the `m` rendezvous problem once we remove the matching. The interpretation is to schedule 
  `n` single ladies going into a dance floor and start dancing all alone by themselves when they are all together, so we reuse the `n` rendezvous and 
  removing an element of the pairs (the men). We also distinguish the `m` ladies into two categories some executing a dance called `max` and some executing a
   dance (job/task) called `min`. We are now all set, except that introduced some timers to gather the final results, which is suboptimal:
     
```scala
    val n = 4 
    val nSquare = n*n
    val dim = 0 until n

    val sp = new SmartPool(n)

    val matrix = Array.ofDim[Int](n, n)

    type Point = (Int, Int)
    case class PointAndValue(value: Int, point: Point) extends Ordered[PointAndValue] {
      def compare(that: PointAndValue): Int = this.value compare that.value
    }

    def arrayToMatrix(a: Array[Int], m: Array[Array[Int]]): Unit =
      for (i <- dim) { dim.foreach( j => m(i)(j) = a(i * n + j)) }

    def seqMinR(i: Int, pointsWithValues: Array[PointAndValue]): PointAndValue =
       pointsWithValues.filter { case PointAndValue(v, (r, c)) => r == i }.min

    def seqMaxC(i: Int, pointsWithValues: Array[PointAndValue]): PointAndValue =
      pointsWithValues.filter { case PointAndValue(v, (r, c)) => c == i }.max

    def getSaddlePointsSequentially(pointsWithValues: Array[PointAndValue]): IndexedSeq[PointAndValue] = {
      val minOfRows = for { i <- dim } yield seqMinR(i, pointsWithValues)
      val maxOfCols = for { i <- dim } yield seqMaxC(i, pointsWithValues)

      // now intersect minOfRows with maxOfCols using the positions we keep track of.
      minOfRows.filter(minElem => maxOfCols(minElem.point._2).point._1 == minElem.point._1)
    }

    val sample =
      Array(12, 3, 11, 21,
        14, 7, 57, 26,
        61, 37, 53, 59,
        55, 6, 12, 12)
    arrayToMatrix(sample, matrix)
    val pointsWithValues = matrix.flatten.zipWithIndex.map{ case(x: Int, y: Int) => PointAndValue(x, (y/n, y % n) )}
    // print input matrix
    for (i <- dim) { println(dim.map(j => sample(i * n + j)).mkString(" "))}

    val barrier = b[Unit,Unit]
    val counterInit = m[Unit]
    val counter = b[Int,Unit]
    val interpret = m[()=>Unit]

    val minFoundAt = m[PointAndValue]
    val maxFoundAt = m[PointAndValue]
    val saddlePoints = m[List[PointAndValue]]

    val end = m[Unit]
    val done = b[Unit, List[PointAndValue]]

    sealed trait ComputeRequest // just for logging, not really necessary
    case class MinOfRow( row: Int) extends ComputeRequest
    case class MaxOfColumn(column: Int) extends ComputeRequest

    case class LogData (c: ComputeRequest, pv: PointAndValue)
    val logFile = new ConcurrentLinkedQueue[LogData]  // just for logging, not really necessary

    def minR(row: Int)(): Unit = {
      val pv = seqMinR(row, pointsWithValues)
      minFoundAt(pv)
      logFile.add(LogData(MinOfRow(row), pv))
      ()
    }
    def maxC(col: Int)(): Unit = {
      val pv = seqMaxC(col, pointsWithValues)
      maxFoundAt(pv)
      logFile.add(LogData(MaxOfColumn(col), pv))
      ()
    }
    val results = getSaddlePointsSequentially(pointsWithValues)
    // results.foreach(y => println(s"saddle point at $y"))

    site(sp)(
      go { case interpret(work) => work(); barrier(); end() },
      // this reaction will be run n times because we emit n molecules `interpret` with various computation tasks

      go { case barrier(_, r) + counterInit(_) => // this reaction will consume the very first barrier molecule emitted
        counter(1)
        r()
      },
      go { case saddlePoints(sps) + minFoundAt(pv1) + maxFoundAt(pv2) if pv1 == pv2 => // the key matching happens here.
        saddlePoints(pv1::sps)
      },
      go { case barrier(_, r1) + counter(k, r2) => // the `counter` molecule holds the number (`k`) of the reactions/computations triggered by interpret
        // that have executed so far
        if (k + 1 < 2*n) { // 2*n is amount of preliminary tasks of computation (emitted originally by interpret)
          counter(k+1)
          r2()
          r1()
        }
        else {
          Thread.sleep(500.toLong) // Can we avoid this sleep? Should we?
          // now we have enough to report immediately the results!
          end() + counterInit()
        }
      },
      go { case end(_) + done(_, r) + saddlePoints(sps)  => r(sps) }
    )

    dim.foreach(i => interpret(minR(i)) + interpret(maxC(i)))
    counterInit()
    saddlePoints(Nil)
    done.timeout(1000 millis)().toList.flatten.toSet shouldBe results.toSet

    val events: IndexedSeq[LogData] = logFile.iterator().asScala.toIndexedSeq
    println("\nLogFile START"); events.foreach { case(LogData(c, pv)) => println(s"$c  $pv") }; println("LogFile END") // comment out to see what's going on.

    sp.shutdownNow()

```     
