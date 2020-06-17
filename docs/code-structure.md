# Code structure

This document contains information on the overall structure of the code, and how
particularly important pieces of the system are implemented.

## Bazel build system

[Bazel](https://www.bazel.io/) is used to build and test the Nomulus codebase.

Bazel builds are described using [BUILD
files](https://www.bazel.io/versions/master/docs/build-ref.html). A directory
containing a BUILD file defines a package consisting of all files and
directories underneath it, except those directories which themselves also
contain BUILD files. A package contains targets. Most targets in the codebase
are of the type `java_library`, which generates `JAR` files, or `java_test`,
which runs tests.

The key to Bazel's ability to create reproducible builds is the requirement that
each build target must declare its direct dependencies. Each of those
dependencies is a target, which, in turn, must also declare its dependencies.
This recursive description of a target's dependencies forms an acyclic graph
that fully describes the targets which must be built in order to build any
target in the graph.

A wrinkle in this system is managing external dependencies. Bazel was designed
first and foremost to manage builds where all code lives in a single source
repository and is compiled from `HEAD`. In order to mesh with other build and
packaging schemes, such as libraries distributed as compiled `JAR`s, Bazel
supports [external target
declarations](https://www.bazel.io/versions/master/docs/external.html#transitive-dependencies).
The Nomulus codebase uses external targets pulled in from Maven Central, these
are declared in `java/google/registry/repositories.bzl`. The dependencies of
these external targets are not managed by Bazel; you must manually add all of
the dependencies or use the
[generate_workspace](https://docs.bazel.build/versions/master/generate-workspace.html)
tool to do it.

### Generating EAR/WAR archives for deployment

There are special build target types for generating `WAR` and `EAR` files for
deploying Nomulus to GAE. These targets, `zip_file` and `registry_ear_file` respectively, are used in `java/google/registry/BUILD`. To generate archives suitable for deployment on GAE:

```shell
$ bazel build java/google/registry:registry_ear
  ...
  bazel-genfiles/java/google/registry/registry.ear
INFO: Elapsed time: 0.216s, Critical Path: 0.00s
# This will also generate the per-module WAR files:
$ ls bazel-genfiles/java/google/registry/*.war
bazel-genfiles/java/google/registry/registry_backend.war
bazel-genfiles/java/google/registry/registry_default.war
bazel-genfiles/java/google/registry/registry_tools.war
```

## Cursors

Cursors are `DateTime` pointers used to ensure rolling transactional isolation
of various reporting and other maintenance operations. Utilizing a `Cursor`
within an operation ensures that instances in time are processed exactly once
for a given task, and that tasks can catch up from any failure states at any
time.

Cursors are rolled forward at the end of successful tasks, are not rolled
forward in the case of failure, and can be manually set backwards using the
`nomulus update_cursors` command to reprocess a past action.

The following cursor types are defined:

*   **`BRDA`** - BRDA (thin) escrow deposits
*   **`RDE_REPORT`** - XML RDE report uploads
*   **`RDE_STAGING`** - RDE (thick) escrow deposit staging
*   **`RDE_UPLOAD`** - RDE (thick) escrow deposit upload
*   **`RDE_UPLOAD_SFTP`** - Cursor that tracks the last time we talked to the
    escrow provider's SFTP server for a given TLD.
*   **`RECURRING_BILLING`** - Expansion of `Recurring` (renew) billing events
    into `OneTime` events.
*   **`SYNC_REGISTRAR_SHEET`** - Tracks the last time the registrar spreadsheet
    was successfully synced.

All `Cursor` entities in Datastore contain a `DateTime` that represents the next
timestamp at which an operation should resume processing and a `CursorType` that
identifies which operation the cursor is associated with. In many cases, there
are multiple cursors per operation; for instance, the cursors related to RDE
reporting, staging, and upload are per-TLD cursors. To accomplish this, each
`Cursor` also has a scope, a `Key<ImmutableObject>` to which the particular
cursor applies (this can be e.g. a `Registry` or any other `ImmutableObject` in
Datastore, depending on the operation). If the `Cursor` applies to the entire
registry environment, it is considered a global cursor and has a scope of
`EntityGroupRoot.getCrossTldKey()`.

Cursors are singleton entities by type and scope. The id for a `Cursor` is a
deterministic string that consists of the websafe string of the Key of the scope
object concatenated with the name of the name of the cursor type, separated by
an underscore.

## Mapreduces

Nomulus uses the [App Engine MapReduce
framework](https://github.com/GoogleCloudPlatform/appengine-mapreduce/wiki/1-MapReduce)
extensively, both for a variety of regularly scheduled background tasks and for
one-off maintenance tasks. The MapReduce framework comes with a web UI for
viewing the status of ongoing and completed tasks.

Most MapReduces in Nomulus work by mapping over all entities of a given set of
Datastore kind(s) (e.g. domains, contacts, etc.). All of the MapReduces in
Nomulus are run by the `MapreduceRunner` class, which provides a standard set of
ways to set the number of mapper and reducer shards. It is common to run
map-only MapReduces when reducers aren't needed; these are supported as well.

The main complication with MapReduces is that the mapper and reducer classes are
required to be serializable as a consequence of how work is sharded out,
pasued/resumed, and moved around. All fields on these classes must therefore be
either `Serializable` or `transient`. This also means that dependency injection
is of limited use -- the best you can do is to `@Inject` serializable fields on
the entire MapReduce `Action`, and then set them manually on the mapper/reducer
classes in their constructor.

## Guava

The Nomulus codebase makes extensive use of the
[Guava](https://github.com/google/guava) libraries. These libraries provide
idiomatic, well-tested, and performant add-ons to the JDK. There are several
libraries in particular that you should familiarize yourself with, as they are
used extensively throughout the codebase:

*   [Immutable
    Collections](https://github.com/google/guava/wiki/ImmutableCollectionsExplained):
    Immutable collections are a useful defensive programming technique. When an
    Immutable collection type is used as a parameter type, it immediately
    indicates that the given collection will not be modified in the method.
    Immutable collections are also more memory-efficient than their mutable
    counterparts, and are inherently thread-safe.

    Immutable collections are constructed one of three ways:

    *   Using a `Builder`: used when the collection will be built iteratively in
        a loop.
    *   With the `of` method: used when constructing the collection with a
        handful of elements. Most commonly used when creating collections
        representing constants, like lookup tables or allow lists.
    *   With the `copyOf` method: used when constructing the method from a
        reference to another collection. Used to defensively copy a mutable
        collection (like a return value from an external library) to an
        immutable collection.

*   [Optional](https://github.com/google/guava/wiki/UsingAndAvoidingNullExplained#optional):
    The `Optional<T>` class is used as a container for nullable values. It is
    most often used as return value, as an explicit indicator that the return
    value may be absent, thereby making a `null` return value an obvious error.

*   [Preconditions](https://github.com/google/guava/wiki/PreconditionsExplained):
    Preconditions are used defensively, in order to validate parameters and
    state upon entry to a method.

In addition to Guava, the codebase also extensively uses
[AutoValue](https://github.com/google/auto) value classes. `AutoValue` value
type objects are immutable and have sane default implementations of `toString`,
`hashCode`, and `equals`. They are often used as parameters and return values to
encapsulate related values together.

## EPP resources

`EppResource` is the base class for objects allocated within a registry via EPP.
The classes that extend `EppResource` (along with the RFCs that define them) are
as follows:

*   `DomainBase` ([RFC 5731](https://tools.ietf.org/html/rfc5731)), further
    broken down into:
    *   `DomainApplication`, an application for a domain submitted during (e.g.)
        sunrise or landrush
    *   `DomainResource`, a domain name allocated following a successful
        application, or registered during a general availability phase
*   `HostResource` ([RFC 5732](https://tools.ietf.org/html/rfc5732))
*   `ContactResource` ([RFC 5733](https://tools.ietf.org/html/rfc5733))

All `EppResource` entities use a Repository Object Identifier (ROID) as its
unique id, in the format specified by [RFC
5730](https://tools.ietf.org/html/rfc5730#section-2.8) and defined in
`EppResourceUtils.createRoid()`.

Each entity also tracks a number of timestamps related to its lifecycle (in
particular, creation time, past or future deletion time, and last update time).
The way in which an EPP resource's active/deleted status is determined is by
comparing clock time against a resource's creation and deletion time, rather
than relying on an automated job (or similar) to flip an active bit on a
resource when it is deleted.

There are a number of other useful utility methods for interacting with EPP
resources in the `EppResourceUtils` class, many of which deal with inspecting
the status of a resource at a given point in time.

## Foreign key indexes

Foreign key indexes provide a means of loading active instances of `EppResource`
objects by their unique IDs:

*   `DomainResource`: fully-qualified domain name
*   `ContactResource`: contact id
*   `HostResource`: fully-qualified host name

Since all `EppResource` entities are indexed on ROID (which is also unique, but
not as useful as the resource's name), a `ForeignKeyIndex` provides a way to
look up the resources using another key which is also unique during the lifetime
of the resource (though not for all time).

A `ForeignKeyIndex` is updated as a resource is created or deleted. It is
important to note that throughout the lifecycle of an `EppResource`, the
underlying Datastore entity is never hard-deleted; its deletion time is set to
the time at which the EPP command to delete the resource was set, and it remains
in Datastore. Other resources with that same name can then be created.

## EPP resource index

An `EppResourceIndex` is an index that allows for quick enumeration of all
`EppResource` entities in Datastore. Datastore does not otherwise provide an
easy way to efficiently and strongly consistently enumerate all entities of a
given type. Each `EppResourceIndex` is assigned randomly to an
`EppResourceIndexBucket` upon creation, the number of which is configured to be
greater than the number of shards typically used for Mapreduces that enumerate
these entities. Mapreduces that process all `EppResource` entities (or
subclasses thereof) distribute each `EppResourceIndexBucket` to available
shards.

## History entries

A `HistoryEntry` is a record of a mutation of an EPP resource. There are various
events that are recorded as history entries, including:

*   Creates
*   Deletes
*   Delete failures
*   Pending deletes
*   Updates
*   Domain allocation
*   Domain renews
*   Domain restores
*   Application status updates
*   Domain and contact transfer status changes
    *   Approval
    *   Cancellation
    *   Rejection
    *   Requests

The full list is captured in the `HistoryEntry.Type` enum.

Each `HistoryEntry` has a parent `Key<EppResource>`, the EPP resource that was
mutated by the event. A `HistoryEntry` will also contain the complete EPP XML
command that initiated the mutation, stored as a byte array to be agnostic of
encoding.

A `HistoryEntry` also captures other event metadata, such as the `DateTime` of
the change, whether the change was created by a superuser, and the ID of the
registrar that sent the command.

## Poll messages

Poll messages are the mechanism by which EPP handles asynchronous communication
between the registry and registrars. Refer to [RFC 5730 Section
2.9.2.3](https://tools.ietf.org/html/rfc5730#section-2.9.2.3) for their protocol
specification.

Poll messages are stored by the system as entities in Datastore. All poll
messages have an event time at which they become active; any poll request before
that time will not return the poll message. For example, every domain when
created enqueues a speculative poll message for the automatic renewal of the
domain a year later. This poll message won't be delivered until that year
elapses, and if some change to the domain occurs prior to that point, such as it
being deleted, then the speculative poll message will be deleted and thus never
delivered. Other poll messages are effective immediately, e.g. the poll message
generated for the owning registrar when another registrar requests the transfer
of a domain. These messages are written out with an event time of when they were
created, and will thus be delivered whenever the registrar next polls for
messages.

`PollMessage` is the abstract base class for the two different types of poll
messages that extend it:

*   **`Autorenew`** - A poll message corresponding to an automatic renewal of a
    domain. It recurs annually.
*   **`OneTime`** - A one-time poll message used for everything else.

Queries for poll messages by the registrar are handled in `PollRequestFlow`, and
poll messages are ACKed (and thus deleted) in `PollAckFlow`.

## Billing events

Billing events capture all events in a domain's lifecycle for which a registrar
will be charged. A `BillingEvent` will be created for the following reasons (the
full list of which is represented by `BillingEvent.Reason`):

*   Domain creates
*   Domain renewals
*   Domain restores
*   Server status changes
*   Domain transfers

A `BillingEvent` can also contain one or more `BillingEvent.Flag` flags that
provide additional metadata about the billing event (e.g. the application phase
during which the domain was applied for).

All `BillingEvent` entities contain a parent `Key<HistoryEntry>` to identify the
mutation that spawned the `BillingEvent`.

There are 4 types of billing events, all of which extend the abstract
`BillingEvent` base class:

*   **`OneTime`**, a one-time billing event.
*   **`Recurring`**, a recurring billing event (used for events such as domain
    renewals).
*   **`Cancellation`**, which represents the cancellation of either a `OneTime`
    or `Recurring` billing event. This is implemented as a distinct event to
    preserve the immutability of billing events.
*   **`Modification`**, a change to an existing `OneTime` billing event (for
    instance, to represent a discount or refund).
