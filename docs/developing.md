# Developing

This document contains advice on how to do development on the Nomulus codebase,
including how to set up an IDE environment and run tests.

## Running a local development server

`RegistryTestServer` is a lightweight test server for the registry that is
suitable for running locally for development. It uses local versions of all
Google Cloud Platform dependencies, when available. Correspondingly, its
functionality is limited compared to a Nomulus instance running on an actual App
Engine instance. It is most helpful for doing web UI development such as on the
registrar console: it allows you to update JS, CSS, images, and other front-end
resources, and see the changes instantly simply by refreshing the relevant page
in your browser.

To see the registry server's command-line parameters, run:

```shell
$ bazel run //javatests/google/registry/server -- --help
```

To start an instance of the server, run:

```shell
$ bazel run //javatests/google/registry/server {your params}
```

Once it is running, you can interact with it via normal `nomulus` commands, or
view the registrar console in a web browser by navigating to
[http://localhost:8080/registrar](http://localhost:8080/registrar). The server
will continue running until you terminate the process.

If you are adding new URL paths, or new directories of web-accessible resources,
you will need to make the corresponding changes in `RegistryTestServer`. This
class contains all of the routing and static file information used by the local
development server.

## Performing Datastore schema migrations

At some point in the development of a large product it will most likely become
necessary to perform a schema migration on data persisted to the database.
Because Datastore is a schema-less database, adding new fields is as simple as
writing to them, but changing the names or data formats of existing fields
requires more care, especially on a running production environment. The
Objectify documentation has a good [introductory
guide](https://github.com/objectify/objectify/wiki/SchemaMigration) to schema
migrations that is worth reading. Beyond that, you may want to use some of the
following techniques.

The requirements for a good schema migration are as follows:

*   There must be no down-time or interruption of service.
*   The upgrade must be rollback-safe, i.e. the new version of the app can be
    reverted and everything will continue working.

In order to meet these requirements, a multiple-phase roll-out strategy is used
as follows:

1.  Dual-write, reading from old field. Add the new Datastore fields along with
    any required Datastore indexes. Use an `@OnSave` method on the entity to
    copy over the contents of the old field to the new field every time the
    entity is saved.
2.  Deploy the new version of the app.
3.  Re-save all affected entities in Datastore. For `EppResources` this can be
    accomplished by running
    [`ResaveAllEppResourcesAction`](https://github.com/google/nomulus/blob/master/java/google/registry/tools/server/ResaveAllEppResourcesAction.java);
    for other entities you may need to write something custom. Re-saving all
    entities forces the `@OnSave` method to fire for every entity, copying over
    the contents of the old fields to the new fields. Any additional entities
    that are created after the mapreduce is run will have the right values for
    the new field because the `@OnSave` method is writing them.
4.  Dual-write, now reading from new field. Switch over all places in the code
    that are using the data to read from the new fields rather than from the old
    fields. Adjust the `@OnSave` method so that it is copying over the contents
    from the new field to the old field. Dual-writing to the old fields ensures
    that it is safe to roll back to the prior version if necessary, since the
    data it is expecting will still be there.
5.  Deploy the new version of the app.
6.  Delete the old fields, their indexes, and the `@OnSave` method.
7.  Deploy the new version of the app. The schema migration is now complete.

The migration away from using a wrapper class around Keys on `Domain`
objects is instructive as an example:

*   [Step
    1](https://github.com/google/nomulus/commit/861fd60d2cb408ba2f9570d3881d316475b728c2),
    which implements dual-writing.
*   [Step
    4](https://github.com/google/nomulus/commit/361a53a3c985c14539e5ec1a31cf4ad192f67a5d),
    which switches over to using the new fields.
*   [Step
    6](https://github.com/google/nomulus/commit/780a5add78e735589f25f736059c29e9faf9aef5),
    which removes the old fields.
