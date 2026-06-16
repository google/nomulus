# Operational procedures

This document covers procedures that are typically used when running a
production registry system.

## Stackdriver monitoring

[Stackdriver Monitoring](https://cloud.google.com/monitoring/docs/) is used to
instrument internal state within the Nomulus internal environment. This is
broadly called white-box monitoring. EPP, DNS, and RDAP are instrumented. The
metrics monitored are as follows:

*   `/dns/publish_domain_requests` -- A count of publish domain requests,
    described by the target TLD and the publish status.
*   `/dns/publish_host_requests` -- A count of publish host requests,
    described by the target TLD and the publish status.
*   `/epp/requests` -- A count of EPP requests, described by command
    name, client (registrar) id, and return status code.
*   `/epp/request_time` -- A [Distribution][distribution] representing
    the processing time for EPP requests, described by command name, traffic type,
    and return status code.
*   `/rdap/requests` -- A count of RDAP requests, described by endpoint
    type, deleted inclusion, registrar specification, authorization, and
    HTTP method.
*   `/rdap/request_time` -- A [Distribution][distribution]
    representing the processing time for RDAP requests, described by endpoint
    type, search type, wildcard type, HTTP status code, and
    incompleteness warning type.
*   `/lock/acquire_lock_requests` -- A count of lock acquisition attempts,
    described by TLD, resource name, and the existing lock state.
*   `/lock/lock_duration` -- A [Distribution][distribution] representing
    the lock lifetime in milliseconds, described by TLD and resource name.
*   `/cache/lookups` -- A count of cache lookups, described by cache name
    (e.g. domain, host) and the hit type (LOCAL, REMOTE, MISS,
    MISS_NONEXISTENT).
*   `/domain_label/reserved/checks` -- A count of reserved list checks,
    described by TLD, number of matching lists, most severe list name, and
    most severe reservation type.
*   `/domain_label/reserved/processing_time` -- A [Distribution][distribution]
    representing the amount of time in milliseconds required to check a label
    against all reserved lists.
*   `/domain_label/reserved/hits` -- A count of reserved list hits,
    described by TLD, reserved list name, and the reservation type found.

Follow the guide to
[set up a Stackdriver account](https://cloud.google.com/monitoring/accounts/guide)
and associate it with the GCP project containing the Nomulus app. Once the two
have been linked, monitoring will start automatically. For now, because the
visualization of custom metrics in Stackdriver is embryronic, you can retrieve
and visualize the collected metrics with a script, as described in the guide on
[Reading Time Series](https://cloud.google.com/monitoring/custom-metrics/reading-metrics)
and the
[custom metric code sample](https://github.com/GoogleCloudPlatform/python-docs-samples/blob/master/monitoring/api/v3/custom_metric.py).

In addition to the included white-box monitoring, black-box monitoring should be
set up to exercise the functionality of the registry platform as a user would
see it. This monitoring should, for example, create a new domain name every few
minutes via EPP and then verify that the domain exists in DNS and RDAP. For now,
no black-box monitoring implementation is provided with the Nomulus platform.

## Updating cursors

In most cases, cursors will not advance if a task that utilizes a cursor fails
(so that the task can be retried for that given timestamp). However, there are
some cases where a cursor is updated at the end of a job that produces bad
output (for example, RDE export), and in order to re-run a job, the cursor will
need to be rolled back.

In rare cases it might be useful to roll a cursor forward if there is some bad
data at a given time that prevents a task from completing successfully, and an
acceptable solution is to simply skip the bad data.

Cursors can be updated as follows:

```shell
$ nomulus -e {ENVIRONMENT} update_cursors exampletld --type RDE_STAGING \
    --timestamp 2016-09-01T00:00:00Z
Change cursorTime of RDE_STAGING for Scope:exampletld to 2016-09-01T00:00:00Z

Perform this command? (y/N): Y
Running ...
Updated 1 cursors.
```

## gTLD reporting

gTLD registry operators are required by ICANN to provide various reports (ccTLDs
are not generally subject to these requirements). The Nomulus system provides
some of these reports, but others will need to be implemented using custom
scripts.

### Registry Data Escrow (RDE)

[RDE](https://newgtlds.icann.org/en/applicants/data-escrow) is a daily deposit
of the contents of the registry, sent to a third-party escrow provider. The
details are contained in Specification 2 of the
[registry agreement][registry-agreement].

Nomulus provides
[code to generate and send these deposits](./operational-procedures/rde-deposits.md).

### Monthly registry activity and transaction reporting

ICANN requires monthly activity and transaction reporting. The details are
contained in Specification 3 of the [registry agreement][registry-agreement].

These reports are generated by querying BigQuery, using database snapshots
loaded into BigQuery. The default `DnsCountQueryCoordinator` implementation
(`CloudDnsCountQueryCoordinator`) relies on Google-internal DNS tables, so
external users will need to provide their own implementation to query their DNS
statistics.

### Zone File Access (ZFA)

ICANN requires a mechanism for them to be able to retrieve DNS zone file
information. The details are contained in part 2 of Specification 4 of the
[registry agreement][registry-agreement].

This information will come from the DNS server, rather than Nomulus itself, so
ZFA is not directly part of the Nomulus release.

### Bulk Registration Data Access (BRDA)

BRDA is a weekly archive of the contents of the registry. The details are
contained in part 3 of Specification 4 of the
[registry agreement][registry-agreement].

ICANN uses sFTP to retrieve BRDA data from a server provided by the registry.
Nomulus provides
[code to generate these deposits](./operational-procedures/brda-deposits.md),
but a separate sFTP server must be configured, and the deposits must be moved
onto the server for access by ICANN.

### Spec 11 reporting

[Spec 11][spec-11] reporting must be provided to ICANN as part of their
anti-abuse efforts. This is covered in Specification 11 of the
[registry agreement][registry-agreement], but the details are little spotty.
Nomulus provides
[code](https://github.com/google/nomulus/blob/master/core/src/main/java/google/registry/beam/spec11/Spec11Pipeline.java)
to generate and send these reports, run on
[a schedule](https://github.com/google/nomulus/blob/master/core/src/main/java/google/registry/config/files/tasks/cloud-scheduler-tasks-production.xml#L257-L267)

[distribution]: https://cloud.google.com/monitoring/api/ref_v3/rest/v3/TypedValue#Distribution
[registry-agreement]: https://newgtlds.icann.org/sites/default/files/agreements/agreement-approved-09jan14-en.pdf
[spec-11]: https://newgtlds.icann.org/en/applicants/agb/base-agreement-specs-pic-faqs
