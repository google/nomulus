# Architecture

This document contains information on the overall architecture of Nomulus on
[Google Cloud Platform](https://cloud.google.com/).

Nomulus was originally built for App Engine, but the modern architecture now
uses Google Kubernetes Engine (GKE) for better flexibility and control over
networking, running as a series of Java-based microservices within GKE pods.

In addition, because GKE (and standard HTTP load balancers) typically handle
HTTP(s) traffic, Nomulus uses a custom proxy to handle raw TCP traffic required
for EPP (Port 700). This proxy can run as a GKE sidecar or a standalone cluster.
For more information on the proxy, see [the proxy setup guide](proxy-setup.md).

### Workloads

Nomulus contains four Kubernetes
[workloads](https://kubernetes.io/docs/concepts/workloads/). Each workload is
fairly independent as one would expect, including scaling.

The four workloads are referred to as `frontend`, `backend`, `console`, and
`pubapi`.

Each workload's URL is created by prefixing the name of the workload to the base
domain, e.g. `https://pubapi.mydomain.example`. Requests to each workload are
all handled by the
[RegistryServlet](https://github.com/google/nomulus/blob/master/core/src/main/java/google/registry/module/RegistryServlet.java)

#### Frontend workload

The frontend workload is responsible for all registrar-facing
[EPP](https://en.wikipedia.org/wiki/Extensible_Provisioning_Protocol) command
traffic. If the workload has any problems or goes down, it will begin to impact
users immediately.

#### PubApi workload

The PubApi (Public API) workload is responsible for all public traffic to the
registry. In practice, this primarily consists of RDAP traffic. This is split
into a separate workload so that public users (without authentication) will have
a harder time impacting intra-registry or registrar-registry actions.

#### Backend workload

The backend workload is responsible for executing all regularly scheduled
background tasks (using cron) as well as all asynchronous tasks. These include
tasks for generating/exporting RDE, syncing the trademark list from TMDB,
exporting backups, writing out DNS updates, syncing BSA data,
generating/exporting ICANN activity data, and many more. Issues in the backend
workload will not immediately be apparent to end users, but the longer it is
down, the more obvious it will become that user-visible tasks such as DNS and
deletion are not being handled in a timely manner.

The backend workload is also where scheduled and automatically-invoked BEAM
pipelines run, which includes some of the aforementioned tasks such as RDE.
Consequently, the backend workload should be sized to support not just the
normal ongoing DNS load but also the load incurred by BEAM pipelines, both
scheduled (such as RDE) and on-demand (started by registry employees).

The backend workload also supports handling of manually-performed actions using
the `nomulus` command-line tool, which provides administrative-level
functionality for developers and tech support employees of the registry.

### Cloud Tasks queues

GCP's [Cloud Tasks](https://docs.cloud.google.com/tasks/docs) provides an
asynchronous way to enqueue tasks and then execute them on some kind of
schedule. Task queues are essential because by nature, GKE architecture does not
support long-running background processes, and so queues are thus the
fundamental building block that allows asynchronous and background execution of
code that is not in response to incoming web requests.

The task queues used by Nomulus are configured in the `cloud-tasks-queue.xml`
file. Note that many push queues have a direct one-to-one correspondence with
entries in `cloud-scheduler-tasks-ENVIRONMENT.xml` because they need to be
fanned-out on a per-TLD or other basis (see the Cron section below for more
explanation). The exact queue that a given cron task will use is passed as the
query string parameter "queue" in the url specification for the cron task.

Here are the task queues in use by the system:

*   `brda` -- Queue for tasks to upload weekly Bulk Registration Data Access
    (BRDA) files to a location where they are available to ICANN. The RDE
    pipeline creates these tasks at the end of generating an RDE dump.
*   `dns-publish` -- Queue for batches of DNS updates to be pushed to DNS
    writers.
*   `dns-refresh` -- Queues for reading and fanning out DNS refresh requests,
    using the `DnsRefreshRequest` SQL table as the source of data
*   `marksdb` -- Queue for tasks to verify that an upload to NORDN was
    successfully received and verified. These tasks are enqueued by
    `NordnUploadAction` following an upload and are executed by
    `NordnVerifyAction`.
*   `nordn` -- Cron queue used for NORDN exporting. Tasks are executed by
    `NordnUploadAction`
*   `rde-report` -- Queue for tasks to upload RDE reports to ICANN following
    successful upload of full RDE files to the escrow provider. Tasks are
    enqueued by `RdeUploadAction` and executed by `RdeReportAction`.
*   `rde-upload` -- Cron queue for tasks to upload already-generated RDE files
    from Cloud Storage to the escrow provider. Tasks are executed by
    `RdeUploadAction`.
*   `retryable-cron-tasks` -- Catch-all cron queue for various cron tasks that
    run infrequently, such as exporting reserved terms.
*   `sheet` -- Queue for tasks to sync registrar updates to a Google Sheets
    spreadsheet, done by `SyncRegistrarsSheetAction`.

### Scheduled cron jobs

Nomulus uses [Cloud Scheduler](https://docs.cloud.google.com/scheduler/docs) to
run periodic scheduled actions. These actions run as frequently as once per
minute (in the case of syncing DNS updates) or as infrequently as once per month
(in the case of RDE exports). Cron tasks are specified in
`cloud-scheduler-tasks-{ENVIRONMENT}.xml` files, with one per environment. There
are more tasks that run in Production than in other environments because tasks
like uploading RDE dumps are only done for the live system.

Most cron tasks use the `TldFanoutAction` which is accessed via the
`/_dr/cron/fanout` URL path. This action fans out a given cron task for each TLD
that exists in the registry system, using the queue that is specified in the XML
entry. Because some tasks may be computationally intensive and could risk
spiking system latency if all start executing immediately at the same time,
there is a `jitterSeconds` parameter that spreads out tasks over the given
number of seconds. This is used with DNS updates and commit log deletion.

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

*   `endpoint` -- The path of the action that should be executed
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
recommended project naming scheme that has the best possible compatibility with
the codebase and thus requires the least configuration is to pick a name for the
production app and then suffix it for the other environments. E.g., if the
production app is to be named 'registry-platform', then the sandbox app would be
named 'registry-platform-sandbox'.

The full list of environments supported out-of-the-box, in descending order from
real to not-real, is:

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

## Cloud SQL

Nomulus uses [GCP Cloud SQL](https://cloud.google.com/sql) (Postgres) to store
information. For more information, see the
[DB project README file.](../db/README.md)

## Cloud Storage buckets

Nomulus uses [Cloud Storage](https://cloud.google.com/storage/) for bulk storage
of large flat files that aren't suitable for SQL. These files include backups,
RDE exports, and reports. Each bucket name must be unique across all of Google
Cloud Storage, so we use the common recommended pattern of prefixing all buckets
with the name of the project (which is itself globally unique). Most of the
bucket names are configurable, but the most important / relevant defaults are:

*   `PROJECT-billing` -- Monthly invoice files for each registrar.
*   `PROJECT-bsa` -- BSA data and output
*   `PROJECT-domain-lists` -- Daily exports of all registered domain names per
    TLD.
*   `PROJECT-gcs-logs` -- This bucket is used at Google to store the GCS access
    logs and storage data. This bucket is not required by the Registry system,
    but can provide useful logging information. For instructions on setup, see
    the
    [Cloud Storage documentation](https://cloud.google.com/storage/docs/access-logs).
*   `PROJECT-icann-brda` -- This bucket contains the weekly ICANN BRDA files.
    There is no lifecycle expiration; we keep a history of all the files. This
    bucket must exist for the BRDA process to function.
*   `PROJECT-icann-zfa` -- This bucket contains the most recent ICANN ZFA files.
    No lifecycle is needed, because the files are overwritten each time.
*   `PROJECT-rde` -- This bucket contains RDE exports, which should then be
    regularly uploaded to the escrow provider. Lifecycle is set to 90 days. The
    bucket must exist.
*   `PROJECT-reporting` -- Contains monthly ICANN reporting files.
