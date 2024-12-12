// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.tools.Injector.injectReflectively;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.tools.AuthModule.LoginRequiredException;
import google.registry.tools.params.ParameterFactory;
import google.registry.util.RegistryEnvironment;
import java.security.Security;
import java.util.Map;
import java.util.Optional;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.postgresql.util.PSQLException;

/** Container class to create and run remote commands against a server instance. */
@Parameters(separators = " =", commandDescription = "Command-line interface to the registry")
final class RegistryCli implements CommandRunner {

  private static final ImmutableSet<RegistryToolEnvironment> DEFAULT_GKE_ENVIRONMENTS =
      ImmutableSet.of(RegistryToolEnvironment.ALPHA, RegistryToolEnvironment.QA);

  // The environment parameter is parsed twice: once here, and once with {@link
  // RegistryToolEnvironment#parseFromArgs} in the {@link RegistryTool#main} function.
  //
  // The flag names must be in sync between the two, and also - this is ugly, and we should feel
  // bad.
  @Parameter(
      names = {"-e", "--environment"},
      description = "Sets the default environment to run the command.")
  private RegistryToolEnvironment environment = RegistryToolEnvironment.PRODUCTION;

  @Parameter(
      names = {"-c", "--commands"},
      description = "Returns all command names.")
  private boolean showAllCommands;

  @Parameter(
      names = "--credential",
      description =
          "Name of a JSON file containing credential information used by the tool. "
              + "If not set, credentials saved by running `nomulus login' will be used.")
  private String credentialJson = null;

  @Parameter(
      names = "--sql_access_info",
      description =
          "Name of a file containing space-separated SQL access info used when deploying "
              + "Beam pipelines")
  private String sqlAccessInfoFile = null;

  @Parameter(names = "--gke", description = "Whether to use GKE runtime, instead of GAE")
  private boolean useGke = false;

  @Parameter(names = "--gae", description = "Whether to use GAE runtime, instead of GKE")
  private boolean useGae = false;

  @Parameter(names = "--canary", description = "Whether to connect to the canary instances")
  private boolean useCanary = false;

  // Do not make this final - compile-time constant inlining may interfere with JCommander.
  @ParametersDelegate private LoggingParameters loggingParams = new LoggingParameters();

  RegistryToolComponent component;

  // This is created lazily on first use.
  private ServiceConnection connection;

  // The "shell" command should only exist on first use - so that we can't run "shell" inside
  // "shell".
  private boolean isFirstUse = true;

  Map<String, ? extends Class<? extends Command>> commands;
  String programName;

  RegistryCli(
      String programName, ImmutableMap<String, ? extends Class<? extends Command>> commands) {
    this.programName = programName;
    this.commands = commands;

    Security.addProvider(new BouncyCastleProvider());
  }

  // The <? extends Class<? extends Command>> wildcard looks a little funny, but is necessary so
  // that we can accept maps with value types that are subtypes of Class<? extends Command> rather
  // than literally that type.  For more explanation, see:
  // http://www.angelikalanger.com/GenericsFAQ/FAQSections/TypeArguments.html#FAQ104
  @Override
  public void run(String[] args) throws Exception {

    // Create the JCommander instance.
    JCommander jcommander = new JCommander(this);
    jcommander.addConverterFactory(new ParameterFactory());
    jcommander.setProgramName(programName);

    // Create all command instances. It would be preferable to do this in the constructor, but
    // JCommander mutates the command instances and doesn't reset them, so we have to do it for
    // every run.
    try {
      for (Map.Entry<String, ? extends Class<? extends Command>> entry : commands.entrySet()) {
        Command command = entry.getValue().getDeclaredConstructor().newInstance();
        jcommander.addCommand(entry.getKey(), command);
      }
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }

    // Create the "help" and "shell" commands (these are special in that they don't have a default
    // constructor).
    jcommander.addCommand("help", new HelpCommand(jcommander));
    if (isFirstUse) {
      isFirstUse = false;
      ShellCommand shellCommand = new ShellCommand(this);
      // We have to build the completions based on the jcommander *before* we add the "shell"
      // command - to avoid completion for the "shell" command itself.
      shellCommand.buildCompletions(jcommander);
      jcommander.addCommand("shell", shellCommand);
    }

    try {
      jcommander.parse(args);
    } catch (ParameterException e) {
      // If we failed to fully parse the command but at least found a valid command name, show only
      // the usage for that command.
      if (jcommander.getParsedCommand() != null) {
        jcommander.usage(jcommander.getParsedCommand());
      }
      // Don't rethrow if we said: nomulus command --help
      if ("Unknown option: --help".equals(e.getMessage())) {
        return;
      }
      throw e;
    }

    checkState(!useGke || !useGae, "Cannot specify both --gke and --gae");
    // Special logic to set the default based on the environment if neither --gae nor --gke is set.
    if (!useGke && !useGae) {
      useGke = DEFAULT_GKE_ENVIRONMENTS.contains(environment);
    }

    String parsedCommand = jcommander.getParsedCommand();
    // Show the list of all commands either if requested or if no subcommand name was specified
    // (which does not throw a ParameterException parse error above).
    if (showAllCommands || parsedCommand == null) {
      if (parsedCommand == null) {
        System.out.println("The list of available subcommands is:");
      }
      commands.keySet().forEach(System.out::println);
      return;
    }

    checkState(
        RegistryToolEnvironment.get() == environment,
        "RegistryToolEnvironment argument pre-processing kludge failed.");

    component =
        DaggerRegistryToolComponent.builder()
            .credentialFilePath(credentialJson)
            .sqlAccessInfoFile(sqlAccessInfoFile)
            .useGke(useGke)
            .useCanary(useCanary)
            .build();

    // JCommander stores sub-commands as nested JCommander objects containing a list of user objects
    // to be populated.  Extract the subcommand by getting the JCommander wrapper and then
    // retrieving the first (and, by virtue of our usage, only) object from it.
    Command command =
        (Command)
            Iterables.getOnlyElement(jcommander.getCommands().get(parsedCommand).getObjects());
    loggingParams.configureLogging(); // Must be called after parameters are parsed.

    try {
      runCommand(command);
    } catch (RuntimeException e) {
      if (Throwables.getRootCause(e) instanceof LoginRequiredException) {
        System.err.println("===================================================================");
        System.err.println("You must login using 'nomulus login' prior to running this command.");
        System.err.println("===================================================================");
        System.exit(1);
      } else {
        // See if this looks like the error we get when there's another instance of nomulus tool
        // running against SQL and give the user some additional guidance if so.
        Optional<Throwable> psqlException =
            Throwables.getCausalChain(e).stream()
                .filter(x -> x instanceof PSQLException)
                .findFirst();
        if (psqlException.isPresent() && psqlException.get().getMessage().contains("google:5432")) {
          e.printStackTrace();
          System.err.println("===================================================================");
          System.err.println(
              """
              This error is likely the result of having another instance of
              nomulus running at the same time.  Check your system, shut down
              the other instance, and try again.""");
          System.err.println("===================================================================");
        } else {
          throw e;
        }
      }
    }
  }

  private ServiceConnection getConnection() {
    // Get the App Engine connection, advise the user if they are not currently logged in.
    if (connection == null) {
      connection = component.serviceConnection();
    }
    return connection;
  }

  private void runCommand(Command command) throws Exception {
    injectReflectively(RegistryToolComponent.class, component, command);

    if (command instanceof CommandWithConnection) {
      ((CommandWithConnection) command).setConnection(getConnection());
    }

    // Reset the JPA transaction manager after every command to avoid a situation where a test can
    // interfere with other tests
    final JpaTransactionManager cachedJpaTm;
    if (RegistryEnvironment.get() == RegistryEnvironment.UNITTEST) {
      cachedJpaTm = tm();
    } else {
      cachedJpaTm = null;
    }
    TransactionManagerFactory.setJpaTm(() -> component.nomulusToolJpaTransactionManager().get());
    TransactionManagerFactory.setReplicaJpaTm(
        () -> component.nomulusToolReplicaJpaTransactionManager().get());
    command.run();
    if (RegistryEnvironment.get() == RegistryEnvironment.UNITTEST) {
      TransactionManagerFactory.setJpaTm(() -> cachedJpaTm);
    }
  }

  void setEnvironment(RegistryToolEnvironment environment) {
    this.environment = environment;
  }
}
