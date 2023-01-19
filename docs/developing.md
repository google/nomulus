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