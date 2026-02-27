# Installation

This document covers the steps necessary to download, build, and deploy Nomulus.

## Prerequisites

You will need the following programs installed on your local machine:

*   A recent version of the [Java 21 JDK][java-jdk21].
*   The [Google Cloud CLI](https://docs.cloud.google.com/sdk/docs/install-sdk)
    (configure an alias to the `gcloud`utility, because you'll use it a lot)
*   [Git](https://git-scm.com/) version control system.
*   Docker (confirm with `docker info` no permission issues, use `sudo groupadd
    docker` for sudoless docker).
*   Python version 3.7 or newer.
*   gnupg2 (e.g. in run `sudo apt install gnupg2` in Debian-like Linuxes)

**Note:** The instructions in this document have only been tested on Linux. They
might work with some alterations on other operating systems.

## Download the codebase

Start off by using git to download the latest version from the
[Nomulus GitHub page](https://github.com/google/nomulus). You may check out any
of the daily tagged versions (e.g. `nomulus-20260101-RC00`), but in general it
is also safe to simply check out from HEAD:

```shell
$ git clone git@github.com:google/nomulus.git
Cloning into 'nomulus'...
[ .. snip .. ]
```

Most of the directory tree is organized into gradle subprojects (see
`settings.gradle` for details). The following other top-level directories are
also defined:

*   `config` -- Tools for build and code hygiene.
*   `docs` -- The documentation (including this install guide)
*   `gradle` -- Configuration and code managed by the Gradle build system.
*   `integration` -- Testing scripts for SQL changes.
*   `java-format` -- The Google java formatter and wrapper scripts to use it
    incrementally.
*   `release` -- Configuration for our continuous integration process.

## Build the codebase

The first step is to build the project, and verify that this completes
successfully. This will also download and install dependencies.

```shell
$ ./gradlew build
Starting a Gradle Daemon (subsequent builds will be faster)
Plugins: Using default repo...

Java dependencies: Using Maven central...
[ .. snip .. ]
```

The "build" command builds all the code and runs all the tests. This will take a
while.

## Create and configure a GCP project

First,
[create an application](https://cloud.google.com/appengine/docs/java/quickstart)
on Google Cloud Platform. Make sure to choose a good Project ID, as it will be
used repeatedly in a large number of places. If your company is named Acme, then
a good Project ID for your production environment would be "acme-registry". Keep
in mind that project IDs for non-production environments should be suffixed with
the name of the environment (see the
[Architecture documentation](./architecture.md) for more details). For the
purposes of this example we'll deploy to the "alpha" environment, which is used
for developer testing. The Project ID will thus be `acme-registry-alpha`.

Now log in using the command-line Google Cloud Platform SDK and set the default
project to be this one that was newly created:

```shell
$ gcloud auth login
Your browser has been opened to visit:
[ ... snip logging in via browser ... ]
You are now logged in as [user@email.tld].
$ gcloud config set project acme-registry-alpha
```

And make sure the required APIs are enabled in the project:

```shell
$ gcloud services enable \
    container.googleapis.com \
    artifactregistry.googleapis.com \
    sqladmin.googleapis.com \
    secretmanager.googleapis.com \
    compute.googleapis.com
```

Now modify `projects.gradle` with the name of your new project:

<pre>
// The projects to run your deployment Nomulus application.
rootProject.ext.projects = ['production': 'your-production-project',
                            'sandbox'   : 'your-sandbox-project',
                            'alpha'     : <strong>'acme-registry-alpha',</strong>
                            'crash'     : 'your-crash-project']
</pre>

#### Create GKE Clusters

We recommend Standard clusters with Workload Identity enabled to allow pods to
securely access Cloud SQL and Secret Manager. Feel free to adjust the numbers
and sizing as desired.

```shell
$ gcloud container clusters create nomulus-cluster \
    --region=$REGION \
    --workload-pool=$PROJECT_ID.svc.id.goog \
    --num-nodes=3 \
    --enable-ip-alias
$ gcloud container clusters create proxy-cluster \
    --region=$REGION \
    --workload-pool=$PROJECT_ID.svc.id.goog \
    --num-nodes=3 \
    --enable-ip-alias
```

Then create an artifact repository: `shell $ gcloud artifacts repositories
create nomulus-repo \ --repository-format=docker \ --location=$REGION \
--description="Nomulus Docker images"`

See the files and documentation in the `release/` folder for more information on
the release process. You will likely need to customize the internal build
process for your own setup, including internal repository management, builds,
and where Nomulus is deployed.

Configuration is handled by editing code, rebuilding the project, and deploying
again. See the [configuration guide](./configuration.md) for more details. Once
you have completed basic configuration (including most critically the project
ID, client id and secret in your copy of the `nomulus-config-*.yaml` files), you
can rebuild and start using the `nomulus` tool to create test entities in your
newly deployed system. See the [first steps tutorial](./first-steps-tutorial.md)
for more information.

[java-jdk21]: https://www.oracle.com/java/technologies/javase-downloads.html

## Deploy the Beam Pipelines

Deployment of the Beam pipelines to Cloud Dataflow in the testing environments
(alpha and crash) can be done using the following command:

```shell
./gradlew :core:stageBeamPipelines -Penvironment=alpha
```

Pipeline deployment in other environments are through CloudBuild. Please refer
to the [release folder](http://github.com/google/nomulus/release) for details.
