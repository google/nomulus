# Admin tool

Nomulus includes a command-line registry administration tool. It has the ability
to view and change a large number of things in a live Nomulus environment,
including creating registrars, running arbitrary EPP commands from given XML
files, and performing various backend tasks like re-running RDE if the most
recent export failed. Its code lives inside the tools package
(`core/src/main/java/google/registry/tools`), and is compiled by building the
`nomulus` Gradle target in the `core` project, e.g. `./gradlew core:nomulus`.

The tool connects to the Google Cloud Platform project (identified by project
ID) that was configured in your implementation of `RegistryConfig` when the tool
was built. See the [configuration guide](./configuration.md) for more
information. The tool can switch between project IDs that represent different
environments within a single overall platform (i.e. the production environment
plus development and testing environments); see the `-e` parameter below. For
example, if the platform is called "acme-registry", then the production project
ID is also "acme-registry", and the project ID for the sandbox environment is
"acme-registry-sandbox".

## Build the tool

To build the `nomulus` tool's jarfile, execute the following Gradle command
inside the project's home directory: `./gradlew core:nomulus`. You must rebuild
the tool any time that you edit configuration or make database schema changes.
Note that proper project configuration is necessary for building the tool --
this includes the specialized configuration such as GCP project names.

It's recommended that you alias the compiled jarfile located at
`core/build/libs/nomulus.jar` (or add it to your shell path) so that you can run
it easily, e.g.

```shell
$ alias nomulus="java -jar core/build/libs/nomulus.jar"
```

The rest of this guide assumes that it has been aliased to `nomulus`.

Note: for Google Registry employees, the nomulus tool is built as part of the
weekly deployment process and the nomulus jarfile is located at
`/google/data/ro/teams/domain-registry/tools/live/nomulus.jar`

## Running the tool

The registry tool is always called with a specific environment to run in using
the -e parameter. This looks like:

```shell
$ nomulus -e production {command name} {command parameters}
```

You can get help about the tool in general, or about a specific subcommand, as
follows:

```shell
$ nomulus -e alpha --help # Lists all subcommands
$ nomulus -e alpha SUBCOMMAND --help # Help for a specific subcommand
```

Note that the documentation for the commands comes from JCommander, which parses
metadata contained within the code to yield documentation.

## Local and server-side commands

There are two broad ways that commands are implemented: some send requests to
the backend server to execute the action on the server (these commands implement
`CommandWithConnection`), and others that execute the command locally using
access to the database. Commands that send requests to the backend server are
more work to implement because they require both a client-side and server-side
component, but they are more powerful -- even running Flow pipelines or other
long-running intensive jobs.

Local commands are easier to implement (because there is only a local component
to write) but they aren't as powerful. As a rule of thumb, use a local command
if possible.

## Common tool patterns

All tools ultimately implement the `Command` interface located in the `tools`
package. If you use an integrated development environment (IDE) such as IntelliJ
to view the type hierarchy of that interface, you'll see all the commands that
exist, as well as how a lot of them are grouped using sub-interfaces or abstract
classes that provide additional functionality. The most common patterns that are
used by a large number of other tools are:

*   **`ConfirmingCommand`** -- Provides the methods `prompt()` and `execute()`
    to override. `prompt()` outputs a message (usually what the command is going
    to do) and prompts the user to confirm execution of the command, and then
    `execute()` actually does it.
*   **`EppToolCommand`** -- Commands that work by executing EPP commands against
    the server, usually by filling in a template with parameters that were
    passed on the command-line.
*   **`MutatingEppToolCommand`** -- A subclass of `EppToolCommand` that provides
    a `--dry_run` flag, that, if passed, will display the output from the server
    of what the command would've done without actually committing those changes.
*   **`GetEppResourceCommand`** -- Gets individual EPP resources from the server
    and outputs them.
*   **`ListObjectsCommand`** -- Lists all objects of a specific type from the
    server and outputs them.
*   **`MutatingCommand`** -- Provides a facility to create or update entities in
    the database, and uses a diff algorithm to display the changes that will be
    made before committing them.
