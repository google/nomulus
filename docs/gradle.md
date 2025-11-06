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

## Deploy to GCP Test Projects

If your [configuration](configuration.md) is up to date with the proper test
projects configured, you can deploy to GCP through the Gradle command line.

Use the Gradle task `deployNomulus` to build and deploy to a GCP test project
providing the test project as an argument, e.g.

```shell
./gradlew deployNomulus -Penvironment=alpha
```

### Notable Issues

Test suites (RdeTestSuite and TmchTestSuite) are ignored to avoid duplicate
execution of tests. Neither suite performs any shared test setup routine, so it
is easier to exclude the suite classes than individual test classes. This is the
reason why all test tasks in the :core project contain the exclude pattern
'"**/*TestCase.*", "**/*TestSuite.*"'

Some Nomulus tests are not hermetic: they modify global state, but do not clean
up on completion. This becomes a problem with Gradle. In the beginning we forced
Gradle to run every test class in a new process, and incurred heavy overheads.
Since then, we have fixed some tests, and manged to divide all tests into two
suites that do not have intra-suite conflicts (`fragileTest` and `standardTest`)
