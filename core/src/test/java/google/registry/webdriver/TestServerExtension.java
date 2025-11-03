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

package google.registry.webdriver;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.NetworkUtils.getExternalAddressOfLocalSystem;
import static google.registry.util.NetworkUtils.pickUnusedPort;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.OidcTokenAuthenticationMechanism;
import google.registry.server.Fixture;
import google.registry.server.Route;
import google.registry.server.TestServer;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import javax.annotation.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** JUnit extension that sets up and tears down {@link TestServer}. */
public final class TestServerExtension implements BeforeEachCallback, AfterEachCallback {

  private static final Duration SERVER_STATUS_POLLING_INTERVAL = Duration.ofSeconds(5);

  private final ImmutableList<Fixture> fixtures;
  private final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();
  private final BlockingQueue<FutureTask<?>> jobs = new LinkedBlockingDeque<>();
  private final ImmutableMap<String, Path> runfiles;
  private final ImmutableList<Route> routes;
  private User user;

  private TestServer testServer;
  private Thread serverThread;

  private TestServerExtension(
      ImmutableMap<String, Path> runfiles,
      ImmutableList<Route> routes,
      ImmutableList<Fixture> fixtures,
      String email,
      @Nullable String registryLockEmail) {
    this.runfiles = runfiles;
    this.routes = routes;
    this.fixtures = fixtures;
    // We create a user, and then use setIsAdmin to override this setting if necessary
    this.user =
        new User.Builder()
            .setEmailAddress(email)
            .setRegistryLockEmailAddress(registryLockEmail)
            .setUserRoles(new UserRoles.Builder().build())
            .build();
  }

  @Override
  public void beforeEach(ExtensionContext context) throws InterruptedException {
    try {
      testServer =
          new TestServer(
              HostAndPort.fromParts(
                  // Use external IP address here so the browser running inside Docker container
                  // can access this server.
                  getExternalAddressOfLocalSystem().getHostAddress(), pickUnusedPort()),
              runfiles,
              routes);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
    Server server = new Server(context);
    serverThread = new Thread(server);
    synchronized (this) {
      serverThread.start();
      while (server.serverStatus.equals(ServerStatus.NOT_STARTED)) {
        this.wait(SERVER_STATUS_POLLING_INTERVAL.toMillis());
      }
      if (server.serverStatus.equals(ServerStatus.FAILED)) {
        throw new RuntimeException("TestServer failed to start. See log for details.");
      }
    }
  }

  @Override
  public void afterEach(ExtensionContext context) {
    serverThread.interrupt();
    OidcTokenAuthenticationMechanism.unsetAuthResultForTesting();
    try {
      serverThread.join();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } finally {
      serverThread = null;
      jobs.clear();
      testServer = null;
    }
  }

  /** Set the current user's admin status. */
  public void setIsAdmin(boolean isAdmin) {
    user =
        user.asBuilder()
            .setUserRoles(user.getUserRoles().asBuilder().setIsAdmin(isAdmin).build())
            .build();
    OidcTokenAuthenticationMechanism.setAuthResultForTesting(AuthResult.createUser(user));
  }

  /** Set the current user's registrar role map. */
  public void setRegistrarRoles(Map<String, RegistrarRole> registrarRoles) {
    user =
        user.asBuilder()
            .setUserRoles(user.getUserRoles().asBuilder().setRegistrarRoles(registrarRoles).build())
            .build();
    OidcTokenAuthenticationMechanism.setAuthResultForTesting(AuthResult.createUser(user));
  }

  /** Sets the current user's global role. */
  public void setGlobalRole(GlobalRole globalRole) {
    user =
        user.asBuilder()
            .setUserRoles(new UserRoles.Builder().setGlobalRole(globalRole).build())
            .build();
    OidcTokenAuthenticationMechanism.setAuthResultForTesting(AuthResult.createUser(user));
  }

  /**
   * @see TestServer#getUrl(String)
   */
  public URL getUrl(String path) {
    return testServer.getUrl(path);
  }

  enum ServerStatus {
    NOT_STARTED,
    RUNNING,
    FAILED
  }

  private final class Server implements Runnable {

    private final ExtensionContext context;

    Server(ExtensionContext context) {
      this.context = context;
    }

    private volatile ServerStatus serverStatus = ServerStatus.NOT_STARTED;

    @Override
    public void run() {
      try {
        try {
          jpa.beforeEach(context);
          this.runInner();
        } catch (InterruptedException e) {
          // This is what we expect to happen.
        } finally {
          jpa.afterEach(context);
        }
      } catch (Throwable e) {
        serverStatus = ServerStatus.FAILED;
        throw new RuntimeException(e);
      }
    }

    void runInner() throws Exception {
      for (Fixture fixture : fixtures) {
        fixture.load();
      }
      testServer.start();
      System.out.printf("TestServerExtension is listening on: %s\n", testServer.getUrl("/"));
      synchronized (TestServerExtension.this) {
        serverStatus = ServerStatus.RUNNING;
        TestServerExtension.this.notify();
      }
      try {
        //noinspection InfiniteLoopStatement
        while (true) {
          testServer.process();
          flushJobs();
        }
      } finally {
        testServer.stop();
      }
    }

    private void flushJobs() {
      while (true) {
        FutureTask<?> job = jobs.poll();
        if (job != null) {
          job.run();
        } else {
          break;
        }
      }
    }
  }

  /**
   * Builder for {@link TestServerExtension}.
   *
   * <p>This builder has two required fields: {@link #setRunfiles} and {@link #setRoutes}.
   */
  public static final class Builder {

    private ImmutableMap<String, Path> runfiles;
    private ImmutableList<Route> routes;
    private ImmutableList<Fixture> fixtures = ImmutableList.of();
    private String email;
    @Nullable private String registryLockEmail;

    /** Sets the directories containing the static files for {@link TestServer}. */
    Builder setRunfiles(ImmutableMap<String, Path> runfiles) {
      this.runfiles = runfiles;
      return this;
    }

    public Builder setRoutes(ImmutableList<Route> routes) {
      checkArgument(!routes.isEmpty(), "Must include at least one route");
      this.routes = routes;
      return this;
    }

    /** Sets an ordered list of fixtures that should be loaded on startup. */
    public Builder setFixtures(Fixture... fixtures) {
      this.fixtures = ImmutableList.copyOf(fixtures);
      return this;
    }

    /** Sets the login email of the user. */
    public Builder setEmail(String email) {
      this.email = email;
      return this;
    }

    /** Set the registry lock email of the user, if any. */
    public Builder setRegistryLockEmail(@Nullable String email) {
      this.registryLockEmail = email;
      return this;
    }

    /** Returns a new {@link TestServerExtension} instance. */
    public TestServerExtension build() {
      return new TestServerExtension(
          checkNotNull(this.runfiles),
          checkNotNull(this.routes),
          checkNotNull(this.fixtures),
          checkNotNull(this.email),
          this.registryLockEmail);
    }
  }
}
