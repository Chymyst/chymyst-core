<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# From actors to reactions: The chemical machine explained through the Actor model

This brief chapter explains the chemical machine paradigm for readers already familiar with the Actor model.

An actor receives messages and reacts to them by running a computation.
An actor-based program declares several actors, defines the computations for them, stores references to the actors, and starts sending messages to some of the actors.
Messages are sent asynchronously, enabling communication between different concurrent actors. 

The chemical machine paradigm is in certain ways similar to the Actor model.
A chemical program also consists of concurrent processes, or "chemical actors", that communicate by sending messages.
To arrive at the chemical machine paradigm, we need to modify the Actor model as follows:

- Messages are statically typed, and each chemical actor receives messages of a fixed type.
- A chemical actor can be defined to wait for _several_ input messages (of specified different types) at once, rather than just waiting for one message at a time.
Accordingly, messages are not sent to a linearly ordered queue or a mailbox — instead, messages are kept in an unordered bag and consumed in an unknown order.
- Chemical actors are not persistent and are not created explicitly by the user's program.
Instead, the runtime engine will automatically instantiate and run one or more copies of a chemical actor whenever enough input messages are available for these actors to consume.
These actors will automatically disappear when their computations are finished.

The last two features - the joint consumption of messages and the implicit creation/annihilation of chemical actors - make for a radical departure from the Actor model:

- Whenever there are sufficiently many input messages available for processing without contention, the runtime engine will automatically instantiate several concurrent copies of the same actor.
and process all these messages concurrently.
This is the main mechanism for achieving parallelism in the chemical paradigm.
The users do not need to concern themselves with the details of how many concurrent actors to instantiate at any given time.
Since chemical actors are not persistent but are instantiated automatically on demand, users do not need to implement actor lifecycle management or supervision mechanisms. 
- Whenever two or more chemical actors consume the same type of message, "input message contention" is created.
If only one copy of this message has been emitted, the runtime engine will automatically instantiate and run _one_ of the appropriate actors to process the message, while the other actors will not be instantiated.
Input contention is used in the chemical machine paradigm as a mechanism for synchronization and mutual exclusion.
Since the runtime engine will arbitrarily decide which actor to run, input contention will result in nondeterminism.
This is quite similar to the nondeterminism in the usual models of concurrent programming:
Mutual exclusion allows the programmer to implement safe exclusive access to a resource for any number of concurrent processes,
but the order of access among the concurrent processes remains unspecified.  

In the rest of this book, "chemical actors" are called **reactions**, their input messages are called **input molecules**,
and messages sent by an actor's process are called **output molecules** of the reaction.
