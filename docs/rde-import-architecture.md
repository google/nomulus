# Registry Data Import Architecture

The Registry Data Import feature was designed to handle escrow files from other registries
with millions of domains. In the spirit of divide and conquer, the mapreduce library
is used to break up the work of the import into smaller chunks that can be processed
in a reasonable period of time. This process is broken down into four separate mapreduce
jobs that must be run in sequence due to how datastore transactions work and due
to dependencies between registry objects. The steps are broken up as follows:

__Initial Setup__ - This is a set of manual steps that must be completed before the
process can be run, and is out of the scope of this document. See [Usage](./rde-import-usage.md)
for more details.

__Contacts Import__ - Reads contact entries from an escrow file and saves
them as ContactResource objects. History entries are also created for the ContactResource
objects. This process depends on initial setup, but does not depend on any previous
step being run.

__Hosts Import__ - Reads host entries from an escrow file and saves them as
HostResource objects. History entries are also created for the HostResource objects.
This process depends on initial setup, but does not depend on any previous step being run.

__Domains Import__ - Reads domain entries from an escrow file and saves them as DomainResource
objects. For each domain imported, a history entry, autorenew billing event and autorenew
poll message will also be created. For domains that are in pending transfer state, the
import process will also create future entities for automatic server approval in the same
fashion as domain transfer request epp messages.

__Hosts Link__ - Reads host entries from an escrow file and links in-zone hosts to their
superordinate domains. This is the last step because both hosts and domains have to be
imported before the link can be made in both directions.

## Components

Each mapreduce job (with the exception of Hosts Link) is made up of a similar set of components.
Note that much of the work that is done by each job strongly resembles the inversion of the
Registry Data Export feature, and reuses the Jaxb representations of the xml elements that
compose escrow files.

__Parser__ - The parser is the lowest level of the import process. This component parses
an escrow file (provided as an open stream from cloud storage) into discreet Jaxb objects.
The parser maintains an internal cursor in the xml file that represents the next element
to be read, and can advance to and skip any number of elements.

__Reader__ - The reader is configured by each mapreduce job to load an escrow file from
cloud storage and use a parser to read a selected subset of the file, forwarding the results
to a mapper.

__Input__ - An input is responsible for determining how many reader instances to create
and which section of the escrow file should be consumed by each reader.

__Converter__ - The converter accepts a Jaxb object and returns an equivalent resource
that can be saved to the datastore.

__Import Utility Logic__ - Common import logic is consolidated into a single place,
such as creation of index entities and escrow file validation.

__Mapper__ - The mapper accepts a stream of Jaxb objects from the reader and uses the
converter to map them to resource objects. Then for each resource object produced,
the mapper will attempt to save the resource and any related objects to the datastore
in a transaction. This is an idempotent operation; if any resource has been previously
imported by the process, it will be ignored.

__Action Endpoint__ - The action endpoint is responsible for accepting requests to launch
each step of the import process, bootstrapping mapreduce jobs, and redirecting the client
to the status page of the import job. This is the entry point of the import process.