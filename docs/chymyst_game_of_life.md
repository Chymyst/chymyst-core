# Game of Life

Let us implement the famous [Game of Life](http://ddi.cs.uni-potsdam.de/HyFISCH/Produzieren/lis_projekt/proj_gamelife/ConwayScientificAmerican.htm) as a concurrent computation in the chemical machine.

Our goal is to make use of concurrency as much as possible.
An elementary computation in the Game of Life is to determine the next state of a cell, given its present state and the present states of its 8 neighbor cells.

```scala
def getNewState(state0: Int, state1: Int, state2: Int, state3: Int, state4: Int, state5: Int, state6: Int, state7: Int, state8: Int): Int =
  (state1 + state2 + state3 + state4 + state5 + state6 + state7 + state8) match {
    case 2 => state0
    case 3 => 1
    case _ => 0
  }

```

Here, all "states" are integers `0` or `1`, and `state0` represents the center of a 3 x 3 square.

We would like this computation to proceed concurrently for each cell on the board, as much as possible.
So let us make this computation into a reaction.
For this, the previous states of each cell should be values carried by the input molecules of the reaction.
Therefore, we need 9 input molecules in each reaction, each carrying the state of one neighbor.

To prototype the reactions, let us imagine that we are computing the next state of the board at coordinates `(x, y)`.
Suppose that molecule `c0()` carries the state of the board at the cell `(x, y)`, while the additional molecules `c1()`, ..., `c8()` carry the "neighbor data", that is, the states of the 8 neighbor cells -- at coordinates `(x + 1, y)`, `(x-1, y)`, (x, y + 1)` and so on.
Given these molecules, we can start writing a reaction like this:

```scala
go { case
      c0(state0) +
      c1(state1) +
      c2(state2) +
      c3(state3) +
      c4(state4) +
      c5(state5) +
      c6(state6) +
      c7(state7) +
      c8(state8) => ??? }

```

What should be the output molecules of that reaction?
We need to compute the new state and put it onto _some_ output molecule, say `d0()`:

```scala
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
  d0(newState)
  ???
}

```

But how would this chemistry work at the next time step?
The molecule `d0()` will need to react with its 8 neighbors.
However, each of the neighbor cells also needs to react with _its_ 8 neighbors.
Therefore, we need to have 9 output molecules of each cell: one molecule, `d0()`, will react with its neighbors and produce the new state, while 8 others will provide the neighbor data for each of the 8 neighbors.

TODO: expand

The complete working code is found in `GameOfLifeSpec.scala`.
