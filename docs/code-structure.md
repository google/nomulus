# Code structure

This document contains information on the overall structure of the code, and how
particularly important pieces of the system are implemented.

## Dagger dependency injection

## Bazel build system

## Flows

## Commit logs and backups

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

All `Cursor` entities in Datastore contain a `DateTime` that represents the next
timestamp at which an operation should resume processing and a `CursorType` that
identifies which operation the cursor is associated with. In many cases, there
are multiple cursors per operation; for instance, the cursors related to RDE
reporting, staging, and upload are per-TLD cursors. To accomplish this, each
`Cursor` also has a scope, a `Key<ImmutableObject>` to which the particular
cursor applies (this can be e.g. a `Registry` or any other `ImmutableObject` in
datastore, depending on the operation). If the `Cursor` applies to the entire
registry environment, it is considered a global cursor and has a scope of
`EntityGroupRoot.getCrossTldKey()`.

Cursors are singleton entities by type and scope. The id for a `Cursor` is a
deterministic string that consists of the websafe string of the Key of the scope
object concatenated with the name of the name of the cursor type, separated by
an underscore.

## Mapreduces

## Actions and servlets

## Foreign key indexes

## Point-in-time accuracy

## Guava

## EPP resource hierarchy

## Poll messages

Poll messages are the mechanism by which EPP handles asynchronous communication
between the registry and registrars. Refer to
[RFC 5730 Section 2.9.2.3](https://tools.ietf.org/html/rfc5730#section-2.9.2.3)
for their protocol specification.

Poll messages are stored by the system as entities in Datastore. All poll
messages have an event time at which they become active; any poll request before
that time will not return the poll message. For example, every domain when created
enqueues a speculative poll message for the automatic renewal of the domain a year
later. This poll message won't be delivered until that year
elapses, and if some change to the domain occurs prior to that point, such as it
being deleted, then the speculative poll message will be deleted and thus never
delivered. Other poll messages are effective immediately, e.g. the poll message
generated for the owning registrar when another registrar requests the transfer
of a domain. These messages are written out with an event time of when they were
created, and will thus be delivered whenever the registrar next polls for
messages.

`PollMessage` is the abstract base class for the two different types of poll
messages that extend it:

*   **`Autorenew`** - A poll message corresponding to an automatic renewal of
    a domain. It recurs annually.
*   **`OneTime`** - A one-time poll message used for everything else.

Queries for poll messages by the registrar are handled in `PollRequestFlow`, and
poll messages are ACKed (and thus deleted) in `PollAckFlow`.

## Security
