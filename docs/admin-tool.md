# Admin tool

Nomulus includes a command-line registry administration tool that is invoked
using the `nomulus` command. It has the ability to view and change a large
number of things in a live Nomulus environment, including creating registrars,
updating premium and reserved lists, running an EPP command from a given XML
file, and performing various backend tasks like re-running RDE if the most
recent export failed. Its code lives inside the tools package
(`java/google/registry/tools`), and is compiled by building the `nomulus` target
in the Bazel BUILD file in that package.

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

To build the `nomulus` tool, execute the following `bazel build` command inside
any directory of the codebase. You must rebuild the tool any time that you edit
configuration or make database schema changes.

```shell
$ bazel build //java/google/registry/tools:nomulus
```

It's recommended that you alias the compiled binary located at
`bazel-genfiles/java/google/registry/nomulus` (or add it to your shell path) so
that you can run it easily. The rest of this guide assumes that it has been
aliased to `nomulus`.

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

There are two broad ways that commands are implemented: some that send requests
to `ToolsServlet` to execute the action on the server (these commands implement
`ServerSideCommand`), and others that execute the command locally using the
[Remote API](https://cloud.google.com/appengine/docs/java/tools/remoteapi)
(these commands implement `RemoteApiCommand`). Server-side commands take more
work to implement because they require both a client and a server-side
component.
However, they are fully capable of doing anything that is possible with App
Engine, including running a large MapReduce, because they execute on the tools
service in the App Engine cloud.

Local commands, by contrast, are easier to implement, because there is only a
local component to write, but they aren't as powerful. A general rule of thumb
for making this determination is to use a local command if possible, or a
server-side command otherwise.

## Common tool patterns

All tools ultimately implement the `Command` interface located in the `tools`
package. If you use an integrated development environment (IDE) such as IntelliJ
to view the type hierarchy of that interface, you'll see all of the commands
that exist, as well as how a lot of them are grouped using sub-interfaces or
abstract classes that provide additional functionality. The most common patterns
that are used by a large number of other tools are:

*   **`BigqueryCommand`** -- Provides a connection to BigQuery for tools that
    need it.
*   **`ConfirmingCommand`** -- Provides the methods `prompt()` and `execute()`
    to override. `prompt()` outputs a message (usually what the command is going
    to do) and prompts the user to confirm execution of the command, and then
    `execute()` actually does it.
*   **`EppToolCommand`** -- Commands that work by executing EPP commands against
    the server, usually by filling in a template with parameters that were
    passed on the command-line.
*   **`MutatingEppToolCommand`** -- A sub-class of `EppToolCommand` that
    provides a `--dry_run` flag, that, if passed, will display the output from
    the server of what the command would've done without actually committing
    those changes.
*   **`GetEppResourceCommand`** -- Gets individual EPP resources from the server
    and outputs them.
*   **`ListObjectsCommand`** -- Lists all objects of a specific type from the
    server and outputs them.
*   **`MutatingCommand`** -- Provides a facility to create or update entities in
    Datastore, and uses a diff algorithm to display the changes that will be
    made before committing them.
