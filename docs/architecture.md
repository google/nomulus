# Architecture

This document contains information on the overall architecture of Nomulus on
[Google Cloud Platform](https://cloud.google.com/). It covers the App Engine
architecture as well as other Cloud Platform services used by Nomulus.

## App Engine

[Google App Engine](https://cloud.google.com/appengine/) is a cloud computing
platform that runs web applications in the form of servlets. Nomulus consists of
Java servlets that process web requests. These servlets use other features
provided by App Engine, including task queues and cron jobs, as explained
below.

### Services

Nomulus contains three [App Engine
services](https://cloud.google.com/appengine/docs/python/an-overview-of-app-engine),
which were previously called modules in earlier versions of App Engine. The
services are: default (also called front-end), backend, and tools. Each service
runs independently in a lot of ways, including that they can be upgraded
individually, their log outputs are separate, and their servers and configured
scaling are separate as well.

Once you have your app deployed and running, the default service can be accessed
at `https://project-id.appspot.com`, substituting whatever your App Engine app
is named for "project-id". Note that that is the URL for the production instance
of your app; other environments will have the environment name appended with a
hyphen in the hostname, e.g. `https://project-id-sandbox.appspot.com`.

The URL for the backend service is `https://backend-dot-project-id.appspot.com`
and the URL for the tools service is `https://tools-dot-project-id.appspot.com`.
The reason that the dot is escaped rather than forming subdomains is because the
SSL certificate for `appspot.com` is only valid for `*.appspot.com` (no double
wild-cards).

#### Default service

The default service is responsible for all registrar-facing
[EPP](https://en.wikipedia.org/wiki/Extensible_Provisioning_Protocol) command
traffic, all user-facing WHOIS and RDAP traffic, and the admin and registrar web
consoles, and is thus the most important service. If the service has any
problems and goes down or stops servicing requests in a timely manner, it will
begin to impact users immediately. Requests to the default service are handled
by the `FrontendServlet`, which provides all of the endpoints exposed in
`FrontendRequestComponent`.

#### Backend service

The backend service is responsible for executing all regularly scheduled
background tasks (using cron) as well as all asynchronous tasks. Requests to the
backend service are handled by the `BackendServlet`, which provides all of the
endpoints exposed in `BackendRequestComponent`. These include tasks for
generating/exporting RDE, syncing the trademark list from TMDB, exporting
backups, writing out DNS updates, handling asynchronous contact and host
deletions, writing out commit logs, exporting metrics to BigQuery, and many
more. Issues in the backend service will not immediately be apparent to end
users, but the longer it is down, the more obvious it will become that
user-visible tasks such as DNS and deletion are not being handled in a timely
manner.

The backend service is also where scheduled and automatically invoked MapReduces
run, which includes some of the aforementioned tasks such as RDE and
asynchronous resource deletion. Consequently, the backend service should be
sized to support not just the normal ongoing DNS load but also the load incurred
by MapReduces, both scheduled (such as RDE) and on-demand (asynchronous
contact/host deletion).

#### Tools service

The tools service is responsible for servicing requests from the `nomulus`
command line tool, which provides administrative-level functionality for
developers and tech support employees of the registry. It is thus the least
critical of the three services. Requests to the tools service are handled by the
`ToolsServlet`, which provides all of the endpoints exposed in
`ToolsRequestComponent`. Some example functionality that this service provides
includes the server-side code to update premium lists, run EPP commands from the
tool, and manually modify contacts/hosts/domains/and other resources. Problems
with the tools service are not visible to users.

The tools service also runs ad-hoc MapReduces, like those invoked via `nomulus`
tool subcommands like `generate_zone_files` and by manually hitting URLs under
https://tools-dot-project-id.appspot.com, like
`/_dr/task/refreshDnsForAllDomains`.

### Task queues

App Engine [task
queues](https://cloud.google.com/appengine/docs/java/taskqueue/) provide an
asynchronous way to enqueue tasks and then execute them on some kind of
schedule. There are two types of queues, push queues and pull queues. Tasks in
push queues are always executing up to some throttlable limit. Tasks in pull
queues remain there until the queue is polled by code that is running for some
other reason. Essentially, push queues run their own tasks while pull queues
just enqueue data that is used by something else. Many other parts of App Engine
are implemented using task queues. For example, [App Engine
cron](https://cloud.google.com/appengine/docs/java/config/cron) adds tasks to
push queues at regularly scheduled intervals, and the [MapReduce
framework](https://cloud.google.com/appengine/docs/java/dataprocessing/) adds
tasks for each phase of the MapReduce algorithm.

Nomulus uses a particular pattern of paired push/pull queues that is worth
explaining in detail. Push queues are essential because App Engine's
architecture does not support long-running background processes, and so push
queues are thus the fundamental building block that allows asynchronous and
background execution of code that is not in response to incoming web requests.
However, they also have limitations in that they do not allow batch processing
or grouping. That's where the pull queue comes in. Regularly scheduled tasks in
the push queue will, upon execution, poll the corresponding pull queue for a
specified number of tasks and execute them in a batch. This allows the code to
execute in the background while taking advantage of batch processing.

The task queues used by Nomulus are configured in the `queue.xml` file. Note
that many push queues have a direct one-to-one correspondence with entries in
`cron.xml` because they need to be fanned-out on a per-TLD or other basis (see
the Cron section below for more explanation). The exact queue that a given cron
task will use is passed as the query string parameter "queue" in the url
specification for the cron task.

Here are the task queues in use by the system. All are push queues unless
explicitly marked as otherwise.

*   `async-delete-pull` and `async-host-rename-pull` -- Pull queues for tasks to
    asynchronously delete contacts/hosts and to asynchronously refresh DNS for
    renamed hosts, respectively. Tasks are enqueued during EPP flows and then
    handled in batches by the regularly running cron tasks
    `DeleteContactsAndHostsAction` and `RefreshDnsOnHostRenameAction`.
*   `bigquery-streaming-metrics` -- Queue for metrics that are asynchronously
    streamed to BigQuery in the `Metrics` class. Tasks are enqueued during EPP
    flows in `EppController`. This means that there is a lag of a few seconds to
    a few minutes between when metrics are generated and when they are queryable
    in BigQuery, but this is preferable to slowing all EPP flows down and
    blocking them on BigQuery streaming.
*   `brda` -- Queue for tasks to upload weekly Bulk Registration Data Access
    (BRDA) files to a location where they are available to ICANN. The
    `RdeStagingReducer` (part of the RDE MapReduce) creates these tasks at the
    end of generating an RDE dump.
*   `dns-pull` -- A pull queue to enqueue DNS modifications. Cron regularly runs
    `ReadDnsQueueAction`, which drains the queue, batches modifications by TLD,
    and writes the batches to `dns-publish` to be published to the configured
    `DnsWriter` for the TLD.
*   `dns-publish` -- Queue for batches of DNS updates to be pushed to DNS
    writers.
*   `export-bigquery-poll` -- Queue for tasks to query the success/failure of a
    given BigQuery export job. Tasks are enqueued by `BigqueryPollJobAction`.
*   `export-commits` -- Queue for tasks to export commit log checkpoints. Tasks
    are enqueued by `CommitLogCheckpointAction` (which is run every minute by
    cron) and executed by `ExportCommitLogDiffAction`.
*   `export-snapshot` -- Cron and push queue for tasks to load a Datastore
    snapshot that was stored in Google Cloud Storage and export it to BigQuery.
    Tasks are enqueued by both cron and `CheckSnapshotAction` and are executed
    by both `ExportSnapshotAction` and `LoadSnapshotAction`.
*   `export-snapshot-poll` -- Queue for tasks to check that a Datastore snapshot
    has been successfully uploaded to Google Cloud Storage (this is an
    asynchronous background operation that can take an indeterminate amount of
    time). Once the snapshot is successfully uploaded, it is imported into
    BigQuery. Tasks are enqueued by `ExportSnapshotAction` and executed by
    `CheckSnapshotAction`.
*   `export-snapshot-update-view` -- Queue for tasks to update the BigQuery
    views to point to the most recently uploaded snapshot. Tasks are enqueued by
    `LoadSnapshotAction` and executed by `UpdateSnapshotViewAction`.
*   `group-members-sync` -- Cron queue for tasks to sync registrar contacts (not
    domain contacts!) to Google Groups. Tasks are executed by
    `SyncGroupMembersAction`.
*   `load[0-9]` -- Queues used to load-test the system by `LoadTestAction`.
    These queues don't need to exist except when actively running load tests
    (running load tests on production environments is not recommended). There
    are ten of these queues to provide simple sharding, because Nomulus is
    capable of handling significantly more Queries Per Second than the highest
    throttle limit available on task queues (which is 500 qps).
*   `lordn-claims` and `lordn-sunrise` -- Pull queues for handling LORDN
    exports. Tasks are enqueued synchronously during EPP commands depending on
    whether the domain name in question has a claims notice ID.
*   `marksdb` -- Queue for tasks to verify that an upload to NORDN was
    successfully received and verified. These tasks are enqueued by
    `NordnUploadAction` following an upload and are executed by
    `NordnVerifyAction`.
*   `nordn` -- Cron queue used for NORDN exporting. Tasks are executed by
    `NordnUploadAction`, which pulls LORDN data from the `lordn-claims` and
    `lordn-sunrise` pull queues (above).
*   `rde-report` -- Queue for tasks to upload RDE reports to ICANN following
    successful upload of full RDE files to the escrow provider. Tasks are
    enqueued by `RdeUploadAction` and executed by `RdeReportAction`.
*   `rde-upload` -- Cron queue for tasks to upload already-generated RDE files
    from Cloud Storage to the escrow provider. Tasks are executed by
    `RdeUploadAction`.
*   `retryable-cron-tasks` -- Catch-all cron queue for various cron tasks that
    run infrequently, such as exporting reserved terms.
*   `sheet` -- Queue for tasks to sync registrar updates to a Google Sheets
    spreadsheet. Tasks are enqueued by `RegistrarServlet` when changes are made
    to registrar fields and are executed by `SyncRegistrarsSheetAction`.

### Cron jobs

Nomulus uses App Engine [cron
jobs](https://cloud.google.com/appengine/docs/java/config/cron) to run periodic
scheduled actions. These actions run as frequently as once per minute (in the
case of syncing DNS updates) or as infrequently as once per month (in the case
of RDE exports). Cron tasks are specified in `cron.xml` files, with one per
environment. There are more tasks that run in Production than in other
environments because tasks like uploading RDE dumps are only done for the live
system. Cron tasks execute on the `backend` service.

Most cron tasks use the `TldFanoutAction` which is accessed via the
`/_dr/cron/fanout` URL path. This action, which is run by the BackendServlet on
the backend service, fans out a given cron task for each TLD that exists in the
registry system, using the queue that is specified in the `cron.xml` entry.
Because some tasks may be computationally intensive and could risk spiking
system latency if all start executing immediately at the same time, there is a
`jitterSeconds` parameter that spreads out tasks over the given number of
seconds. This is used with DNS updates and commit log deletion.

The reason the `TldFanoutAction` exists is that a lot of tasks need to be done
separately for each TLD, such as RDE exports and NORDN uploads. It's simpler to
have a single cron entry that will create tasks for all TLDs than to have to
specify a separate cron task for each action for each TLD (though that is still
an option). Task queues also provide retry semantics in the event of transient
failures that a raw cron task does not. This is why there are some tasks that do
not fan out across TLDs that still use `TldFanoutAction` -- it's so that the
tasks retry in the face of transient errors.

The full list of URL parameters to `TldFanoutAction` that can be specified in
cron.xml is:

*   `endpoint` -- The path of the action that should be executed (see
    `web.xml`).
*   `queue` -- The cron queue to enqueue tasks in.
*   `forEachRealTld` -- Specifies that the task should be run in each TLD of
    type `REAL`. This can be combined with `forEachTestTld`.
*   `forEachTestTld` -- Specifies that the task should be run in each TLD of
    type `TEST`. This can be combined with `forEachRealTld`.
*   `runInEmpty` -- Specifies that the task should be run globally, i.e. just
    once, rather than individually per TLD. This is provided to allow tasks to
    retry. It is called "`runInEmpty`" for historical reasons.
*   `excludes` -- A list of TLDs to exclude from processing.
*   `jitterSeconds` -- The execution of each per-TLD task is delayed by a
    different random number of seconds between zero and this max value.

## Environments

Nomulus comes pre-configured with support for a number of different
environments, all of which are used in Google's registry system. Other registry
operators may choose to use more or fewer environments, depending on their
needs. Each environment consists of a separate Google Cloud Platform project,
which includes a separate database and separate bulk storage in Cloud Storage.
Each environment is thus completely independent.

The different environments are specified in `RegistryEnvironment`. Most
correspond to a separate App Engine app except for `UNITTEST` and `LOCAL`, which
by their nature do not use real environments running in the cloud. The
recommended naming scheme for the App Engine apps that has the best possible
compatibility with the codebase and thus requires the least configuration is to
pick a name for the production app and then suffix it for the other
environments. E.g., if the production app is to be named 'registry-platform',
then the sandbox app would be named 'registry-platform-sandbox'.

The full list of environments supported out-of-the-box, in descending order from
real to not, is:

*   `PRODUCTION` -- The real production environment that is actually running
    live TLDs. Since Nomulus is a shared registry platform, there need only ever
    be one of these.
*   `SANDBOX` -- A playground environment for external users to test commands in
    without the possibility of affecting production data. This is the
    environment new registrars go through
    [OT&E](https://www.icann.org/resources/unthemed-pages/registry-agmt-appc-e-2001-04-26-en)
    in. Sandbox is also useful as a final sanity check to push a new prospective
    build to and allow it to "bake" before pushing it to production.
*   `QA` -- An internal environment used by business users to play with and sign
    off on new features to be released. This environment can be pushed to
    frequently and is where manual testers should be spending the majority of
    their time.
*   `CRASH` -- Another environment similar to QA, except with no expectations of
    data preservation. Crash is used for testing of backup/restore (which brings
    the entire system down until it is completed) without affecting the QA
    environment.
*   `ALPHA` -- The developers' playground. Experimental builds are routinely
    pushed here in order to test them on a real app running on App Engine. You
    may end up wanting multiple environments like Alpha if you regularly
    experience contention (i.e. developers being blocked from testing their code
    on Alpha because others are already using it).
*   `LOCAL` -- A fake environment that is used when running the app locally on a
    simulated App Engine instance.
*   `UNITTEST` -- A fake environment that is used in unit tests, where
    everything in the App Engine stack is simulated or mocked.

## Release process

The following is a recommended release process based on Google's several years
of experience running a production registry using this codebase.

1.  Developers write code and associated unit tests verifying that the new code
    works properly.
2.  New features or potentially risky bug fixes are pushed to Alpha and tested
    by the developers before being committed to the source code repository.
3.  New builds are cut and first pushed to Sandbox.
4.  Once a build has been running successfully in Sandbox for a day with no
    errors, it can be pushed to Production.
5.  Repeat once weekly, or potentially more often.

## Cloud Datastore

Nomulus uses [Cloud
Datastore](https://cloud.google.com/appengine/docs/java/datastore/) as its
primary database. Cloud Datastore is a NoSQL document database that provides
automatic horizontal scaling, high performance, and high availability. All
information that is persisted to Cloud Datastore takes the form of Java classes
annotated with `@Entity` that are located in the `model` package. The [Objectify
library](https://cloud.google.com/appengine/docs/java/gettingstarted/using-datastore-objectify)
is used to persist instances of these classes in a format that Datastore
understands.

A brief overview of the different entity types found in the App Engine Datastore
Viewer may help administrators understand what they are seeing. Note that some
of these entities are part of App Engine tools that are outside of the domain
registry codebase:

*   `_AE_*` -- These entities are created by App Engine.
*   `_ah_SESSION` -- These entities track App Engine client sessions.
*   `_GAE_MR_*` -- These entities are generated by App Engine while running
    MapReduces.
*   `BackupStatus` -- There should only be one of these entities, used to
    maintain the state of the backup process.
*   `Cancellation` -- A cancellation is a special type of billing event which
    represents the cancellation of another billing event such as a OneTime or
    Recurring.
*   `ClaimsList`, `ClaimsListShard`, and `ClaimsListSingleton` -- These entities
    store the TMCH claims list, for use in trademark processing.
*   `CommitLog*` -- These entities store the commit log information.
*   `ContactResource` -- These hold the ICANN contact information (but not
    registrar contacts, who have a separate entity type).
*   `Cursor` -- We use Cursor entities to maintain state about daily processes,
    remembering which dates have been processed. For instance, for the RDE
    export, Cursor entities maintain the date up to which each TLD has been
    exported.
*   `Domain` -- These hold the ICANN domain information.
*   `DomainRecord` -- These are used during the DNS update process.
*   `EntityGroupRoot` -- There is only one EntityGroupRoot entity, which serves
    as the Datastore parent of many other entities.
*   `EppResourceIndex` -- These entities allow enumeration of EPP resources
    (such as domains, hosts and contacts), which would otherwise be difficult to
    do in Datastore.
*   `ExceptionReportEntity` -- These entities are generated automatically by
    ECatcher, a Google-internal logging and debugging tool. Non-Google users
    should not encounter these entries.
*   `ForeignKeyContactIndex`, `ForeignKeyDomainIndex`, and
    `ForeignKeyHostIndex` -- These act as a unique index on contacts, domains
    and hosts, allowing transactional lookup by foreign key.
*   `HistoryEntry` -- A HistoryEntry is the record of a command which mutated an
    EPP resource. It serves as the parent of BillingEvents and PollMessages.
*   `HostRecord` -- These are used during the DNS update process.
*   `Host` -- These hold the ICANN host information.
*   `Lock` -- Lock entities are used to control access to a shared resource such
    as an App Engine queue. Under ordinary circumstances, these locks will be
    cleaned up automatically, and should not accumulate.
*   `MR-*` -- These entities are generated by the App Engine MapReduce library
    in the course of running MapReduces.
*   `Modification` -- A Modification is a special type of billing event which
    represents the modification of a OneTime billing event.
*   `OneTime` -- A OneTime is a billing event which represents a one-time charge
    or credit to the client (as opposed to Recurring).
*   `pipeline-*` -- These entities are also generated by the App Engine
    MapReduce library.
*   `PollMessage` -- PollMessages are generated by the system to notify
    registrars of asynchronous responses and status changes.
*   `PremiumList`, `PremiumListEntry`, and `PremiumListRevision` -- The standard
    method for determining which domain names receive premium pricing is to
    maintain a static list of premium names. Each PremiumList contains some
    number of PremiumListRevisions, each of which in turn contains a
    PremiumListEntry for each premium name.
*   `RdeRevision` -- These entities are used by the RDE subsystem in the process
    of generating files.
*   `Recurring` -- A Recurring is a billing event which represents a recurring
    charge to the client (as opposed to OneTime).
*   `Registrar` -- These hold information about client registrars.
*   `RegistrarContact` -- Registrars have contacts just as domains do. These are
    stored in a special RegistrarContact entity.
*   `Registry` -- These hold information about the TLDs supported by the
    Registry system.
*   `RegistryCursor` -- These entities are the predecessor to the Cursor
    entities. We are no longer using them, and will be deleting them soon.
*   `ReservedList` -- Each ReservedList entity represents an entire list of
    reserved names which cannot be registered. Each TLD can have one or more
    attached reserved lists.
*   `ServerSecret` -- this is a single entity containing the secret numbers used
    for generating tokens such as XSRF tokens.
*   `SignedMarkRevocationList` -- The entities together contain the Signed Mark
    Data Revocation List file downloaded from the TMCH MarksDB each day. Each
    entity contains up to 10,000 rows of the file, so depending on the size of
    the file, there will be some handful of entities.
*   `TmchCrl` -- This is a single entity containing ICANN's TMCH CA Certificate
    Revocation List.

## Cloud Storage buckets

Nomulus uses [Cloud Storage](https://cloud.google.com/storage/) for bulk storage
of large flat files that aren't suitable for Datastore. These files include
backups, RDE exports, Datastore snapshots (for ingestion into BigQuery), and
reports. Each bucket name must be unique across all of Google Cloud Storage, so
we use the common recommended pattern of prefixing all buckets with the name of
the App Engine app (which is itself globally unique). Most of the bucket names
are configurable, but the defaults are as follows, with PROJECT standing in as a
placeholder for the App Engine app name:

*   `PROJECT-billing` -- Monthly invoice files for each registrar.
*   `PROJECT-commits` -- Daily exports of commit logs that are needed for
    potentially performing a restore.
*   `PROJECT-domain-lists` -- Daily exports of all registered domain names per
    TLD.
*   `PROJECT-gcs-logs` -- This bucket is used at Google to store the GCS access
    logs and storage data. This bucket is not required by the Registry system,
    but can provide useful logging information. For instructions on setup, see
    the [Cloud Storage
    documentation](https://cloud.google.com/storage/docs/access-logs).
*   `PROJECT-icann-brda` -- This bucket contains the weekly ICANN BRDA files.
    There is no lifecycle expiration; we keep a history of all the files. This
    bucket must exist for the BRDA process to function.
*   `PROJECT-icann-zfa` -- This bucket contains the most recent ICANN ZFA files.
    No lifecycle is needed, because the files are overwritten each time.
*   `PROJECT-rde` -- This bucket contains RDE exports, which should then be
    regularly uploaded to the escrow provider. Lifecycle is set to 90 days. The
    bucket must exist.
*   `PROJECT-reporting` -- Contains monthly ICANN reporting files.
*   `PROJECT-snapshots` -- Contains daily exports of Datastore entities of types
    defined in `ExportConstants.java`. These are imported into BigQuery daily to
    allow for in-depth querying.
*   `PROJECT.appspot.com` -- Temporary MapReduce files are stored here. By
    default, the App Engine MapReduce library places its temporary files in a
    bucket named {project}.appspot.com. This bucket must exist. To keep
    temporary files from building up, a 90-day or 180-day lifecycle should be
    applied to the bucket, depending on how long you want to be able to go back
    and debug MapReduce problems.
