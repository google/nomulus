# First steps tutorial

This document covers the first steps of creating some test entities in a newly
deployed and configured testing environment. It isn't required, but it does help
gain familiarity with the system. If you have not already done so, you must
first complete [installation](./install.md) and
[initial configuration](./configuration.md).

Note: Do not create these entities on a production environment! All commands
below use the [`nomulus` admin tool](./admin-tool.md) to interact with the
running registry system. We'll assume that all commands below are running in the
`alpha` environment; if you named your environment differently, then use that
everywhere that `alpha` appears.

## Create a TLD

Pick the name of a TLD to create. For the purposes of this example we'll use
"example", which conveniently happens to be an ICANN reserved string, meaning
it'll never be created for real on the Internet at large. Then,
[create a TLD](operational-procedures/modifying-tlds.md) using the
[example template](https://github.com/google/nomulus/blob/master/core/src/test/resources/google/registry/tools/tld.yaml)
as a guide.

The fields you'll want to change from the template: * `driveFolderId` should be
null * `roidSuffix` should be `EXAMPLE` -- this is the suffix that will be used
for repository ids of domains on the TLD. This suffix must be all uppercase and
a maximum of eight ASCII characters and can be set to the upper-case equivalent
of our TLD name (if it is 8 characters or fewer), such as "EXAMPLE." You can
also abbreviate the upper-case TLD name down to 8 characters. Refer to the
[gTLD Registry Advisory: Correction of non-compliant ROIDs][roids] for further
information. * `tldStr` should be `example` * `tldType` should be `TEST`, which
identifies that the TLD is for testing purposes, whereas `REAL` would identify
the TLD as a live TLD

```shell
$ nomulus -e alpha configure_tld --input=example.yaml
```

## Create a registrar

Now we need to create a registrar and give it access to operate on the example
TLD. For the purposes of our example we'll name the registrar "Acme".

```shell
$ nomulus -e alpha create_registrar acme --name 'ACME Corp' \
  --registrar_type TEST --password hunter2 \
  --icann_referral_email blaine@acme.example --street '123 Fake St' \
  --city 'Fakington' --state MA --zip 12345 --cc US --allowed_tlds example
[ ... snip confirmation prompt ... ]
Perform this command? (y/N): y
Updated 1 entities.
Skipping registrar groups creation because only production and sandbox
support it.
```

Where:

*   `create_registrar` is the subcommand to create a registrar. The argument you
    provide ("acme") is the registrar id, called the client identifier, that is
    the primary key used to refer to the registrar both internally and
    externally.
*   `--name` indicates the display name of the registrar, in this case `ACME
    Corp`.
*   `--registrar_type` is the type of registrar. `TEST` identifies that the
    registrar is for testing purposes, where `REAL` identifies the registrar is
    a real live registrar.
*   `--password` is the password used by the registrar to log in to the domain
    registry system.
*   `--icann_referral_email` is the email address associated with the initial
    creation of the registrar. This address cannot be changed.
*   `--allowed_tlds` is a comma-delimited list of top level domains where this
    registrar has access.

## Create a host

Hosts are used to specify the IP addresses (either v4 or v6) that are associated
with a given nameserver. Note that hosts may either be in-bailiwick (on a TLD
that this registry runs) or out-of-bailiwick. In-bailiwick hosts may
additionally be subordinate (a subdomain of a domain name that is on this
registry). Let's create an out-of-bailiwick nameserver, which is the simplest
type.

```shell
$ nomulus -e alpha create_host -c acme --host ns1.google.com
[ ... snip EPP response ... ]
```

Where:

*   `create_host` is the subcommand to create a host.
*   `--host` is the name of the host.
*   `--addresses` (not used here) is the comma-delimited list of IP addresses
    for the host in IPv4 or IPv6 format, if applicable.

Note that hosts are required to have IP addresses if they are subordinate, and
must not have IP addresses if they are not subordinate.

## Create a domain

To tie it all together, let's create a domain name that uses the above contact
and host.

```shell
$ nomulus -e alpha create_domain fake.example --client acme --nameservers ns1.google.com
[ ... snip EPP response ... ]
```

Where:

*   `create_domain` is the subcommand to create a domain name. It accepts a
    whitespace-separated list of domain names to be created
*   `--client` is used to define the registrar.
*   `--nameservers` is a comma-separated list of hosts.

## Verify test entities using RDAP

To verify that everything worked, let's query the RDAP information for
fake.example:

```shell
$ nomulus -e alpha rdap_query fake.example
[ ... snip RDAP response ... ]
```

You should see all the information in RDAP that you entered above for the
nameserver and domain.

[roids]: https://www.icann.org/resources/pages/correction-non-compliant-roids-2015-08-26-en
