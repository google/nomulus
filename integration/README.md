## Summary

This project runs cross-version server/schema integration tests with arbitrary
version pairs. It may be used by presubmit tests and continuous-integration
tests, or as a gating test during release and/or deployment.

## Maven Dependencies

This release process is expected to publish the following Maven dependencies to
a well-known repository:

*   google.registry:schema, which contains the schema DDL scripts. This is done
    by the ':db:publish' task.
*   google.registry:nomulus_test, which contains the nomulus classes and
    dependencies needed for the integration tests. This is done by the
    ':core:publish' task.

After each deployment in sandbox or production, the deployment process is
expected to save the version tag of the binary or schema along with the
environment. These tags will be made available to test runners.

## Usage

The ':integration:sqlIntegrationTest' task is the test runner. It uses the
following properties:

*   nomulus_version: a Registry server release tag, or 'local' if the code in
    the local Git tree should be used.
*   schema_version: a schema release tag, or 'local' if the code in the local
    Git tree should be used.
*   publish_repo: the Maven repository where release jars may be found. This is
    required if neither of the above is 'local'.

Given a program 'fetch_version_tag' that retrieves the currently deployed
version tag of SQL schema or server binary in a particular environment (which as
mentioned earlier are saved by the deployment process), the following code
snippet checks if the current PR or local clone has schema changes, and if yes,
tests the production server's version with the new schema.

```shell
current_prod_schema=$(fetch_version_tag schema production)
current_prod_server=$(fetch_version_tag server production)
schema_changes=$(git diff ${current_prod_schema} --name-only \
  ./db/src/main/resources/sql/flyway/ | wc -l)
[[ schema_changes -gt  0 ]] && ./gradlew :integration:sqlIntegrationTest \
  -Ppublish_repo=${REPO} -Pschema_version=local \
  -Pnomulus_version=current_prod_server
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
