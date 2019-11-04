## Summary

This project contains Nomulus's Cloud SQL schema and schema-deployment
utilities.

### Database Roles and Privileges

Nomulus uses the 'postgres' database in the 'public' schema. The following
users/roles are defined:

*   postgres: the initial user is used for admin and schema deployment.
    *  In Cloud SQL, we do not control superusers. The initial 'postgres' user
       is a regular user with create-role/create-db privileges. Therefore,
       it is not possible to separate admin user and schema-deployment user.
*   readwrite is a role with read-write privileges on all data tables and
    sequences. However, it does not have write access to admin tables. Nor
    can it create new tables.
    *   The Registry server user is granted this role.
*   readonly is a role with SELECT privileges on all tables.
    *   Reporting job user and individual human readers may be granted
        this role.

### Schema DDL Scripts

Currently we use Flyway for schema deployment. Versioned incremental update
scripts are organized in the src/main/resources/sql/flyway folder. A Flyway
'migration' task examines the target database instance, and makes sure that only
changes not yet deployed are pushed.

Below are the steps to submit a schema change:

1.  Make your changes to entity classes, remembering to add new ones to
    `core/src/main/resources/META-INF/persistence.xml` so they'll be picked up.
2.  Run the `nomulus generate_sql_schema` command to generate a new version of
    `db-schema.sql.generated`. The full command line to do this is:
    
    `./gradlew registryTool --args="-e localhost generate_sql_schema --start_postgresql -o /path/to/nomulus/db/src/main/resources/sql/schema/db-schema.sql.generated"`
3.  Write an incremental DDL script that changes the existing schema to your
    new one. The generated SQL file from the previous step should help. New 
    create table statements can be used as is, whereas alter table statements
    should be written to change any existing tables.
    
    This script should be stored in a new file in the
    `db/src/main/resources/sql/flyway` folder using the naming pattern
    `V{id}__{description text}.sql`, where `{id}` is the next highest number
    following the existing scripts in that folder. Note the double underscore in
    the naming pattern.
4.  Run the `:db:test` task from the Gradle root project. The SchemaTest will
    fail because the new schema does not match the golden file.
5.  Copy `db/build/resources/test/testcontainer/mount/dump.txt` to the golden file
    `db/src/main/resources/sql/schema/nomulus.golden.sql`. Diff it against the
    old version and verify that all changes are expected.
6.  Re-run the `:db:test` task. This time all tests should pass.

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

### Non-production Schema Push

To manage schema in a non-production environment, use the 'flywayMigration'
task. You will need Cloud SDK and login once.

```shell
# One time login
gcloud auth login

# Deploy the current schema to alpha
gradlew :db:flywayMigrate -PdbServer=alpha

# Delete the entire schema in alpha
gradlew :db:flywayClean -PdbServer=alpha
```

The flywayMigrate task is idempotent. Repeated runs will not introduce problems.

The Flyway tasks may also be used to deploy to local instances, e.g, your own
test instance. E.g.,

```shell
# Deploy to a local instance at standard port as the super user.
gradlew :db:flywayMigrate -PdbServer=192.168.9.2 -PdbPassword=domain-registry

# Full specification of all parameters
gradlew :db:flywayMigrate -PdbServer=192.168.9.2:5432 -PdbUser=postgres \
    -PdbPassword=domain-registry
```

### Production Schema Deployment

Schema deployment to production and sandbox is under development.
