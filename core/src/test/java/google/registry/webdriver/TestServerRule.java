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
import static google.registry.testing.AppEngineRule.THE_REGISTRAR_GAE_USER_ID;
import static google.registry.util.NetworkUtils.getExternalAddressOfLocalSystem;
import static google.registry.util.NetworkUtils.pickUnusedPort;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaNomulusIntegrationTestRule;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.server.Fixture;
import google.registry.server.Route;
import google.registry.server.TestServer;
import google.registry.testing.AppEngineRule;
import google.registry.testing.UserInfo;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import javax.servlet.Filter;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * JUnit Rule that sets up and tears down {@link TestServer}.
 *
 * <p><b>Warning:</b> App Engine testing environments are thread local. This rule will spawn that
 * testing environment in a separate thread from your unit tests. Therefore any modifications you
 * need to make to that testing environment (e.g. Datastore interactions) must be done through the
 * {@link #runInAppEngineEnvironment(Callable)} method.
 */
public final class TestServerRule extends ExternalResource {

  private final ImmutableList<Fixture> fixtures;
  private final AppEngineRule appEngineRule;
  private final JpaNomulusIntegrationTestRule jpaTransactionManagerRule;
  private final BlockingQueue<FutureTask<?>> jobs = new LinkedBlockingDeque<>();
  private final ImmutableMap<String, Path> runfiles;
  private final ImmutableList<Route> routes;
  private final ImmutableList<Class<? extends Filter>> filters;

  private TestServer testServer;
  private Thread serverThread;

  private TestServerRule(
      ImmutableMap<String, Path> runfiles,
      ImmutableList<Route> routes,
      ImmutableList<Class<? extends Filter>> filters,
      ImmutableList<Fixture> fixtures,
      String email) {
    this.runfiles = runfiles;
    this.routes = routes;
    this.filters = filters;
    this.fixtures = fixtures;
    // We create an GAE-Admin user, and then use AuthenticatedRegistrarAccessor.bypassAdminCheck to
    // choose whether the user is an admin or not.
    this.appEngineRule = AppEngineRule.builder()
        .withDatastore()
        .withLocalModules()
        .withUrlFetch()
        .withTaskQueue()
        .withUserService(UserInfo.createAdmin(email, THE_REGISTRAR_GAE_USER_ID))
        .build();
    this.jpaTransactionManagerRule = new JpaTestRules.Builder().buildIntegrationTestRule();
  }

  @Override
  protected void before() throws InterruptedException {
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
    Server server = new Server();
    serverThread = new Thread(server);
    synchronized (this) {
      serverThread.start();
      while (!server.isRunning) {
        this.wait();
      }
    }
  }

  @Override
  protected void after() {
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
   * <p>You should use this method when you want to do things like change Datastore, because the
   * App Engine testing environment is thread-local.
   */
  public <T> T runInAppEngineEnvironment(Callable<T> callback) throws Throwable {
    FutureTask<T> job = new FutureTask<>(callback);
    jobs.add(job);
    testServer.ping();
    return job.get();
  }

  private final class Server extends Statement implements Runnable {
    private volatile boolean isRunning = false;

    @Override
    public void run() {
      try {
        Statement appEngineStatement = appEngineRule.apply(this, Description.EMPTY);
        jpaTransactionManagerRule.apply(appEngineStatement, Description.EMPTY).evaluate();
      } catch (InterruptedException e) {
        // This is what we expect to happen.
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void evaluate() throws InterruptedException {
      for (Fixture fixture : fixtures) {
        fixture.load();
      }
      testServer.start();
      System.out.printf("TestServerRule is listening on: %s\n", testServer.getUrl("/"));
      synchronized (TestServerRule.this) {
        isRunning = true;
        TestServerRule.this.notify();
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
   * Builder for {@link TestServerRule}.
   *
   * <p>This builder has three required fields: {@link #setRunfiles}, {@link #setRoutes}, and {@link
   * #setFilters}.
   *
   */
  public static final class Builder {
    private ImmutableMap<String, Path> runfiles;
    private ImmutableList<Route> routes;
    ImmutableList<Class<? extends Filter>> filters;
    private ImmutableList<Fixture> fixtures = ImmutableList.of();
    private String email;

    /** Sets the directories containing the static files for {@link TestServer}. */
    public Builder setRunfiles(ImmutableMap<String, Path> runfiles) {
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

    /** Returns a new {@link TestServerRule} instance. */
    public TestServerRule build() {
      return new TestServerRule(
          checkNotNull(this.runfiles),
          checkNotNull(this.routes),
          checkNotNull(this.filters),
          checkNotNull(this.fixtures),
          checkNotNull(this.email));
    }
  }
}
