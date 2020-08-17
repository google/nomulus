## Summary

This project contains Nomulus's Cloud SQL schema and schema-deployment
utilities.

### Database Roles and Privileges

Nomulus uses the 'postgres' database in the 'public' schema. The following
users/roles are defined:

*   postgres: the initial user is used for admin and schema deployment.
    *   In Cloud SQL, we do not control superusers. The initial 'postgres' user
        is a regular user with create-role/create-db privileges. Therefore, it
        is not possible to separate admin user and schema-deployment user.
*   readwrite is a role with read-write privileges on all data tables and
    sequences. However, it does not have write access to admin tables. Nor can
    it create new tables.
    *   The Registry server user is granted this role.
*   readonly is a role with SELECT privileges on all tables.
    *   Reporting job user and individual human readers may be granted this
        role.

### Schema DDL Scripts

Currently we use Flyway for schema deployment. Versioned incremental update
scripts are organized in the src/main/resources/sql/flyway folder. A Flyway
'migration' task examines the target database instance, and makes sure that only
changes not yet deployed are pushed.

Below are the steps to submit a schema change:

1.  Make your changes to entity classes, remembering to add new ones to
    `core/src/main/resources/META-INF/persistence.xml` so they'll be picked up.
2.  Run the `devTool generate_sql_schema` command to generate a new version of
    `db-schema.sql.generated`. The full command line to do this is:

    `./nom_build generateSqlSchema`

3.  Write an incremental DDL script that changes the existing schema to your new
    one. The generated SQL file from the previous step should help. New create
    table statements can be used as is, whereas alter table statements should be
    written to change any existing tables.

    This script should be stored in a new file in the
    `db/src/main/resources/sql/flyway` folder using the naming pattern
    `V{id}__{description text}.sql`, where `{id}` is the next highest number
    following the existing scripts in that folder. Note the double underscore in
    the naming pattern.

4.  Run `./nom_build :nom:generate_golden_file`.  This is a pseudo-task
    implemented in the `nom_build` script that does the following:
    -   Runs the `:db:test` task from the Gradle root project. The SchemaTest
        will fail because the new schema does not match the golden file.

    -   Copies `db/build/resources/test/testcontainer/mount/dump.txt` to the golden
        file `db/src/main/resources/sql/schema/nomulus.golden.sql`.

    -   Re-runs the `:db:test` task. This time all tests should pass.

    You'll want to have a look at the diffs in the golden schema to verify
    that all changes are intentional.

Relevant files (under db/src/main/resources/sql/schema/):

*   nomulus.golden.sql is the schema dump (pg_dump for postgres) of the final
    schema pushed by Flyway. This is mostly for informational, although it may
    be used in tests.
*   db-schema.sql.generated is the schema generated from ORM classes by the
    GenerateSqlSchema command in Nomulus tool. This reflects the ORM-layer's
    view of the schema.

The generated schema and the golden one may diverge during schema changes. For
example, when adding a new column to a table, we would deploy the change before
adding it to the relevant ORM class. Therefore, for a short time the golden file
will contain the new column while the generated one does not.

### Schema Push

Currently Cloud SQL schema is released with the Nomulus server, and shares the
server release's tag (e.g., nomulus-20191101-RC00). Automatic schema push
process (to apply new changes in a released schema to the databases) has not
been set up yet, and new schema may be pushed manually on demand.

Presubmit and continuous-integration tests are being implemented to ensure
server/schema compatibility. Before the tests are activated, please look for
breaking changes before deploying a schema.

Released schema may be deployed using Cloud Build. Use the root project
directory as working directory, run the following shell snippets:

```shell
# Tags exist as folder names under gs://domain-registry-dev-deploy.
SCHEMA_TAG=
# Recognized environments are alpha, crash, sandbox and production
SQL_ENV=
# Deploy on cloud build. The --project is optional if domain-registry-dev
# is already your default project.
gcloud builds submit --config=release/cloudbuild-schema-deploy.yaml \
    --substitutions=TAG_NAME=${SCHEMA_TAG},_ENV=${SQL_ENV} \
    --project domain-registry-dev
# Verify by checking Flyway Schema History:
./nom_build :db:flywayInfo --dbServer=${SQL_ENV}
```

#### Glass Breaking

If you need to deploy a schema off-cycle, try making a release first, then
deploy that release schema to Cloud SQL.

TODO(weiminyu): elaborate on different ways to push schema without a full
release.

#### Notes On Flyway

Please note: to run Flyway commands, you need Cloud SDK and need to log in once.

```shell
# One time login
gcloud auth login
```

The Flyway-based Cloud Build schema push process is safe in common scenarios:

*   Repeatedly deploying the latest schema is safe. All duplicate runs become
    NOP.

*   Accidentally deploying a past schema is safe. Flyway will not undo
    incremental changes not reflected in the deployed schema.

*   Concurrent deployment runs are safe. Flyway locks its own metadata table,
    serializing deployment runs without affecting normal accesses.

#### Schema Push to Local Database

The Flyway tasks may also be used to deploy to local instances, e.g, your own
test instance. E.g.,

```shell
# Deploy to a local instance at standard port as the super user.
./nom_build :db:flywayMigrate --dbServer=192.168.9.2 --dbPassword=domain-registry

# Full specification of all parameters
./nom_build :db:flywayMigrate --dbServer=192.168.9.2:5432 --dbUser=postgres \
    --dbPassword=domain-registry
```
