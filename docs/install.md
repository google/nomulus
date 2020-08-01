# Installation

This document covers the steps necessary to download, build, and deploy Nomulus.

## Prerequisites

You will need the following programs installed on your local machine:

*   A recent version of the [Java 11 JDK][java-jdk11].
*   [Google App Engine SDK for Java][app-engine-sdk], and configure aliases to
    to the `gcloud` and `appcfg.sh` utilities (you'll use them a lot).
*   [Git](https://git-scm.com/) version control system.

**Note:** App Engine does not yet support Java 9. Also, the instructions in this
document have only been tested on Linux. They might work with some alterations
on other operating systems.

## Download the codebase

Start off by using git to download the latest version from the [Nomulus GitHub
page](https://github.com/google/nomulus). In the future we will release tagged
stable versions, but for now, just download `HEAD` of the master branch as
follows:

```shell
$ git clone git@github.com:google/nomulus.git
Cloning into 'nomulus'...
[ .. snip .. ]
$ cd nomulus
$ ls
apiserving       CONTRIBUTORS  java        LICENSE    scripts
AUTHORS          docs          javascript  python     third_party
CONTRIBUTING.md  google        javatests   README.md  WORKSPACE
```

The most important directories are:

*   `docs` -- the documentation (including this install guide)
*   `java/google/registry` -- all of the source code of the main project
*   `javatests/google/registry` -- all of the tests for the project
*   `python` -- Some Python reporting scripts
*   `scripts` -- Scripts for configuring development environments

Everything else, especially `third_party`, contains dependencies that are used
by the project.

## Build the codebase

The first step is to build the project, and verify that this completes
successfully. This will also download and install dependencies.

```shell
$ bazel --batch build --javacopt="-target 8 -source 8" \
  //java{,tests}/google/registry/...
INFO: Found 584 targets...
[ .. snip .. ]
INFO: Elapsed time: 124.433s, Critical Path: 116.92s
```

There may be some warnings thrown, but if there are no errors, then you can
proceed. The most important build output files from the build are the
[ear](https://en.wikipedia.org/wiki/EAR_\(file_format\)) files:

```shell
$ ls bazel-genfiles/java/google/registry/*.ear
registry_alpha.ear  registry.ear        registry_sandbox.ear
registry_crash.ear  registry_local.ear
```

Each `ear` file is a compiled version codebase ready to deploy to App Engine for
a specific environment. By default there are five environments, with the unnamed
one being production. Each `ear` file contains App Engine-specific metadata
files in the `META-INF` directory, as well as three directories for the three
services used in the project, `default`, `backend`, and `tools` (each of these
directories is an unpacked
[war](https://en.wikipedia.org/wiki/WAR_\(file_format\)) file.

## (Optional) Run the tests

You can run the tests to verify that all expected functionality succeeds in your
build:

```shell
$ nice bazel --batch test  --javacopt="-target 8 -source 8" \
  //javatests/google/registry/... \
  --local_ram_resources=1000 --local_cpu_resources=3
Executed 360 out of 360 tests: 360 tests pass.
```

**Note:** The tests can be pretty resource intensive, so experiment with
different values of parameters to optimize between low running time and not
slowing down your computer too badly. Refer to the [Bazel User
Manual](https://www.bazel.io/versions/master/docs/bazel-user-manual.html) for
more information.

## Create an App Engine project

First, [create an
application](https://cloud.google.com/appengine/docs/java/quickstart) on Google
Cloud Platform. Make sure to choose a good Project ID, as it will be used
repeatedly in a large number of places. If your company is named Acme, then a
good Project ID for your production environment would be "acme-registry". Keep
in mind that project IDs for non-production environments should be suffixed with
the name of the environment (see the [Architecture
documentation](./architecture.md) for more details). For the purposes of this
example we'll deploy to the "alpha" environment, which is used for developer
testing. The Project ID will thus be `acme-registry-alpha`.

Now log in using the command-line Google Cloud Platform SDK and set the default
project to be this one that was newly created:

```shell
$ gcloud auth login
Your browser has been opened to visit:
[ ... snip logging in via browser ... ]
You are now logged in as [user@email.tld].
$ gcloud config set project acme-registry-alpha
```

## Deploy the code to App Engine

One interesting quirk about the App Engine SDK is that it can't use `ear` files
in their packed form; you have to unpack them first, then run `appcfg.sh`
commands on the unpacked contents of the `ear`. So grab the compiled `ear` file
for the alpha environment (it's one of the outputs of the build step earlier),
copy it to another directory, and extract it:

```shell
$ mkdir /path/to/app-dir/acme-registry-alpha
$ unzip bazel-genfiles/java/google/registry/registry_alpha.ear \
  -d /path/to/app-dir/acme-registry-alpha
$ ls /path/to/app-dir/acme-registry-alpha
backend  default  META-INF  tools
```

Now deploy the code to App Engine. We must provide a version string, e.g., live.

```shell
$ appcfg.sh -A acme-registry-alpha --enable_jar_splitting \
  -V live update /path/to/app-dir/acme-registry-alpha
Reading application configuration data...
Processing module default
Oct 05, 2016 12:16:59 PM com.google.apphosting.utils.config.IndexesXmlReader readConfigXml
INFO: Successfully processed /usr/local/google/home/mcilwain/Code/acme-registry-alpha/./default/WEB-INF/datastore-indexes.xml
Ignoring application.xml context-root element, for details see https://developers.google.com/appengine/docs/java/modules/#config
Processing module backend
Ignoring application.xml context-root element, for details see https://developers.google.com/appengine/docs/java/modules/#config
Processing module tools
Ignoring application.xml context-root element, for details see https://developers.google.com/appengine/docs/java/modules/#config

Beginning interaction for module default...
0% Created staging directory at: '/tmp/appcfg7185922945263751117.tmp'
5% Scanning for jsp files.
20% Scanning files on local disk.
[ ... snip ... ]
Beginning interaction for module backend...
[ ... snip ... ]
Beginning interaction for module tools...
[ ... snip ... ]
```

Note that the `update` command deploys all three services of Nomulus. In the
future, if you've only made changes to a single service, you can save time and
upload just that one using the `-M` flag to specify the service to update.

To verify successful deployment, visit
https://acme-registry-alpha.appspot.com/registrar in your browser (adjusting
appropriately for the project ID that you actually used). If the project
deployed successfully, you'll see a "You need permission" page indicating that
you need to configure the system and grant access to your Google account. It's
time to go to the next step, configuration.

Configuration is handled by editing code, rebuilding the project, and deploying
again. See the [configuration guide](./configuration.md) for more details. Once
you have completed basic configuration (including most critically the project ID
in your copy of `ProductionRegistryConfigExample`), you can rebuild and start
using the `nomulus` tool to create test entities in your newly deployed system.
See the [first steps tutorial](./first-steps-tutorial.md) for more information.

[app-engine-sdk]: https://cloud.google.com/appengine/docs/java/download
[java-jdk11]: https://www.oracle.com/java/technologies/javase-downloads.html 
