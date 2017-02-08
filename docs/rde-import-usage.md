# Registry Data Import Usage Guide

In order to import an RDE escrow file into Nomulus, four different mapreduce processes
must be run in sequence. Future iterations of this feature may automate the sequence,
but for now each step of the process must be run manually.

Note that only contacts, hosts and domains are imported by this process. Other objects
such as registrars and top level domains are not imported automatically, and must be
configured before running this import process (see below).

## Prerequisites

This document assumes that the import process is being executed against a
fully functioning instance of Nomulus that is set up with its own unique
project id. See the [project overview](/README.md) for details and helpful links.

Before launching any of the import jobs, the top level domain (TLD) and all
registrars that are included in the escrow file must be created in the registry;
these cannot be created automatically. Detailed instructions on how to create
TLDs, registrars and registrar contacts can be found in the
[first steps tutorial](/docs/first-steps-tutorial.md).

## How to load an escrow file

First of all, ensure that all of the cloud storage buckets are set up for nomulus.
See [architecture documentation](/docs/app-engine-architecture.md) for details.
The escrow file that will be imported should be uploaded to the `PROJECT-rde-import`
cloud storage bucket. The escrow file should not be compressed or encrypted. When
launching each mapreduce job, reference the absolute path to the file (just the path,
not the bucket name) in the `path` argument.

__TODO:__ Add `PROJECT-rde-import` bucket requirement to architecture doc.

## Overview of import steps

Due to the huge variety in size of domain registries, the number of objects included
in escrow files can also vary widely. In order to process these files in a way that
scales, the import of contacts, hosts and domains has been implemented as a series
of four mapreduce jobs. The four jobs are summarized as follows:

* Contacts Import - Creates contacts in the registry.
* Hosts Import - Creates host objects in the registry. Note that only host objects
  are supported at this time; escrow files with host attributes are not supported
  and will be rejected by the import process.
* Domains Import - Creates domain objects in the registry. This step will also
  publish NS records and A records to dns as necessary based on referenced host
  objects.
* Hosts Link - Links hosts to their superordinate domain objects. This step is
  necessary to establish data integrity for imported objects.

The import steps __must__ be run in this order: Contacts Import, Hosts Import,
Domains Import, Hosts Link.

## Executing the import process

The import process steps must be executed by a user that is logged in as an administrator
of the deployed Nomulus instance. Currently the way to launch the process is to
manually enter the proper url into the user's web browser, which will kick off
the mapreduce job and load its status page. The status page will serve as a way
to monitor the progress of a job until completion. Once each job is completed,
the user can launch the next step in the same fashion.

Parameters:
* path - This is the path to the escrow file in cloud storage.
* mapShards - This is the number of shards that will be used to process the file.
  The process has been tested with a mapShards setting of 100, which seems to perform
  well in most cases. 

The import process is deployed in the `backend` service, so "backend-dot-" will be prepended
to the hostname. Replace `PROJECT` below with the unique project name under which
the Nomulus instance is deployed, and `PATH` with the path to the escrow file.

Launch Contacts Import:
`https://backend-dot-PROJECT.appspot.com/_dr/importRdeContacts?path=PATH&mapShards=100`

Launch Hosts Import:
`https://backend-dot-PROJECT.appspot.com/_dr/importRdeHosts?path=PATH&mapShards=100`

Launch Domains Import:
`https://backend-dot-PROJECT.appspot.com/_dr/importRdeDomains?path=PATH&mapShards=100`

Launch Hosts Link:
`https://backend-dot-PROJECT.appspot.com/_dr/linkRdeHosts?path=PATH&mapShards=100`

For each job, the mapreduce user interface will display the status of the running job.
The job is finished when all of the boxes on the left turn green (at which point the next
step of the process can be safely launched). If any of the boxes turn red, it means
that the job failed and the logs should be consulted for errors (see below).

Note that each of these steps is idempotent, and can be run many times with no
harmful side effects or duplicated data.

## Monitoring and Troubleshooting

On the status page, several counters will be shown as the job progresses, indicating
the number of operations attempted, how many succeeded, how many were ignored, and
if there were any errors. This is a good tool to understand at a high level how
the job is progressing. The counters from a completed job can also be compared
against the known count of resources from an escrow file to determine if the
import was successful or not.

For a more detailed view, open up the logging for the project in the
[Google Cloud Console](https://console.cloud.google.com). The application logs
from the import process will show up under requests with a url that
starts with `/_dr/mapreduce/workerCallback` - the logs provide a detailed view
of which resources were read from file, which were imported, which had already
existed before the import, and which failed to import.

## Known Limitations

Currently the ID of all registrars in the escrow file must match those that are
already configured in the registry. Future work is planned to map between different
internal and external registrar IDs by using the IANA IDs, which are always
consistent between registries.


