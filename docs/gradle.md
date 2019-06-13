# Gradle Build Documentation

## Initial Setup

Install Gradle on your local host, then run the following commands from this
directory:

```shell
# One-time command to add gradle wrapper:
gradle wrapper

# Start the build:
./gradlew build
```

From now on, use './gradlew build' or './gradlew test' to build and test your
changes.

To upgrade to a new Gradle version for this project, use:

```shell
gradle wrapper --gradle-version version-number
```

## Deploy to AppEngine

Use the Gradle task 'appengineDeploy' to build and deploy to AppEngine. For now
you must update the appengine.deploy.project in build.gradle to your
GCP project ID.

To deploy the Gradle build, you will need the Google Cloud SDK and its
app-engine-java component.


### Notable Issues

Test suites (RdeTestSuite and TmchTestSuite) are ignored to avoid duplicate
execution of tests. Neither suite performs any shared test setup routine, so it
is easier to exclude the suite classes than individual test classes. This is the
reason why all test tasks in the :core project contain the exclude pattern
'"**/*TestCase.*", "**/*TestSuite.*"'

Many Nomulus tests are not hermetic: they modify global state (e.g., the shared
local instance of Datastore) but do not clean up on completion. This becomes a
problem with Gradle. In the beginning we forced Gradle to run every test class
in a new process, and incurred heavy overheads. Since then, we have fixed some
tests, and manged to divide all tests into three suites that do not have
intra-suite conflicts. We will revisit the remaining tests soon.

Note that it is unclear if all conflicting tests have been identified. More may
be exposed if test execution order changes, e.g., when new tests are added or
execution parallelism level changes.
