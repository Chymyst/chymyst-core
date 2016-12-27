<link href="{{ site.github.url }}/tables.css" rel="stylesheet">

# Version history

- 0.1.4 Simplify API: now users need only one package import. Many more tutorial examples of chemical machine concurrency. Test code coverage is 97%. More compiler warnings enabled (including deprecation warnings). There are now more intelligent "whitebox" macros that generate different subclasses of `M[T]` and `B[T,R]` when `T` or `R` are the `Unit` type, to avoid deprecation warnings with the syntax `f()`.

- 0.1.3 Major changes in the API ("site", "go" instead of "join", "run") and in the terminology used in the tutorial and in the code: we now use the chemical machine paradigm more consistently, and avoid using the vague term "join". The build system now uses the "wartremover" SBT plugin to check for more possible errors. Test code coverage is at 96%.

- 0.1.2 Bug fixes for singletons and for blocking molecules. Documentation revised with help of Philippe Derome. Started to track test code coverage (currently at 95%). New PR builds will not pass if code coverage decreases.

- 0.1.1 Bug fixes for blocking replies; new benchmarks for blocking molecules.

- 0.1.0 First alpha release of `JoinRun`. Changes: implementing singleton molecules and volatile readers; several important bugfixes.

- 0.0.10 Static checks for livelock and deadlock in reactions, with both compile-time errors and run-time errors.

- 0.0.9 Macros for static analysis of reactions; unrestricted pattern-matching now available for molecule values.

- 0.0.8 Add a timeout option for blocking molecules. Tutorial text and ScalaDocs are almost finished. Minor cleanups and simplifications in the API.

- 0.0.7 Refactor into proper library structure. Add tutorial text and start adding documentation. Minor cleanups. Add `Future`/molecule interface.

- 0.0.6 Initial release on Github. Basic functionality, unit tests.

# Roadmap for the future

These features are considered for implementation in the next versions:

Version 0.1: (Released.) Perform static analysis of reactions, and warn the user about certain situations with unavoidable livelock, deadlock, or nondeterminism.

Version 0.2: Rework the decisions to start reactions.
In particular, do not lock the entire molecule bag - only lock some groups of molecules that have contention on certain molecule inputs (decide this using static analysis information).
This will allow us to implement interesting features such as:

- fairness with respect to molecules (random choice of input molecules for reactions)
- start many reactions at once when possible
- emit many molecules at once, rather than one by one
- allow nonlinear input patterns

Version 0.3: Investigate interoperability with streaming frameworks such as Scala Streams, Scalaz Streams, FS2, Akka streams.

Version 0.4: Enterprise readiness: fault tolerance, monitoring, flexible logging, assertions on singleton molecules and perhaps on some other situations, thread fusing for singleton molecule reactions.

Version 0.5: Investigate an implicit distributed execution of chemical reactions ("soup pools").

# Current To-Do List

 value * difficulty - description

 2 * 3 - investigate using wait/notify instead of semaphore; does it give better performance? This depends on benchmarking of blocking molecules.

 2 * 3 - support a fixed number of singleton copies; remove Molecule.isSingleton mutable field in favor of a function on getJoinDef 

 2 * 3 - detect livelock due to singleton emission (at the moment, they are not considered as present inputs)

 3 * 3 - define a special "switch off" or "quiescence" molecule - per-join, with a callback parameter.
 Also define a "shut down" molecule which will enforce quiescence and then shut down the join pool and the reaction pool.

 5 * 5 - implement fairness with respect to molecules
 * - go through possible values when matching (can do?) Important: reactions can get stuck when molecules are in different order. Or need to shuffle.

 3 * 5 - create and use an RDLL (random doubly linked list) data structure for storing molecule values; benchmark. Or use Vector with tail-swapping?

 2 * 2 - perhaps use separate molecule bags for molecules with unit value and with non-unit value? for Booleans? for blocking and non-blocking? for constants? for singletons?

 4 * 5 - implement multiple emission construction a+b+c so that a+b-> and b+c-> reactions are equally likely to start. Implement starting many reactions concurrently at once, rather than one by one.
 
 4 * 5 - allow several reactions to be scheduled *truly simultaneously* out of the same reaction site, when this is possible. Avoid locking the entire bag? - perhaps partition it and lock only some partitions, based on reaction site information gleaned using a macro.

 4 * 5 - do not schedule reactions if queues are full. At the moment, RejectedExecutionException is thrown. It's best to avoid this. Molecules should be accumulated in the bag, to be inspected at a later time (e.g. when some tasks are finished). Insert a call at the end of each reaction, to re-inspect the bag.

 3 * 3 - add logging of reactions currently in progress at a given RS. (Need a custom thread class, or a registry of reactions?)

 2 * 2 - refactor ActorPool into a separate project with its own artifact and dependency. Similarly for interop with Akka Stream, Scalaz Task etc.

 2 * 2 - maybe remove default pools altogether? It seems that every pool needs to be stopped.

 3 * 4 - implement "thread fusion" like in iOS/Android: 1) when a blocking molecule is emitted from a thread T and the corresponding reaction site runs on the same thread T, do not schedule a task but simply run the reaction site synchronously (non-blocking molecules still require a scheduled task? not sure); 2) when a reaction is scheduled from a reaction site that runs on thread T and the reaction is configured to run on the same thread, do not schedule a task but simply run the reaction synchronously.

 5 * 5 - is it possible to implement distributed execution by sharing the join pool with another machine (but running the reaction sites only on the master node)?

 3 * 4 - LAZY values on molecules? By default? What about pattern-matching then? Probably need to refactor SyncMol and AsyncMol into non-case classes and change some other logic.

 3 * 5 - Can we implement JoinRun using Future / Promise and remove all blocking and all semaphores?

 2 * 2 - Detect this condition at the reaction site time:
 A cycle of input molecules being subset of output molecules, possibly spanning several reaction sites (a->b+..., b->c+..., c-> a+...). This is a warning if there are nontrivial matchers and an error otherwise.

 2 * 3 - understand the "reader-writer" example; implement it as a unit test

 3 * 2 - add per-molecule logging; log to file or to logger function

 5 * 5 - implement "progress and safety" assertions so that we could prevent deadlock in more cases
 and be able to better reason about our declarative reactions. First, need to understand what is to be asserted.
 Can we assert non-contention on certain molecules? Can we assert deterministic choice of some reactions? Should we assert the number of certain molecules present (precisely N`, or at most N)?

 2 * 4 - allow molecule values to be parameterized types or even higher-kinded types? Need to test this.

 2 * 2 - make memory profiling / benchmarking; how many molecules can we have per 1 GB of RAM?

 3 * 4 - implement nonlinear input patterns

 2 * 2 - annotate join pools with names. Make a macro for auto-naming join pools of various kinds.

 2 * 2 - add tests for Pool such that we submit a closure that sleeps and then submit another closure. Should get / or not get the RejectedExecutionException

 3 * 5 - implement "singleton" molecules with automatic detection of possible singletons; implement automatic thread fusion for singletons
