## Summary

This subproject provides two integration tests that ensure schema safety:

*  One test checks for edits to Flyway scripts already deployed to Sandbox
   Production. Such edits will cause schema deployment failure.
*  Another test runs cross-version server/schema integration tests between a
   pull request and the deployed release in Sandbox or Production. If a pull
   request fails this test, it either contains schema changes not compatible
   with Sandbox/Production binaries, or binaries not compatible with the
   current schema in Sandbox/Production. This test may be include in presubmit
   testing.

## Test Artifacts

To support the tests above, each release generates the following test artifacts:

*  schema.jar: The flyway scripts.
*  nomulus-public.jar:  The open-source java classes.
*  nomulus-tests-alldeps.jar: Uber jar with schema test classes and all
   third-party dependencies.

After each deployment in sandbox or production, the deployment process copies
these artifacts to a well-known location, and appends the environment tag to
the file names.

## Usage

Use the convenience scripts in the `integration` folder to run the tests. 

```bash
./integration/run_schema_check.sh -p domain-registry-dev

./integration/run_compatibility_tests.sh -p domain-registry-dev -s sql
./integration/run_compatibility_tests.sh -p domain-registry-dev -s nomulus
```

## Implementation Notes

### Run Tests from Jar

Gradle test runner does not look for runnable tests in jars. We must extract
tests to a directory. For now, only the SqlIntegrationTestSuite.class needs to
be extracted. Gradle has no trouble finding its member classes.

### Hibernate Behavior

If all :core classes (main and test) and dependencies are assembled in a single
jar, Hibernate behaves strangely: every time an EntityManagerFactory is created,
regardless of what Entity classes are specified, Hibernate would scan the entire
jar for all Entity classes and complain about duplicate mapping (due to the
TestEntity classes declared by tests).

We worked around this problem by creating two jars from :core:

*   The nomulus-public.jar: contains the classes and resources in the main
    sourceSet (and excludes internal files under the config package).
*   The nomulus-tests-alldeps.jar: contains the test classes as well as all
    dependencies.

## Alternatives Tried

### Use Git Branches

One alternative is to rely on Git branches to set up the classes. For example,
the shell snippet shown earlier can be implemented as:

```shell
current_prod_schema=$(fetch_version_tag schema production)
current_prod_server=$(fetch_version_tag server production)
schema_changes=$(git diff ${current_prod_schema} --name-only \
  ./db/src/main/resources/sql/flyway/ | wc -l)

if [[ schema_changes -gt  0 ]]; then
  current_branch=$(git rev-parse --abbrev-ref HEAD)
  schema_folder=$(mktemp -d)
  ./gradlew :db:schemaJar && cp ./db/build/libs/schema.jar ${schema_folder}
  git checkout ${current_prod_server}
  ./gradlew sqlIntegrationTest \
  -Psql_schema_resource_root=${schema_folder}/schema.jar
  git checkout ${current_branch}
fi
```

The drawbacks of this approach include:

*   Switching branches back and forth is error-prone and risky, especially when
    we run this as a gating test during release.
*   Switching branches makes implicit assumptions on how the test platform would
    check out the repository (e.g., whether we may be on a headless branch when
    we switch).
*   The generated jar is not saved, making it harder to troubleshoot.
*   To use this locally during development, the Git tree must not have
    uncommitted changes.

### Smaller Jars

Another alternative follows the same idea as our current approach. However,
instead of including dependencies in a fat jar, it simply records their versions
in a file. At testing time these dependencies will be imported into the gradle
project file with forced resolution (e.g., testRuntime ('junit:junit:4.12)'
{forced = true} ). This way the published jars will be smaller.

This approach conflicts with our current dependency-locking processing. Due to
issues with the license-check plugin, dependency-locking is activated after all
projects are evaluated. This approach will resolve some configurations in :core
(and make them immutable) during evaluation, causing the lock-activation (which
counts as a mutation) call to fail.
