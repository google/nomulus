# Registry tool

The registry tool is a command-line registry administration tool that is invoked
using the `registry_tool` command.  It has the ability to view and change a
large number of things in a running domain registry environment, including
creating registrars, updating premium and reserved lists, running an EPP command
from a given XML file, and performing various backend tasks like re-running RDE
if the most recent export failed.  Its code lives inside the tools package
(`java/google/registry/tools`), and is compiled by building the `registry_tool`
target in the Bazel BUILD file in that package.

To build the tool and display its command-line help, execute this command:

    $ bazel run //java/google/registry/tool:registry_tool -- --help

For future invocations you should alias the compiled binary in the
`bazel-genfiles/java/google/registry` directory or add it to your path so that
you can run it more easily.  The rest of this guide assumes that it has been
aliased to `registry_tool`.

The registry tool is always called with a specific environment to run in using
the -e parameter.  This looks like:

    $ registry_tool -e production {command name} {command parameters}

To see a list of all available commands along with usage information, run
registry_tool without specifying a command name, e.g.:

    $ registry_tool -e alpha

Note that the documentation for the commands comes from JCommander, which parses
metadata contained within the code to yield documentation.

## Tech support commands

There are actually two separate tools, `gtech_tool`, which is a collection of
lower impact commands intended to be used by tech support personnel, and
`registry_tool`, which is a superset of `gtech_tool` that contains additional
commands that are potentially more destructive and can change more aspects of
the system.  A full list of `gtech_tool` commands can be found in
`GtechTool.java`, and the additional commands that only `registry_tool` has
access to are in `RegistryTool.java`.

## Local and server-side commands

There are two broad ways that commands are implemented: some that send requests
to `ToolsServlet` to execute the action on the server (these commands implement
`ServerSideCommand`), and others that execute the command locally using the
[Remote API](https://cloud.google.com/appengine/docs/java/tools/remoteapi)
(these commands implement `RemoteApiCommand`).  Server-side commands take more
work to implement because they require both a client and a server-side
component, e.g. `CreatePremiumListCommand.java` and
`CreatePremiumListAction.java` respectively for creating a premium list.
However, they are fully capable of doing anything that is possible with App
Engine, including running a large MapReduce, because they execute on the tools
service in the App Engine cloud.

Local commands, by contrast, are easier to implement, because there is only a
local component to write, but they aren't as powerful.  A general rule of thumb
for making this determination is to use a local command if possible, or a
server-side command otherwise.

## Common tool patterns

All tools ultimately implement the `Command` interface located in the `tools`
package.  If you use an IDE such as Eclipse to view the type hierarchy of that
interface, you'll see all of the commands that exist, as well as how a lot of
them are grouped using sub-interfaces or abstract classes that provide
additional functionality.  The most common patterns that are used by a large
number of other tools are:

* **`BigqueryCommand`** -- Provides a connection to BigQuery for tools that need
  it.
* **`ConfirmingCommand`** -- Provides the methods `prompt()` and `execute()` to
  override.  `prompt()` outputs a message (usually what the command is going to
  do) and prompts the user to confirm execution of the command, and then
  `execute()` actually does it.
* **`EppToolCommand`** -- Commands that work by executing EPP commands against
  the server, usually by filling in a template with parameters that were passed
  on the command-line.
* **`MutatingEppToolCommand`** -- A sub-class of `EppToolCommand` that provides
  a `--dry_run` flag, that, if passed, will display the output from the server
  of what the command would've done without actually committing those changes.
* **`GetEppResourceCommand`** -- Gets individual EPP resources from the server
  and outputs them.
* **`ListObjectsCommand`** -- Lists all objects of a specific type from the
  server and outputs them.
* **`MutatingCommand`** -- Provides a facility to create or update entities in
  Datastore, and uses a diff algorithm to display the changes that will be made
  before committing them.
