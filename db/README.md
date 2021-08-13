## Summary

This project contains Nomulus's Cloud SQL schema and schema-deployment
utilities.

### Entity Relationship (ER) diagrams

The following links are the ER diagrams generated from the current SQL schema:

* [Full ER diagram](https://storage.googleapis.com/domain-registry-dev-er-diagram/full_er_diagram.html): 
shows all columns, foreign keys and indexes.

* [Brief ER diagram](https://storage.googleapis.com/domain-registry-dev-er-diagram/brief_er_diagram.html): 
shows only significant columns, such as primary and foreign key columns, and 
columns that are part of unique indexes.

### Database roles and privileges

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

### How to update the schema

Currently we use Flyway for schema deployment. Versioned incremental update
scripts are organized in the src/main/resources/sql/flyway folder. A Flyway
'migration' task examines the target database instance, and makes sure that only
changes not yet deployed are pushed.

Because we have SQL integration tests enabled to ensure that deployments are
rollback-safe, which prevent Java code from executing against a version of the
schema it is incompatible with, you will need to commit your schema additions in
two separate PRs, with a wait for a deployment in-between, as explained in the
following steps:

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

4.  Run `./nom_build :db:generateFlywayIndex` to regenerate the Flyway index.
    This is a file listing all of the current Flyway files.  Its purpose is to
    produce a merge conflict when more than one person adds a Flyway file with
    the same sequence number.

5.  Run `./nom_build :nom:generate_golden_file`. This is a pseudo-task
    implemented in the `nom_build` script that does the following:

    -   Runs the `:db:test` task from the Gradle root project. The SchemaTest
        will fail because the new schema does not match the golden file.

    -   Copies `db/build/resources/test/testcontainer/mount/dump.txt` to the
        golden file `db/src/main/resources/sql/schema/nomulus.golden.sql`.

    -   Re-runs the `:db:test` task. This time all tests should pass.

    You'll want to have a look at the diffs in the golden schema to verify that
    all changes are intentional.
6.  Now, split your outstanding changes into two PRs. The first PR should _only_
    include your new Flyway version `.sql` file, its addition to the `flyway.txt`
    index, changes to the `nomulus.golden.sql` schema file, and changes to the
    Entity Relationship diagram `.html` files. The second PR should include
    everything else, including _all_ changes to `.java` files and the
    `db-schema.sql.generated` changes that derive from them.
7.  Submit the first PR and wait until it is successfully deployed to production,
    then submit the second PR. Note, if you are removing things from the schema
    (rather than adding them), then these PRs should be in the opposite order:
    Java changes first, then SQL changes afterwards.

Relevant files (under `db/src/main/resources/sql/schema/`):

*   `nomulus.golden.sql` is the schema dump (pg_dump for postgres) of the final
    schema pushed by Flyway. This is mostly for informational, although it may
    be used in tests.
*   `db-schema.sql.generated` is the schema generated from ORM classes by the
    GenerateSqlSchema command in Nomulus tool. This reflects the ORM-layer's
    view of the schema.

The generated schema and the golden one may diverge during schema changes. For
example, when adding a new column to a table, we would deploy the change before
adding it to the relevant ORM class. Therefore, for a short time the golden file
will contain the new column while the generated one does not.

Note that, when making schema changes, you _cannot_ add a new `NOT NULL` column
to an existing table that does not have a default value, or make any other
similar addition of a constraint that will be violated by existing data. If you
wish to rename a column, you must first add a new column with the desired name,
copy over its contents using a `@PostLoad` action in Java, re-save all rows,
update the Java to no longer contain the old column, wait for a deployment, and
then remove the old column. A rename operation requires the most complicated
series of steps to complete, as it is effectively an add followed by a remove.

### Schema push

Currently Cloud SQL schema is released with the Nomulus server, and shares the
server release's tag (e.g., `nomulus-20191101-RC00`). Automatic schema push
process (to apply new changes in a released schema to the databases) has been
set up as part of the overall release pipeline.

Presubmit and continuous-integration tests are being implemented to ensure
server/schema compatibility. Before the tests are activated, please look for
breaking changes before deploying a schema.

Released schema may be manually deployed using Cloud Build. Use the root
project directory as working directory, run the following shell snippets:

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

To test unsubmitted schema changes in the alpha or crash environments, use the
following command to deploy the local schema,

```shell
./nom_build :db:flywayMigrate --dbServer=[alpha|crash] --environment=[alpha|crash]
```

#### Glass breaking

If you need to deploy a schema off-cycle, try making a release first, then
deploy that release schema to Cloud SQL.

TODO(weiminyu): elaborate on different ways to push schema without a full
release.

#### Notes on Flyway

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

#### Schema push to local database

The Flyway tasks may also be used to deploy to local instances, e.g, your own
test instance. E.g.,

```shell
# Deploy to a local instance at standard port as the super user.
./nom_build :db:flywayMigrate --dbServer=192.168.9.2 --dbPassword=domain-registry

# Full specification of all parameters
./nom_build :db:flywayMigrate --dbServer=192.168.9.2:5432 --dbUser=postgres \
    --dbPassword=domain-registry
```
