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

package google.registry.server;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import google.registry.testing.AppEngineRule;
import google.registry.testing.UserInfo;
import google.registry.tools.params.HostAndPortParameter;
import google.registry.ui.ConsoleDebug;
import java.util.List;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/** Command-line interface for {@link RegistryTestServer}. */
@Parameters(separators = " =", commandDescription = "Runs web development server.")
public final class RegistryTestServerMain {

  private static final String RESET = "\u001b[0m";
  private static final String BLUE = "\u001b[1;34m";
  private static final String PURPLE = "\u001b[1;35m";
  private static final String PINK = "\u001b[1;38;5;205m";
  private static final String LIGHT_PURPLE = "\u001b[38;5;139m";
  private static final String ORANGE = "\u001b[1;38;5;172m";

  @Parameter(
      names = "--mode",
      description = "UI console debug mode. RAW allows live editing; DEBUG allows rename testing.")
  private ConsoleDebug mode = ConsoleDebug.PRODUCTION;

  @Parameter(
      names = "--address",
      description = "Listening address.",
      validateWith = HostAndPortParameter.class)
  private HostAndPort address = HostAndPort.fromString("[::]:8080");

  @Parameter(
      names = "--fixtures",
      description = "Fixtures to load into Datastore.")
  private List<Fixture> fixtures = ImmutableList.of(Fixture.BASIC);

  @Parameter(
      names = "--login_email",
      description = "Login email address for App Engine Local User Service.")
  private String loginEmail = "Marla.Singer@crr.com";

  @Parameter(
      names = "--login_user_id",
      description = "GAE User ID for App Engine Local User Service.")
  private String loginUserId = AppEngineRule.MARLA_SINGER_GAE_USER_ID;

  @Parameter(
      names = "--login_is_admin",
      description = "Should logged in user be an admin for App Engine Local User Service.",
      arity = 1)
  private boolean loginIsAdmin = true;

  @Parameter(
      names = "--jetty_debug",
      description = "Enables Jetty debug logging.")
  private boolean jettyDebug;

  @Parameter(
      names = "--jetty_verbose",
      description = "Enables Jetty verbose logging.")
  private boolean jettyVerbose;

  @Parameter(
      names = {"-h", "--help"},
      description = "Display help and list flags for this command.",
      help = true)
  private boolean help;

  public static void main(String[] args) throws Throwable {
    RegistryTestServerMain serverMain = new RegistryTestServerMain();
    JCommander jCommander = new JCommander(serverMain);
    jCommander.setProgramName("dr-run server");
    jCommander.parse(args);
    if (serverMain.help) {
      jCommander.usage();
      return;
    }
    serverMain.run();
  }

  private void run() throws Throwable {
    ConsoleDebug.set(mode);
    if (jettyDebug) {
      System.setProperty("DEBUG", "true");
    }
    if (jettyVerbose) {
      System.setProperty("VERBOSE", "true");
    }

    System.out.printf("\n"
        + "        CHARLESTON ROAD REGISTRY SHARED REGISTRATION SYSTEM\n"
        + "                      ICANN-GTLD-AGB-20120604\n\n%s"
        + "        ▓█████▄  ▒█████   ███▄ ▄███▓ ▄▄▄       ██▓ ███▄    █\n"
        + "        ▒██▀ ██▌▒██▒  ██▒▓██▒▀█▀ ██▒▒████▄    ▓██▒ ██ ▀█   █\n"
        + "        ░██   █▌▒██░  ██▒▓██    ▓██░▒██  ▀█▄  ▒██▒▓██  ▀█ ██▒\n"
        + "        ░▓█▄   ▌▒██   ██░▒██    ▒██ ░██▄▄▄▄██ ░██░▓██▒  ▐▌██▒\n"
        + "        ░▒████▓ ░ ████▓▒░▒██▒   ░██▒ ▓█   ▓██▒░██░▒██░   ▓██░\n"
        + "         ▒▒▓  ▒ ░ ▒░▒░▒░ ░ ▒░   ░  ░ ▒▒   ▓▒█░░▓  ░ ▒░   ▒ ▒\n"
        + "         ░ ▒  ▒   ░ ▒ ▒░ ░  ░      ░  ▒   ▒▒ ░ ▒ ░░ ░░   ░ ▒░\n"
        + "         ░ ░  ░ ░ ░ ░ ▒  ░      ░     ░   ▒    ▒ ░   ░   ░ ░\n"
        + "           ░        ░ ░         ░         ░  ░ ░           ░\n"
        + "         ░\n%s"
        + "    ██▀███  ▓█████   ▄████  ██▓  ██████ ▄▄▄█████▓ ██▀███ ▓██   ██▓\n"
        + "    ▓██ ▒ ██▒▓█   ▀  ██▒ ▀█▒▓██▒▒██    ▒ ▓  ██▒ ▓▒▓██ ▒ ██▒▒██  ██▒\n"
        + "    ▓██ ░▄█ ▒▒███   ▒██░▄▄▄░▒██▒░ ▓██▄   ▒ ▓██░ ▒░▓██ ░▄█ ▒ ▒██ ██░\n"
        + "    ▒██▀▀█▄  ▒▓█  ▄ ░▓█  ██▓░██░  ▒   ██▒░ ▓██▓ ░ ▒██▀▀█▄   ░ ▐██▓░\n"
        + "    ░██▓ ▒██▒░▒████▒░▒▓███▀▒░██░▒██████▒▒  ▒██▒ ░ ░██▓ ▒██▒ ░ ██▒▓░\n"
        + "    ░ ▒▓ ░▒▓░░░ ▒░ ░ ░▒   ▒ ░▓  ▒ ▒▓▒ ▒ ░  ▒ ░░   ░ ▒▓ ░▒▓░  ██▒▒▒\n"
        + "    ░▒ ░ ▒░ ░ ░  ░  ░   ░  ▒ ░░ ░▒  ░ ░    ░      ░▒ ░ ▒░▓██ ░▒░\n"
        + "    ░░   ░    ░   ░ ░   ░  ▒ ░░  ░  ░    ░        ░░   ░ ▒ ▒ ░░\n"
        + "     ░        ░  ░      ░  ░        ░              ░     ░ ░\n"
        + "                                                         ░ ░\n%s"
        + "(✿◕ ‿◕ )ノ%s\n",
        LIGHT_PURPLE, ORANGE, PINK, RESET);

    final RegistryTestServer server = new RegistryTestServer(address);
    Statement runner =
        new Statement() {
          @Override
          public void evaluate() throws InterruptedException {
            System.out.printf("%sLoading Datastore fixtures...%s\n", BLUE, RESET);
            for (Fixture fixture : fixtures) {
              fixture.load();
            }
            System.out.printf("%sStarting Jetty6 HTTP Server...%s\n", BLUE, RESET);
            server.start();
            System.out.printf("%sListening on: %s%s\n", PURPLE, server.getUrl("/"), RESET);
            try {
              while (true) {
                server.process();
              }
            } finally {
              server.stop();
            }
          }
        };

    System.out.printf("%sLoading SQL fixtures and AppEngineRule...%s\n", BLUE, RESET);
    AppEngineRule.builder()
        .withDatastoreAndCloudSql()
        .withUrlFetch()
        .withTaskQueue()
        .withLocalModules()
        .withUserService(
            loginIsAdmin
                ? UserInfo.createAdmin(loginEmail, loginUserId)
                : UserInfo.create(loginEmail, loginUserId))
        .build()
        .apply(runner, Description.EMPTY)
        .evaluate();
  }

  private RegistryTestServerMain() {}
}
