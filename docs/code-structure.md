# Code structure

This document contains information on the overall structure of the code, and how
particularly important pieces of the system are implemented.

## Gradle build system

[Gradle](https://gradle.org/) is used to build and test the Nomulus codebase.

Nomulus, for the most part, uses fairly standard Gradle task naming for building
and running tests, with the various tasks defined in various `build.gradle`
files.

Dependencies and their version restrictions are defined in the
`dependencies.gradle` file. Within each subproject's `build.gradle` file, the
actual dependencies used by that subproject are listed along with the type of
dependency (e.g. implementation, testImplementation). Versions of each
dependency are locked to avoid frequent dependency churn, with the locked
versions stored in the various `gradle.lockfile` files. To update these
versions, run any Gradle command (e.g. `./gradlew build`) with the
`--write-locks` argument.

### Generating WAR archives for deployment

The `jetty` project is the main entry point for building the Nomulus WAR files,
and one can use the `war` gradle task to build the base WAR file. The various
deployment/release files use Docker to deploy this, in a system that is too
Google-specialized to replicate directly here.

## Subprojects

Within the Nomulus repository there are a few notable subprojects:

*   `util` contains tools that don't depend on any of our other code, e.g.
    libraries or raw utilities
*   `db` contains database-related code, managing the schema and
    deployment/testing of the database.
*   `integration` contains tests to make sure that schema rollouts won't break
    Nomulus, that code versions and schema versions are cross-compatible
*   `console-webapp` contains the Typescript/HTML/CSS/Angular code for the
    registrar console frontend
*   `proxy` contains code for the EPP proxy, which relays port 700 requests to
    the core EPP services
*   `core` contains the bulk of the core Nomulus code, including request
    handling+serving, backend, actions, etc

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
*   **`RECURRING_BILLING`** - Expansion of `BillingRecurrence` (renew) billing
    events into one-time `BillingEvent`s.
*   **`SYNC_REGISTRAR_SHEET`** - Tracks the last time the registrar spreadsheet
    was successfully synced.

All `Cursor` entities in the database contain a `DateTime` that represents the
next timestamp at which an operation should resume processing and a `CursorType`
that identifies which operation the cursor is associated with. In many cases,
there are multiple cursors per operation; for instance, the cursors related to
RDE reporting, staging, and upload are per-TLD cursors. To accomplish this, each
`Cursor` also has a scope, a string to which the particular cursor applies (this
can be anything, but in practice is either a TLD or `GLOBAL` for cross-TLD
cursors. Cursors are singleton entities by type and scope.

## Guava

The Nomulus codebase makes extensive use of the
[Guava](https://github.com/google/guava) libraries. These libraries provide
idiomatic, well-tested, and performant add-ons to the JDK. There are several
libraries in particular that you should familiarize yourself with, as they are
used extensively throughout the codebase:

*   [Immutable Collections](https://github.com/google/guava/wiki/ImmutableCollectionsExplained):
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

*   `Domain` ([RFC 5731](https://tools.ietf.org/html/rfc5731))
*   `Host` ([RFC 5732](https://tools.ietf.org/html/rfc5732))

All `EppResource` entities use a Repository Object Identifier (ROID) as its
unique id, in the format specified by
[RFC 5730](https://tools.ietf.org/html/rfc5730#section-2.8) and defined in
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

`Domain` and `Host` each are foreign-keyed, meaning we often wish to query them
by their foreign keys (fully-qualified domain name and fully-qualified host
name, respectively).

Since all `EppResource` entities are indexed on ROID (which is also unique, but
not as useful as the resource's name), the `ForeignKeyUtils` provides a way to
look up the resources using another key which is also unique during the lifetime
of the resource (though not for all time).

It is important to note that throughout the lifecycle of an `EppResource`, the
underlying entity is never hard-deleted; its deletion time is set to the time at
which the EPP command to delete the resource was set, and it remains in the
database. Other resources with that same name can then be created.

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

Each `HistoryEntry` has a reference to a singular EPP resource that was mutated
by the event. A `HistoryEntry` will also contain the complete EPP XML command
that initiated the mutation, stored as a byte array to be agnostic of encoding.

A `HistoryEntry` also captures other event metadata, such as the `DateTime` of
the change, whether the change was created by a superuser, and the ID of the
registrar that sent the command.

## Poll messages

Poll messages are the mechanism by which EPP handles asynchronous communication
between the registry and registrars. Refer to
[RFC 5730 Section 2.9.2.3](https://tools.ietf.org/html/rfc5730#section-2.9.2.3)
for their protocol specification.

Poll messages are stored by the system as entities in the database. All poll
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
will be charged. A one-time `BillingEvent` will (or can) be created for the
following reasons (the full list of which is represented by
`BillingBase.Reason`):

*   Domain creates
*   Domain renewals
*   Domain restores
*   Server status changes
*   Domain transfers

There are 4 types of billing events, all of which extend the abstract
`BillingBase` base class:

*   **`BillingEvent`**, a one-time billing event.
*   **`BillingRecurrence`**, a recurring billing event (used for events such as
    domain renewals).
*   **`BillingCancellation`**, which represents the cancellation of either a
    `BillingEvent` or `BillingRecurrence` billing event. This is implemented as
    a distinct event to preserve the immutability of billing events.

A `BillingBase` can also contain one or more `BillingBase.Flag` flags that
provide additional metadata about the billing event (e.g. the application phase
during which the domain was applied for).

All `BillingBase` entities contain reference to a given ROID (`EppResource`
reference) to identify the mutation that spawned the `BillingBase`.
