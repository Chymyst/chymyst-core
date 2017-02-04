<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Game of Life

Let us implement the famous [Game of Life](http://ddi.cs.uni-potsdam.de/HyFISCH/Produzieren/lis_projekt/proj_gamelife/ConwayScientificAmerican.htm) as a concurrent computation in the chemical machine.

Our goal is to make use of concurrency as much as possible.
An elementary computation in the Game of Life is to determine the next state of a cell, given its present state and the present states of its 8 neighbor cells.

```scala
def getNewState(
  state0: Int,
  state1: Int,
  state2: Int,
  state3: Int,
  state4: Int,
  state5: Int,
  state6: Int,
  state7: Int,
  state8: Int
): Int =
  (state1 + state2 + state3 + state4 + state5 + state6 + state7 + state8) match {
    case 2 => state0
    case 3 => 1
    case _ => 0
  }

```

Here, all "states" are integers `0` or `1`, and `state0` represents the center of a 3 x 3 square.

We would like this computation to proceed concurrently for each cell on the board, as much as possible.
So let us make this computation into a reaction.
For this to work, the previous states of each cell should be values carried by some input molecules of the reaction.
Therefore, we need 9 input molecules in each reaction, each carrying the state of one neighbor cell.

## First solution: single reaction

To prototype the reactions, let us imagine that we are computing the next state of the board at coordinates `(x, y)` at time `t`.
Suppose that molecule `c0()` carries the state of the board at the cell `(x, y)`, while the additional molecules `c1()`, ..., `c8()` carry the "neighbor data", that is, the states of the 8 neighbor cells at coordinates `(x - 1, y - 1)`, `(x - 1, y)`, `(x, y + 1)` and so on.

Let us assign (arbitrarily) the neighbor values like this:

| | | |
| --- | --- | --- |
| `c1((x, y))` | `c2((x, y))` | `c3((x, y))` |
| `c4((x, y))` | `c0((x, y))` | `c5((x, y))` |
| `c6((x, y))` | `c7((x, y))` | `c8((x, y))` |

For now, let us also put the time coordinate `t` as a value onto the molecules.
Given these molecules, we can start writing a reaction like this:

```scala
go { case
      c0((x, y, t, state0)) +
      c1((x, y, t, state1)) +
      c2((x, y, t, state2)) +
      c3((x, y, t, state3)) +
      c4((x, y, t, state4)) +
      c5((x, y, t, state5)) +
      c6((x, y, t, state6)) +
      c7((x, y, t, state7)) +
      c8((x, y, t, state8)) => ??? }

```

Here, `c1((x, y, t, s))` represents the state of the "first" neighbor at the `(x, y)`.
The values `x` and `y` always represent the coordinates of the center cell.

Now, we will immediately recognize that the reaction cannot work as written:
Scala does not allow the `case` match to use repeated variables.

Our intention was to start the reaction only when all 9 input molecules have the same values of `x`, `y`, `t`.
To do this, we must use a guard condition on the reaction:

```scala
go { case
  c0((x0, y0, t0, state0)) +
  c1((x1, y1, t1, state1)) +
  c2((x2, y2, t2, state2)) +
  c3((x3, y3, t3, state3)) +
  c4((x4, y4, t4, state4)) +
  c5((x5, y5, t5, state5)) +
  c6((x6, y6, t6, state6)) +
  c7((x7, y7, t7, state7)) +
  c8((x8, y8, t8, state8))
   if x0 == x1 && x0 == x2 && x0 == x3 && x0 == x4 && x0 == x5 && x0 == x6 && x0 == x7 && x0 == x8 &&
      y0 == y1 && y0 == y2 && y0 == y3 && y0 == y4 && y0 == y5 && y0 == y6 && y0 == y7 && y0 == y8 &&
      t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 => ??? 
}

```

What should be the output molecules of that reaction?
We need to compute the new state at `(x, y)` and put it onto the output molecule `c0((x, y, t + 1))`:

```scala
go { case
  c0((x0, y0, t0, state0)) +
  c1((x1, y1, t1, state1)) +
  c2((x2, y2, t2, state2)) +
  c3((x3, y3, t3, state3)) +
  c4((x4, y4, t4, state4)) +
  c5((x5, y5, t5, state5)) +
  c6((x6, y6, t6, state6)) +
  c7((x7, y7, t7, state7)) +
  c8((x8, y8, t8, state8))
   if x0 == x1 && x0 == x2 && x0 == x3 && x0 == x4 && x0 == x5 && x0 == x6 && x0 == x7 && x0 == x8 &&
      y0 == y1 && y0 == y2 && y0 == y3 && y0 == y4 && y0 == y5 && y0 == y6 && y0 == y7 && y0 == y8 &&
      t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 =>
  val newState = getNewState(state0, state1, state2, state3, state4, state5, state6, state7, state8)
  c0((x, y, t + 1, newState))
  ???
}

```

But how would the chemistry work at the next time step?
The molecule `c0((x, y, t + 1, _))` will need to react with its 8 neighbors.
However, each of the neighbor cells, such as `c0((x-1, y, t + 1, _))`, also needs to react with _its_ 8 neighbors.

Therefore, we need to have 9 output molecules in this reaction: one molecule, `c0()`, will react with its neighbors and produce the new state, while 8 others will provide `newState` as "neighbor data" for each of the 8 neighbors.
We need to emit these 8 other molecules with shifted coordinates, so that they will react with their proper neighbors at time `t + 1`.

The reaction now looks like this:

```scala
go { case
  c0((x0, y0, t0, state0)) +
  c1((x1, y1, t1, state1)) +
  c2((x2, y2, t2, state2)) +
  c3((x3, y3, t3, state3)) +
  c4((x4, y4, t4, state4)) +
  c5((x5, y5, t5, state5)) +
  c6((x6, y6, t6, state6)) +
  c7((x7, y7, t7, state7)) +
  c8((x8, y8, t8, state8))
   if x0 == x1 && x0 == x2 && x0 == x3 && x0 == x4 && x0 == x5 && x0 == x6 && x0 == x7 && x0 == x8 &&
      y0 == y1 && y0 == y2 && y0 == y3 && y0 == y4 && y0 == y5 && y0 == y6 && y0 == y7 && y0 == y8 &&
      t0 == t1 && t0 == t2 && t0 == t3 && t0 == t4 && t0 == t5 && t0 == t6 && t0 == t7 && t0 == t8 =>
  val newState = getNewState(state0, state1, state2, state3, state4, state5, state6, state7, state8)
  c1((x - 1, y - 1, t + 1, newState))
  c2((x + 0, y - 1, t + 1, newState))
  c3((x + 1, y - 1, t + 1, newState))
  c4((x - 1, y + 0, t + 1, newState))

  c0((x + 0, y + 0, t + 1, newState)) // center

  c5((x + 1, y + 0, t + 1, newState))
  c6((x - 1, y + 1, t + 1, newState))
  c7((x + 0, y + 1, t + 1, newState))
  c8((x + 1, y + 1, t + 1, newState))
}

```

These reactions will work! That's actually the entire chemistry that correctly simulates the Game of Life.

To start the simulation, we need to emit initial molecules.
For each cell `(x, y)` on the initial board, we need to emit 9 molecules `c0((x, y, t = 0, _))`, ..., `c8((x, y, t = 0, _))`: `c0()` bearing the initial state and `c1()`, ..., `c8()` providing neighbor data.

Another detail we glossed over is that the finite size of the board.
The simplest solution is to make the board wrap around in both `x` and `y` directions.

The code for emitting the initial molecules with wraparound can be like this:

```scala
val initBoard: Array[Array[Int]] = ???

(0 until sizeY).foreach { y0 =>
 (0 until sizeX).foreach { x0 =>
   val initState = initBoard(y0)(x0)
   c0(((x0 + 0 + sizeX) % sizeX, (y0 + 0 + sizeY)) % sizeY), 0, initState))
   c1(((x0 - 1 + sizeX) % sizeX, (y0 - 1 + sizeY)) % sizeY), 0, initState))
   c2(((x0 + 0 + sizeX) % sizeX, (y0 - 1 + sizeY)) % sizeY), 0, initState))
   c3(((x0 + 1 + sizeX) % sizeX, (y0 - 1 + sizeY)) % sizeY), 0, initState))
   c4(((x0 - 1 + sizeX) % sizeX, (y0 + 0 + sizeY)) % sizeY), 0, initState))
   c5(((x0 + 1 + sizeX) % sizeX, (y0 + 0 + sizeY)) % sizeY), 0, initState))
   c6(((x0 - 1 + sizeX) % sizeX, (y0 + 1 + sizeY)) % sizeY), 0, initState))
   c7(((x0 + 0 + sizeX) % sizeX, (y0 + 1 + sizeY)) % sizeY), 0, initState))
   c8(((x0 + 1 + sizeX) % sizeX, (y0 + 1 + sizeY)) % sizeY), 0, initState))
 }
}

```

The complete working code for this implementation is the second test case in `GameOfLifeSpec.scala`.

## Improving performance

While the program as written so far works correctly, it works _extremely slowly_.
The computation on a tiny 2 by 2 board takes about 2 seconds per timestep on a fast 8-core machine.
The CPU utilization stays around 100%; in other words, only one CPU core is loaded at 100% while other cores remain idle.
Not only the code runs unacceptably slowly, -- it also fails to use any concurrency!

The main reason for the bad performance is the complicated guard condition in the reaction.
This guard condition is an example of a **cross-molecule guard**, meaning that it constrains the values of a set of molecules as a whole (rather than one molecule value at a time).
In our code, the cross-molecule guard constrains the values of all 9 input molecules together.

Because of the presence of the cross-molecule guard, many combinations of molecule values must be examined before a reaction can be started.
Let us make a rough estimate.
For an `n * n` board, we initially inject `n * n` copies of molecules for each of the nine sorts `c0`, ..., `c8`.
The reaction site needs to find one copy of `c0()`, one copy of `c1()`, etc., such that the guard returns`true` for values carried by these copies.
In the worst case, the reaction site will examine `(n * n) ^ 9` possible combinations of molecule values before scheduling a single reaction.
There are `n * n` reactions to be scheduled at each time step.
This brings the worst-case complexity to `(n * n) ^ 10` per time step.
So, even for a smallest board with `n=2`, we get `(2 * 2) ^ 10 = 1048576`.
A million operations is a very large scheduling overhead for a computation that only runs 4 reactions with actual computations per time step.

The chemical machine can only go so far in optimizing guard conditions that can contain arbitrary user code.
Reactions without guard conditions are scheduled much faster.

How can we rewrite the chemistry so that reactions do not need guard conditions?

We use the cross-molecule guard condition only to select molecules that should react together.
In the current code, these molecules are selected by their coordinates in space and time.
Instead of using a cross-molecule guard to select input molecules, we can define a _separate reaction_ for each group of molecules that react together.

To achieve this, instead of using a single molecule sort `c0` with parameters `(x, y, t, state)`, we will use a new molecule sort for each set of `(x, y, t)`.
In other words, we will define _chemically different_ molecules representing cells at different `(x, y, t)`.
The easiest implementation is by creating a multidimensional matrix of molecule emitters.
Now that we are at it, the 9 sorts `c0`, ..., `c9` can be accommodated by an additional dimension in the same matrix; this will save us some boilerplate typing.

```scala
val emitterMatrix: Array[Array[Array[Array[M[Int]]]]] =
 Array.tabulate(sizeX, sizeY, sizeT, 9)((x, y, t, label) => new M[Int](s"c$label[$x,$y,$t]"))

```

The array `emitterMatrix` now stores all the molecule emitters we will need.
The strings such as `"c8[2,3,0]"`, representing the names of all the new molecules, are assigned explicitly using the `new M()` constructor since the macro `m` would assign the same name to all molecules, which might complicate debugging.
Molecule emitters are of type `M[Int]` because the only value that the new molecules need to carry is the integer `state`.

All molecules in `emitterMatrix` are chemically different since they were created using independent calls to `new M()`.
It remains to define reactions for these new molecules.

We want to define a separate reaction for each `x`, `y`, `t`, and store all these reactions in an array. 
Here is code that defines the 3-dimensional array of reactions:

```scala
val reactionMatrix: Array[Array[Array[Reaction]]] =
 Array.tabulate(boardSize.x, boardSize.y, finalTimeStep) { (x, y, t) =>
   // Molecule emitters for the inputs.
   // We need to assign them to separate `val`'s because `case emitterMatrix(x)(y)(t)(0)(state) => ...` will not compile.
   val c0 = emitterMatrix(x)(y)(t)(0)
   val c1 = emitterMatrix(x)(y)(t)(1)
   val c2 = emitterMatrix(x)(y)(t)(2)
   val c3 = emitterMatrix(x)(y)(t)(3)
   val c4 = emitterMatrix(x)(y)(t)(4)
   val c5 = emitterMatrix(x)(y)(t)(5)
   val c6 = emitterMatrix(x)(y)(t)(6)
   val c7 = emitterMatrix(x)(y)(t)(7)
   val c8 = emitterMatrix(x)(y)(t)(8)
  
   go { case
       c0(state0) +
       c1(state1) +
       c2(state2) +
       c3(state3) +
       c4(state4) +
       c5(state5) +
       c6(state6) +
       c7(state7) +
       c8(state8) =>
     val newState = getNewState(state0, state1, state2, state3, state4, state5, state6, state7, state8)
  
     // Emit output molecules.
     emitterMatrix(x + 0)(y + 0)(t + 1)(0)(newState)
     emitterMatrix(x + 1)(y + 0)(t + 1)(1)(newState)
     emitterMatrix(x - 1)(y + 0)(t + 1)(2)(newState)
     emitterMatrix(x + 0)(y + 1)(t + 1)(3)(newState)
     emitterMatrix(x + 1)(y + 1)(t + 1)(4)(newState)
     emitterMatrix(x - 1)(y + 1)(t + 1)(5)(newState)
     emitterMatrix(x + 0)(y - 1)(t + 1)(6)(newState)
     emitterMatrix(x + 1)(y - 1)(t + 1)(7)(newState)
     emitterMatrix(x - 1)(y - 1)(t + 1)(8)(newState)
   }
}

```

Now that all reactions are created, we need to define a reaction site that will run them.
The `site()` call accepts a sequence of reactions.
Since `reactionMatrix` is a 3-dimensional array, we need to flatten it twice:

```scala
site(reactionMatrix.flatten.flatten)

```

This implementation is found as test 4 in `GameOfLifeSpec.scala`.
This runs 10 time steps on a 10x10 game board in about the same time as the previous implementation ran 1 time step on a 2x2 board. 

## Radically improving performance

We have greatly increased the speed of the simulation, but there is still room for improvement.
The CPU usage of the new solution is still around 100% much of the time, which indicates that parallelism is not optimal.
The reason for low parallelism is the low granularity of the reaction sites: we have a single reaction site that runs all reactions.
A single reaction site can usually schedule only one reaction at a time, and our reactions are very simple, so we still fail to take advantage of multicore parallelism. 

To make progress, we notice that the program defines a separate reaction for each cell, and each reaction has its own chemically unique input molecules.
In this case, we can define _each reaction_ at a separate reaction site, instead of defining all reactions within one reaction site.

The only code change we need to make is the definition of the reaction sites, which will now look like this:

```scala
reactionMatrix.foreach(_.foreach(_.foreach(r => site(r)))))

```

This implementation is found as test 6 in `GameOfLifeSpec.scala`.
It runs about 200 times faster than the previous implementation (test 4): it computes 100 time steps on a 10x10 board in about 0.8 seconds.

Since each reaction is running at a separate reaction site, every cell update can be scheduled concurrently with any other cell update.
With this implementation, cell updates can be concurrent across the entire 3-dimensional array of cells (that is, both in space and time).

## Conclusion

We have seen that there are two ways of optimizing the performance of a chemical computation:

1. Redesign the chemistry so that reactions do not need cross-molecule guard conditions.
2. Redesign the chemistry so that many independent reaction sites are used, with fewer reactions per reaction site.

To see the effect of these design choices, and to provide a benchmark for the chemical machine, I made six different implementations of the Game of Life that differ only in the design of reactions.

The implementation in test 1 uses a single reaction with a single molecule sort.
The reaction has 9 repeated input molecules and emits 9 copies of the same molecule.
All coordination is performed by the guard condition that selects input molecules for reactions.

This implementation is catastrophically slow.
Running on anything larger than a 2x2 board takes forever and may crash due to garbage collecting overhead.

Test 2 is the solution first discussed in this chapter.
It introduces 9 different molecule sorts `c0`, ..., `c8` instead of using one molecule sort.
Otherwise, the chemistry remains the same as in test 1.
The change speeds up the simulation by a few times, although it remains unacceptably slow.

Tests 1 and 2 are intentionally very slow, to be used as benchmarks of the chemical machine.
The speedup between 1 and 2 suggests that avoiding repeated input molecules is a source of additional speedup.
This may or may not remain the case in future versions of `Chymyst Core`. 

Test 3 uses a different molecule sort for each cell on the board.
However, molecules corresponding to different time steps are the same.
The reaction still needs a cross-molecule guard condition to match up input molecules at the same time step.
Using different molecules for different cells speeds up the simulation enormously.

Tests 4 - 6 use a different molecule sort for each cell on the board and for each time step.
This is the solution discussed in the later sections of this chapter.
The reactions need no guard conditions, which results in a significant speedup.

The difference between tests 4 - 6 is in the granularity of reaction sites.
Test 4 has all reactions in one reaction site; test 5 declares a new reaction site for each time step; test 6 declares a new reaction site for each cell and for each time step.
The speedup between tests 4 and 6 is about 20x.

The complete working code showing the six different implementations is found in `GameOfLifeSpec.scala`.
