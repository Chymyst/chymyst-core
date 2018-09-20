# Distributed programming in the chemical machine

All programs considered in the previous chapters ran within a single OS process on a single (possibly multi-core) computer.
We now consider the extension of the chemical machine paradigm to distributed programming, where programs run concurrently and in parallel on several OS processes and/or on several computers.
The resulting paradigm is the distributed chemical machine (DCM).
Similarly to how the single-process chemical machine (CM) provides a declarative approach to concurrent programming, the DCM provides a declarative approach to distributed concurrent programming.

In what way is a CM program declarative?
The programmer merely specifies that certain computations need to be run concurrently and in parallel.
Computations are started automatically as long as the required input data is available.
The input data needs to be specially marked as "destined for" concurrent computations (i.e. emitted on molecules).
However, the code does not explicitly start new tasks at certain times, nor does it need explicitly to wait for or to synchronize processes that run in parallel.

Similarly, the DCM requires the programmer merely to say which molecule(s) must be distributed in a given CM program.
The runtime of the DCM will automatically distribute the chosen data across all processes and/or computers that participate in a given cluster.

