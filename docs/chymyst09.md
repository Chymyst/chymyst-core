<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Generators and coroutines

# Timers and timeouts

## Timers in the chemical machine

The basic function of a **timer** is to schedule some computation at a fixed time in the future.
In the chemical machine, any computation is a reaction, and reactions are started in only one way - by emitting molecules.

However, the chemical machine does not provide a means of delaying a reaction even though all input molecules are present.
Doing so would be inconsistent with the semantics of chemical reactions because it is impossible to know or to predict when the required molecules are going to be emitted.
So, timers should be used for emitting a molecule at a fixed time in the future.

How can we implement this in the chemical machine?
What code needs to be written if a reaction needs to emit its output molecules after a 10 second delay?

This functionality can be of course implemented using the chemical machine itself, for example:

TODO

A more efficient approach is to allocate statically a Java timer that will provide delayed emissions for a given molecule (or for a set of molecules).
However, this timer now needs to be accessible to any reaction (at any reaction site!) that might need to schedule a delayed emission of those molecules.
A straightforward solution is to allocate a timer automatically within each reaction site, and to make that timer accessible through the molecule emitters.

TODO

## Timeouts

In the context of the chemical machine, a timeout means that some action is taken when a given molecule is not consumed (or a given reaction does not start) within a specified time.

This functionality can be implemented with `Chymyst` like this:

TODO
