# Installation

Information on how to download and install the Domain Registry project and get a
working running instance.

## Prerequisites

* A recent version of the
[Java 7 JDK](http://www.oracle.com/technetwork/java/javase/downloads/jdk7-downloads-1880260.html)
(note that Java 8 support should be coming to App Engine soon).
* [Bazel](http://bazel.io/), which is the buld system that
the Domain Registry project uses.  The minimum required version is 0.3.1.
* [Google App Engine SDK for Java](https://cloud.google.com/appengine/downloads#Google_App_Engine_SDK_for_Java),
especially `appcfg`, which is a command-line tool that runs locally that is used
to communicate with the App Engine cloud.
* [Create an application](https://cloud.google.com/appengine/docs/java/quickstart)
  on App Engine to deploy to, and set up `appcfg` to connect to it.

## Downloading the code

Start off by grabbing the latest version from the
[Domain Registry project on GitHub](https://github.com/google/domain-registry).
This can be done either by cloning the Git repo (if you expect to make code
changes to contribute back), or simply by downloading the latest release as a
zip file.  This guide will cover cloning from Git, but should work almost
identically for downloading the zip file.

    $ git clone git@github.com:google/domain-registry.git
    Cloning into 'domain-registry'...
    [ .. snip .. ]
    $ cd domain-registry
    $ ls
    apiserving       CONTRIBUTORS  java        LICENSE    scripts
    AUTHORS          docs          javascript  python     third_party
    CONTRIBUTING.md  google        javatests   README.md  WORKSPACE

The most important directories are:

* `docs` -- the documentation (including this install guide)
* `java/google/registry` -- all of the source code of the main project
* `javatests/google/registry` -- all of the tests for the project
* `python` -- Some Python reporting scripts
* `scripts` -- Scripts for configuring development environments

Everything else, especially `third_party`, contains dependencies that are used
by the project.

## Building and verifying the code

The first step is to verify that the project successfully builds.  This will
also download and install dependencies.

    $ bazel --batch build //java{,tests}/google/registry/...
    INFO: Found 584 targets...
    [ .. snip .. ]
    INFO: Elapsed time: 124.433s, Critical Path: 116.92s

There may be some warnings thrown, but if there are no errors, then you are good
to go.  Next, run the tests to verify that everything works properly.  The tests
can be pretty resource intensive, so experiment with different values of
parameters to optimize between low running time and not slowing down your
computer too badly.

    $ nice bazel --batch test //javatests/google/registry/... \
      --local_resources=1000,3,1.0
    Executed 360 out of 360 tests: 360 tests pass.

## Running a development instance locally

`RegistryTestServer` is a lightweight test server for the registry that is
suitable for running locally for development.  It uses local versions of all
Google Cloud Platform dependencies, when available.  Correspondingly, its
functionality is limited compared to a Domain Registry instance running on an
actual App Engine instance.  To see its command-line parameters, run:

    $ bazel run //javatests/google/registry/server -- --help

Then to fire up an instance of the server, run:

    $ bazel run //javatests/google/registry/server {your params}

Once it is running, you can interact with it via normal `registry_tool`
commands, or view the registrar console in a web browser by navigating to
http://localhost:8080/registrar .

## Deploying the code

You are going to need to configure a variety of things before a working
installation can be deployed (see the Configuration guide for that).  It's
recommended to at least confirm that the default version of the code can be
pushed at all first before diving into that, with the expectation that things
won't work properly until they are configured.

All of the [EAR](https://en.wikipedia.org/wiki/EAR_(file_format)) and
[WAR](https://en.wikipedia.org/wiki/WAR_(file_format)) files for the different
environments, which were built in the previous step, are outputted to the
`bazel-genfiles` directory as follows:

    $ (cd bazel-genfiles/java/google/registry && ls *.ear)
    registry_alpha.ear  registry.ear        registry_sandbox.ear
    registry_crash.ear  registry_local.ear

    $ (cd bazel-genfiles/java/google/registry && ls *.war)
    mandatory_stuff.war           registry_default_local.war
    registry_backend_alpha.war    registry_default_sandbox.war
    registry_backend_crash.war    registry_default.war
    registry_backend_local.war    registry_tools_alpha.war
    registry_backend_sandbox.war  registry_tools_crash.war
    registry_backend.war          registry_tools_local.war
    registry_default_alpha.war    registry_tools_sandbox.war
    registry_default_crash.war    registry_tools.war

Note that there is one EAR file per environment (production is the one without
an environment in the file name), whereas there is one WAR file per service per
environment, with there being three services in total: default, backend, and
tools.

Then, use `appcfg` to [deploy the WAR files](https://cloud.google.com/appengine/docs/java/tools/uploadinganapp):

    $ cd /path/to/downloaded/appengine/app
    $ /path/to/appcfg.sh update /path/to/registry_default.war
    $ /path/to/appcfg.sh update /path/to/registry_backend.war
    $ /path/to/appcfg.sh update /path/to/registry_tools.war

## Creating test entities

Once the code is deployed, the next step is to play around with creating some
entities in the registry, including a TLD, a registrar, a domain, a contact, and
a host.  Note: Do this on a non-production environment!  All commands below use
`registry_tool` to interact with the running registry system; see the
documentation on `registry_tool` for additional information on it.  We'll assume
that all commands below are running in the `alpha` environment; if you named
your environment differently, then use that everywhere that `alpha` appears.

### Create a TLD

Pick the name of a TLD to create.  For the purposes of this example we'll use
"example", which conveniently happens to be an ICANN reserved string, meaning
it'll never be created for real on the Internet at large.

    $ registry_tool -e alpha create_tld example --roid_suffix EXAMPLE \
      --initial_tld_state GENERAL_AVAILABILITY --tld_type TEST
    [ ... snip confirmation prompt ... ]
    Perform this command? (y/N): y
    Updated 1 entities.

The name of the TLD is the main parameter passed to the command.  The initial
TLD state is set here to general availability, bypassing sunrise and landrush,
so that domain names can be created immediately in the following steps.  The TLD
type is set to `TEST` (the other alternative being `REAL`) for obvious reasons.

`roid_suffix` is the suffix that will be used for repository ids of domains on
the TLD -- it must be all uppercase and a maximum of eight ASCII characters.
ICANN
[recommends](https://www.icann.org/resources/pages/correction-non-compliant-roids-2015-08-26-en)
a unique ROID suffix per TLD.  The easiest way to come up with one is to simply
use the entire uppercased TLD string if it is eight characters or fewer, or
abbreviate it in some sensible way down to eight if it is longer.  The full repo
id of a domain resource is a hex string followed by the suffix,
e.g. `12F7CDF3-EXAMPLE` for our example TLD.

### Create a registrar

Now we need to create a registrar and give it access to operate on the example
TLD.  For the purposes of our example we'll name the registrar "Acme".

    $ registry_tool -e alpha create_registrar acme --name 'ACME Corp' \
      --registrar_type TEST --password hunter2 \
      --icann_referral_email blaine@acme.example --street '123 Fake St' \
      --city 'Fakington' --state MA --zip 12345 --cc US --allowed_tlds example
    [ ... snip confirmation prompt ... ]
    Perform this command? (y/N): y
    Updated 1 entities.
    Skipping registrar groups creation because only production and sandbox
    support it.

In the command above, "acme" is the internal registrar id that is the primary
key used to refer to the registrar.  The `name` is the display name that is used
less often, primarily in user interfaces.  We again set the type of the resource
here to `TEST`.  The `password` is the EPP password that the registrar uses to
log in with.  The `icann_referral_email` is the email address associated with
the initial creation of the registrar -- note that the registrar cannot change
it later.  The address fields are self-explanatory (note that other parameters
are available for international addresses).  The `allowed_tlds` parameter is a
comma-delimited list of TLDs that the registrar has access to, and here is set
to the example TLD.

### Create a contact

Now we want to create a contact, as a contact is required before a domain can be
created.  Contacts can be used on any number of domains across any number of
TLDs, and contain the information on who owns or provides technical support for
a TLD.  These details will appear in WHOIS queries.  Note the `-c` parameter,
which stands for client identifier: This is used on most `registry_tool`
commands, and is used to specify the id of the registrar that the command will
be executed using.  Contact, domain, and host creation all work by constructing
an EPP message that is sent to the registry, and EPP commands need to run under
the context of a registrar.  The "acme" registrar that was created above is used
for this purpose.

    $ registry_tool -e alpha create_contact -c acme --id abcd1234 \
      --name 'John Smith' --street '234 Fake St' --city 'North Fakington' \
      --state MA --zip 23456 --cc US --email jsmith@e.mail
    [ ... snip EPP response ... ]

The `id` is the contact id, and is referenced elsewhere in the system (e.g. when
a domain is created and the admin contact is specified).  The `name` is the
display name of the contact, which is usually the name of a company or of a
person.  Again, the address fields are required, along with an `email`.

### Create a host

Hosts are used to specify the IP addresses (either v4 or v6) that are associated
with a given nameserver.  Note that hosts may either be in-bailiwick (on a TLD
that this registry runs) or out-of-bailiwick.  In-bailiwick hosts may
additionally be subordinate (a subdomain of a domain name that is on this
registry).  Let's create an out-of-bailiwick nameserver, which is the simplest
type.

    $ my_registry_tool -e alpha create_host -c acme --host ns1.google.com
    [ ... snip EPP response ... ]

Note that hosts are required to have IP addresses if they are subordinate, and
must not have IP addresses if they are not subordinate.  Use the `--addresses`
parameter to set the IP addresses on a host, passing in a comma-delimited list
of IP addresses in either IPv4 or IPv6 format.

### Create a domain

To tie it all together, let's create a domain name that uses the above contact
and host.

    $ registry_tool -e alpha create_domain -c acme --domain fake.example \
      --admin abcd1234 --tech abcd1234 --registrant abcd1234 \
      --nameservers ns1.google.com
    [ ... snip EPP response ... ]

Note how the same contact id (from above) is used for the administrative,
technical, and registrant contact.  This is quite common on domain names.

To verify that everything worked, let's query the WHOIS information for
fake.example:

    $ registry_tool -e alpha whois_query fake.example
    [ ... snip WHOIS response ... ]

You should see all of the information in WHOIS that you entered above for the
contact, nameserver, and domain.
