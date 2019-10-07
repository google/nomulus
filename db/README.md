## Summary

This project contains Nomulus's Cloud SQL schema and schema-deployment
utilities.

### Schema DDL Scripts

Currently we use Flyway for schema deployment. Versioned incremental update
scripts are organized in the src/main/resources/sql/flyway folder. A Flyway
'migration' task examines the target database instance, and makes sure that only
changes not yet deployed are pushed.

Below are the steps to submit a schema change:

1.  Write the incremental DDL script that makes your changes to the existing
    schema. It should be stored in a new file in the
    `db/src/main/resources/sql/flyway` folder using the naming pattern
    `V{id}__{description text}.sql`, where `{id}` is the next highest number
    following the existing scripts in that folder. Also note that it is a
    **double** underscore in the naming pattern.
2.  Run the `:db:test` task from the Gradle root project. The SchemaTest will
    fail because the new schema does not match the golden file.
3.  Copy db/build/resources/test/testcontainer/mount/dump.txt to the golden file
    (db/src/main/resources/sql/schema/nomulus.golden.sql). Diff it against the
    old version and verify that all changes are expected.
4.  Rerun the `:db:test` task. This time all tests should pass.

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
