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
import static google.registry.testing.AppEngineExtension.THE_REGISTRAR_GAE_USER_ID;
import static google.registry.util.NetworkUtils.getExternalAddressOfLocalSystem;
import static google.registry.util.NetworkUtils.pickUnusedPort;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.server.Fixture;
import google.registry.server.Route;
import google.registry.server.TestServer;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.UserInfo;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import javax.servlet.Filter;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit extension that sets up and tears down {@link TestServer}.
 *
 * <p><b>Warning:</b> App Engine testing environments are thread local. This extension will spawn
 * that testing environment in a separate thread from your unit tests. Therefore any modifications
 * you need to make to that testing environment (e.g. Datastore interactions) must be done through
 * the {@link #runInAppEngineEnvironment(Callable)} method.
 */
public final class TestServerExtension implements BeforeEachCallback, AfterEachCallback {

  private final ImmutableList<Fixture> fixtures;
  private final AppEngineExtension appEngineExtension;
  private final BlockingQueue<FutureTask<?>> jobs = new LinkedBlockingDeque<>();
  private final ImmutableMap<String, Path> runfiles;
  private final ImmutableList<Route> routes;
  private final ImmutableList<Class<? extends Filter>> filters;

  private TestServer testServer;
  private Thread serverThread;

  private TestServerExtension(
      ImmutableMap<String, Path> runfiles,
      ImmutableList<Route> routes,
      ImmutableList<Class<? extends Filter>> filters,
      ImmutableList<Fixture> fixtures,
      String email,
      Optional<String> gaeUserId) {
    this.runfiles = runfiles;
    this.routes = routes;
    this.filters = filters;
    this.fixtures = fixtures;
    // We create an GAE-Admin user, and then use AuthenticatedRegistrarAccessor.bypassAdminCheck to
    // choose whether the user is an admin or not.
    this.appEngineExtension =
        AppEngineExtension.builder()
            .withDatastoreAndCloudSql()
            .withLocalModules()
            .withUrlFetch()
            .withTaskQueue()
            .withUserService(
                UserInfo.createAdmin(email, gaeUserId.orElse(THE_REGISTRAR_GAE_USER_ID)))
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
              routes,
              filters);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
    setIsAdmin(false);
    Server server = new Server(context);
    serverThread = new Thread(server);
    synchronized (this) {
      serverThread.start();
      while (!server.isRunning) {
        this.wait();
      }
    }
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // Reset the global state AuthenticatedRegistrarAccessor.bypassAdminCheck
    // to the default value so it doesn't interfere with other tests
    AuthenticatedRegistrarAccessor.bypassAdminCheck = false;
    serverThread.interrupt();
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

  /**
   * Set the current user's Admin status.
   *
   * <p>This is sort of a hack because we can't actually change the user itself, nor that user's GAE
   * roles. Instead we created a GAE-admin user in the constructor and we "bypass the admin check"
   * if we want that user to not be an admin.
   *
   * <p>A better implementation would be to replace the AuthenticatedRegistrarAccessor - that way we
   * can fully control the Roles the user has without relying on the implementation. But right now
   * we don't have the ability to change injected values like that :/
   */
  public void setIsAdmin(boolean isAdmin) {
    AuthenticatedRegistrarAccessor.bypassAdminCheck = !isAdmin;
  }

  /** @see TestServer#getUrl(String) */
  public URL getUrl(String path) {
    return testServer.getUrl(path);
  }

  /**
   * Runs arbitrary code inside server event loop thread.
   *
   * <p>You should use this method when you want to do things like change Datastore, because the App
   * Engine testing environment is thread-local.
   */
  <T> T runInAppEngineEnvironment(Callable<T> callback) throws Throwable {
    FutureTask<T> job = new FutureTask<>(callback);
    jobs.add(job);
    testServer.ping();
    return job.get();
  }

  private final class Server implements Runnable {

    private ExtensionContext context;

    Server(ExtensionContext context) {
      this.context = context;
    }

    private volatile boolean isRunning = false;

    @Override
    public void run() {
      try {
        try {
          appEngineExtension.beforeEach(context);
          this.runInner();
        } catch (InterruptedException e) {
          // This is what we expect to happen.
        } finally {
          appEngineExtension.afterEach(context);
        }
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    void runInner() throws InterruptedException {
      for (Fixture fixture : fixtures) {
        fixture.load();
      }
      testServer.start();
      System.out.printf("TestServerExtension is listening on: %s\n", testServer.getUrl("/"));
      synchronized (TestServerExtension.this) {
        isRunning = true;
        TestServerExtension.this.notify();
      }
      try {
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
   * <p>This builder has three required fields: {@link #setRunfiles}, {@link #setRoutes}, and {@link
   * #setFilters}.
   */
  public static final class Builder {

    private ImmutableMap<String, Path> runfiles;
    private ImmutableList<Route> routes;
    ImmutableList<Class<? extends Filter>> filters;
    private ImmutableList<Fixture> fixtures = ImmutableList.of();
    private String email;
    private Optional<String> gaeUserId = Optional.empty();

    /** Sets the directories containing the static files for {@link TestServer}. */
    Builder setRunfiles(ImmutableMap<String, Path> runfiles) {
      this.runfiles = runfiles;
      return this;
    }

    /** Sets the list of servlet {@link Route} objects for {@link TestServer}. */
    public Builder setRoutes(Route... routes) {
      checkArgument(routes.length > 0);
      this.routes = ImmutableList.copyOf(routes);
      return this;
    }

    /** Sets the list of servlet {@link Filter} objects for {@link TestServer}. */
    @SafeVarargs
    public final Builder setFilters(Class<? extends Filter>... filters) {
      this.filters = ImmutableList.copyOf(filters);
      return this;
    }

    /** Sets an ordered list of Datastore fixtures that should be loaded on startup. */
    public Builder setFixtures(Fixture... fixtures) {
      this.fixtures = ImmutableList.copyOf(fixtures);
      return this;
    }

    /**
     * Sets information about the logged in user.
     *
     * <p>This unfortunately cannot be changed by test methods.
     */
    public Builder setEmail(String email) {
      this.email = email;
      return this;
    }

    /** Optionally, sets the GAE user ID for the logged in user. */
    public Builder setGaeUserId(String gaeUserId) {
      this.gaeUserId =
          Optional.of(checkArgumentNotNull(gaeUserId, "Must specify a non-null GAE user ID"));
      return this;
    }

    /** Returns a new {@link TestServerExtension} instance. */
    public TestServerExtension build() {
      return new TestServerExtension(
          checkNotNull(this.runfiles),
          checkNotNull(this.routes),
          checkNotNull(this.filters),
          checkNotNull(this.fixtures),
          checkNotNull(this.email),
          this.gaeUserId);
    }
  }
}
