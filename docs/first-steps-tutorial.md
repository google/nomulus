# First steps tutorial

This document covers the first steps of creating some test entities in a newly
deployed and configured testing environment. It isn't required, but it does help
gain familiarity with the system. If you have not already done so, you must
first complete [installation](./install.md) and [initial
configuration](./configuration.md).

Note: Do not create these entities on a production environment! All commands
below use the [`nomulus` admin tool](./admin-tool.md) to interact with the
running registry system. We'll assume that all commands below are running in the
`alpha` environment; if you named your environment differently, then use that
everywhere that `alpha` appears.

## Temporary extra steps

Using the `nomulus` admin tool currently requires two additional steps to enable
full functionality.  These steps should _not_ be done for a production
deployment - a suitable solution for production is in progress.

1.  Modify the `tools` module `web.xml` file to remove admin-only restrictions.
    Look for the `<auth-constraint>admin</auth-constraint>` element.  Comment
    out this element, and redeploy the tools module to your live app.

2.  Set up [application default credentials][app-default-creds] in `gcloud` by
    running the following command:

```shell
$ gcloud beta auth application-default login
Your browser has been opened to visit:
[ ... snip logging in via browser ... ]
You are now logged in as [user@email.tld].
```

[app-default-creds]: https://developers.google.com/identity/protocols/application-default-credentials

## Create a TLD

Pick the name of a TLD to create. For the purposes of this example we'll use
"example", which conveniently happens to be an ICANN reserved string, meaning
it'll never be created for real on the Internet at large.

```shell
$ nomulus -e alpha create_tld example --roid_suffix EXAMPLE \
  --initial_tld_state GENERAL_AVAILABILITY --tld_type TEST
[ ... snip confirmation prompt ... ]
Perform this command? (y/N): y
Updated 1 entities.
```

*   `-e` is the environment name (`alpha` in this example).
*   `create_tld` is the subcommand to create a TLD. The TLD name is "example"
    which happens to be an ICANN reserved string, and therefore "example" can
    never be created on the Internet at large.
*   `--initial_tld_state` defines the initital state of the TLD.
    `GENERAL_AVAILABILITY`, in the case of our example, allows you to
    immediately create domain names by bypassing the sunrise and landrush domain
    registration periods.
*   `--tld_type` is the type of TLD. `TEST` identifies that the TLD is for
    testing purposes, where `REAL` identifies the TLD is a live TLD.
*   `roid_suffix` is the suffix that will be used for repository ids of domains
    on the TLD. This suffix must be all uppercase and a maximum of eight ASCII
    characters and can be set to the upper-case equivalent of our TLD name (if
    it is 8 characters or fewer), such as "EXAMPLE." You can also abbreviate the
    upper-case TLD name down to 8 characters. Refer to the [gTLD Registry
    Advisory: Correction of non-compliant ROIDs][roids] for further information.

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

## Create a contact

Now we want to create a contact, as a contact is required before a domain can be
created. Contacts can be used on any number of domains across any number of
TLDs, and contain the information on who owns or provides technical support for
a TLD. These details will appear in WHOIS queries.

```shell
$ nomulus -e alpha create_contact -c acme --id abcd1234 \
  --name 'John Smith' --street '234 Fake St' --city 'North Fakington' \
  --state MA --zip 23456 --cc US --email jsmith@e.mail
[ ... snip EPP response ... ]
```

Where:

*   `create_contact` is the subcommand to create a contact.
*   `-c` is used to define the registrar. The `-c` option is used with most
    `registry_tool` commands to specify the id of the registrar executing the
    command. Contact, domain, and host creation all work by constructing an EPP
    message that is sent to the registry, and EPP commands need to run under the
    context of a registrar. The "acme" registrar that was created above is used
    for this purpose.
*   `--id` is the contact id, and is referenced elsewhere in the system (e.g.
    when a domain is created and the admin contact is specified).
*   `--name` is the display name of the contact, which is usually the name of a
    company or of a person.

The address and `email` fields are required to create a contact.

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
$ nomulus -e alpha create_domain fake.example --client acme --admins abcd1234 \
  --techs abcd1234 --registrant abcd1234 --nameservers ns1.google.com
[ ... snip EPP response ... ]
```

Where:

*   `create_domain` is the subcommand to create a domain name. It accepts a
    whitespace-separated list of domain names to be created
*   `--client` is used to define the registrar.
*   `--admins` is the administrative contact's id(s).
*   `--techs` is the technical contact's id(s).
*   `--registrant` is the registrant contact's id.
*   `--nameservers` is a comma-separated list of hosts.

Note how the same contact id is used for the administrative, technical, and
registrant contact. It is common for domain names to use the same details for
all contacts on a domain name.

## Verify test entities using WHOIS

To verify that everything worked, let's query the WHOIS information for
fake.example:

```shell
$ nomulus -e alpha whois_query fake.example
[ ... snip WHOIS response ... ]
```

You should see all of the information in WHOIS that you entered above for the
contact, nameserver, and domain.

[roids]: https://www.icann.org/resources/pages/correction-non-compliant-roids-2015-08-26-en
