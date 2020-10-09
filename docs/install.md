# Installation

This document covers the steps necessary to download, build, and deploy Nomulus.

## Prerequisites

You will need the following programs installed on your local machine:


*   A recent version of the [Java 11 JDK][java-jdk11].
*   [Google App Engine SDK for Java][app-engine-sdk], and configure aliases to
    to the `gcloud` and `appcfg.sh` utilities (you'll use them a lot).
*   [Git](https://git-scm.com/) version control system.
*   Python version 3.7 or newer.

**Note:** App Engine does not yet support Java 9. Also, the instructions in this
document have only been tested on Linux. They might work with some alterations
on other operating systems.

## Download the codebase

Start off by using git to download the latest version from the [Nomulus GitHub
page](https://github.com/google/nomulus). You may checkout any of the daily
tagged versions (e.g. `nomulus-20200629-RC00`), but in general it is also
safe to simply checkout from HEAD:

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

Most of the directory tree is organized into gradle sub-projects (see
`settings.gradle` for details).  The following other top-level directories are
also defined:

*   `buildSrc` -- Gradle extensions specific to our local build and release
    methodology.
*   `config` -- Tools for build and code hygiene.
*   `docs` -- The documentation (including this install guide)
*   `gradle` -- Configuration and code managed by the gradle build system.
*   `java-format` -- The Google java formatter and wrapper scripts to use it
    incrementally.
*   `python` -- Some Python reporting scripts
*   `release` -- Configuration for our continuous integration process.
*   `third_party` -- External dependencies.

## Build the codebase

The first step is to build the project, and verify that this completes
successfully. This will also download and install dependencies.

```shell
$ ./nom_build build
Starting a Gradle Daemon (subsequent builds will be faster)
Plugins: Using default repo...

> Configure project :buildSrc
Java dependencies: Using Maven central...
[ .. snip .. ]
```

The `nom_build` script is just a wrapper around `gradlew`.  Its main
additional value is that it formalizes the various properties used in the
build as command-line flags.

The "build" command builds all of the code and runs all of the tests.  This
will take a while.

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

Now modify `projects.gradle` with the name of your new project:

<pre>
// The projects to run your deployment Nomulus application.
rootProject.ext.projects = ['production': 'your-production-project',
                            'sandbox'   : 'your-sandbox-project',
                            'alpha'     : <strong>'acme-registry-alpha',</strong>
                            'crash'     : 'your-crash-project']
</pre>

Next follow the steps in [configuration](./configuration.md) to configure the
complete system or, alternately, read on for an initial deploy in which case
you'll need to deploy again after configuration.

## Deploy the code to App Engine

AppEngine deployment with gradle is straightforward:

    $ ./nom_build appengineDeploy --environment=alpha

To verify successful deployment, visit
https://acme-registry-alpha.appspot.com/registrar in your browser (adjusting
appropriately for the project ID that you actually used). If the project
deployed successfully, you'll see a "You need permission" page indicating that
you need to configure the system and grant access to your Google account. It's
time to go to the next step, configuration.

Configuration is handled by editing code, rebuilding the project, and deploying
again. See the [configuration guide](./configuration.md) for more details.
Once you have completed basic configuration (including most critically the
project ID, client id and secret in your copy of the `nomulus-config-*.yaml`
files), you can rebuild and start using the `nomulus` tool to create test
entities in your newly deployed system. See the [first steps tutorial](./first-steps-tutorial.md)
for more information.

[app-engine-sdk]: https://cloud.google.com/appengine/docs/java/download
[java-jdk11]: https://www.oracle.com/java/technologies/javase-downloads.html 
