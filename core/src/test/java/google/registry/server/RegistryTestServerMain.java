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
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTransactionManagerExtension;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.OidcTokenAuthenticationMechanism;
import google.registry.testing.DatabaseHelper;
import google.registry.tools.params.HostAndPortParameter;
import java.util.List;

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
      names = "--address",
      description = "Listening address.",
      validateWith = HostAndPortParameter.class)
  private HostAndPort address = HostAndPort.fromString("[::1]:8080");

  @Parameter(names = "--fixtures", description = "Fixtures to load into the DB.")
  private List<Fixture> fixtures = ImmutableList.of(Fixture.BASIC);

  @Parameter(
      names = "--login_email",
      description = "Login email address for App Engine Local User Service.")
  private String loginEmail = "Marla.Singer@crr.com";

  @Parameter(
      names = "--login_is_admin",
      description = "Should logged in user be an admin for App Engine Local User Service.",
      arity = 1)
  private boolean loginIsAdmin = true;

  @Parameter(names = "--jetty_debug", description = "Enables Jetty debug logging.")
  private boolean jettyDebug;

  @Parameter(names = "--jetty_verbose", description = "Enables Jetty verbose logging.")
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
    if (jettyDebug) {
      System.setProperty("DEBUG", "true");
    }
    if (jettyVerbose) {
      System.setProperty("VERBOSE", "true");
    }

    System.out.printf(
"""

        CHARLESTON ROAD REGISTRY SHARED REGISTRATION SYSTEM
                      ICANN-GTLD-AGB-20120604

%s        ▓█████▄  ▒█████   ███▄ ▄███▓ ▄▄▄       ██▓ ███▄    █
        ▒██▀ ██▌▒██▒  ██▒▓██▒▀█▀ ██▒▒████▄    ▓██▒ ██ ▀█   █
        ░██   █▌▒██░  ██▒▓██    ▓██░▒██  ▀█▄  ▒██▒▓██  ▀█ ██▒
        ░▓█▄   ▌▒██   ██░▒██    ▒██ ░██▄▄▄▄██ ░██░▓██▒  ▐▌██▒
        ░▒████▓ ░ ████▓▒░▒██▒   ░██▒ ▓█   ▓██▒░██░▒██░   ▓██░
         ▒▒▓  ▒ ░ ▒░▒░▒░ ░ ▒░   ░  ░ ▒▒   ▓▒█░░▓  ░ ▒░   ▒ ▒
         ░ ▒  ▒   ░ ▒ ▒░ ░  ░      ░  ▒   ▒▒ ░ ▒ ░░ ░░   ░ ▒░
         ░ ░  ░ ░ ░ ░ ▒  ░      ░     ░   ▒    ▒ ░   ░   ░ ░
           ░        ░ ░         ░         ░  ░ ░           ░
         ░
%s    ██▀███  ▓█████   ▄████  ██▓  ██████ ▄▄▄█████▓ ██▀███ ▓██   ██▓
    ▓██ ▒ ██▒▓█   ▀  ██▒ ▀█▒▓██▒▒██    ▒ ▓  ██▒ ▓▒▓██ ▒ ██▒▒██  ██▒
    ▓██ ░▄█ ▒▒███   ▒██░▄▄▄░▒██▒░ ▓██▄   ▒ ▓██░ ▒░▓██ ░▄█ ▒ ▒██ ██░
    ▒██▀▀█▄  ▒▓█  ▄ ░▓█  ██▓░██░  ▒   ██▒░ ▓██▓ ░ ▒██▀▀█▄   ░ ▐██▓░
    ░██▓ ▒██▒░▒████▒░▒▓███▀▒░██░▒██████▒▒  ▒██▒ ░ ░██▓ ▒██▒ ░ ██▒▓░
    ░ ▒▓ ░▒▓░░░ ▒░ ░ ░▒   ▒ ░▓  ▒ ▒▓▒ ▒ ░  ▒ ░░   ░ ▒▓ ░▒▓░  ██▒▒▒
    ░▒ ░ ▒░ ░ ░  ░  ░   ░  ▒ ░░ ░▒  ░ ░    ░      ░▒ ░ ▒░▓██ ░▒░
    ░░   ░    ░   ░ ░   ░  ▒ ░░  ░  ░    ░        ░░   ░ ▒ ▒ ░░
     ░        ░  ░      ░  ░        ░              ░     ░ ░
                                                         ░ ░
%s(✿◕ ‿◕ )ノ%s
""",
        LIGHT_PURPLE, ORANGE, PINK, RESET);

    final RegistryTestServer server = new RegistryTestServer(address);

    System.out.printf("%sLoading SQL fixtures setting User for authentication...%s\n", BLUE, RESET);
    new JpaTestExtensions.Builder().buildIntegrationTestExtension().beforeEach(null);
    JpaTransactionManagerExtension.loadInitialData();
    UserRoles userRoles =
        new UserRoles.Builder().setIsAdmin(loginIsAdmin).setGlobalRole(GlobalRole.FTE).build();
    User user =
        DatabaseHelper.persistResource(
            new User.Builder()
                .setEmailAddress(loginEmail)
                .setUserRoles(userRoles)
                .setRegistryLockPassword("registryLockPassword")
                .build());
    OidcTokenAuthenticationMechanism.setAuthResultForTesting(AuthResult.createUser(user));
    System.out.printf("%sLoading fixtures...%s\n", BLUE, RESET);
    for (Fixture fixture : fixtures) {
      fixture.load();
    }
    System.out.printf("%sStarting Jetty HTTP Server...%s\n", BLUE, RESET);
    server.start();
    System.out.printf("%sListening on: %s%s\n", PURPLE, server.getUrl("/"), RESET);
    try {
      // This infinite loop is terminated when the user presses Ctrl-C.
      //noinspection InfiniteLoopStatement
      while (true) {
        server.process();
      }
    } finally {
      server.stop();
      // appEngine.tearDown();
    }
  }

  private RegistryTestServerMain() {}
}
