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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.util.concurrent.Runnables.doNothing;
import static google.registry.util.NetworkUtils.getCanonicalHostName;
import static java.util.concurrent.Executors.newCachedThreadPool;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.SimpleTimeLimiter;
import google.registry.util.UrlChecker;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import javax.servlet.Filter;
import javax.servlet.http.HttpServlet;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.bio.SocketConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.DefaultServlet;
import org.mortbay.jetty.servlet.ServletHolder;

/**
 * HTTP server that serves static content and handles servlet requests in the calling thread.
 *
 * <p>Using this server is similar to to other server classes, in that it has {@link #start()} and
 * {@link #stop()} methods. However a {@link #process()} method was added, which is used to process
 * requests made to servlets (not static files) in the calling thread.
 *
 * <p><b>Note:</b> This server is intended for development purposes. For the love all that is good,
 * do not make this public facing.
 *
 * <h3>Implementation Details</h3>
 *
 * <p>Jetty6 is multithreaded and provides no mechanism for controlling which threads execute your
 * requests. HttpServer solves this problem by wrapping all the servlets provided to the constructor
 * inside {@link ServletWrapperDelegatorServlet}. When requests come in, a {@link FutureTask} will
 * be sent back to this class using a {@link LinkedBlockingDeque} message queue. Those messages are
 * then consumed by the {@code process()} method.
 *
 * <p>The reason why this is necessary is because the App Engine local testing services (created by
 * {@code LocalServiceTestHelper}) only apply to a single thread (probably to allow multi-threaded
 * tests). So when Jetty creates random threads to handle requests, they won't have access to the
 * Datastore and other stuff.
 */
public final class TestServer {

  private static final int DEFAULT_PORT = 80;
  private static final String CONTEXT_PATH = "/";
  private static final int STARTUP_TIMEOUT_MS = 5000;
  private static final int SHUTDOWN_TIMEOUT_MS = 5000;

  private final HostAndPort urlAddress;
  private final Server server = new Server();
  private final BlockingQueue<FutureTask<Void>> requestQueue = new LinkedBlockingDeque<>();

  /**
   * Creates a new instance, but does not begin serving.
   *
   * @param address socket bind address
   * @param runfiles map of server paths to local directories or files, to be served statically
   * @param routes list of servlet endpoints
   */
  public TestServer(
      HostAndPort address,
      ImmutableMap<String, Path> runfiles,
      ImmutableList<Route> routes,
      ImmutableList<Class<? extends Filter>> filters) {
    urlAddress = createUrlAddress(address);
    server.addConnector(createConnector(address));
    server.addHandler(createHandler(runfiles, routes, filters));
  }

  /** Starts the HTTP server in a new thread and returns once it's online. */
  public void start() {
    try {
      server.start();
    } catch (Exception e) {
      throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
    UrlChecker.waitUntilAvailable(getUrl("/healthz"), STARTUP_TIMEOUT_MS);
  }

  /**
   * Processes a single servlet request.
   *
   * <p>This method should be called from within a loop.
   *
   * @throws InterruptedException if this thread was interrupted while waiting for a request.
   */
  public void process() throws InterruptedException {
    requestQueue.take().run();
  }

  /**
   * Adds a fake entry to this server's event loop.
   *
   * <p>This is useful in situations when a random thread wants {@link #process()} to return in the
   * main event loop, for post-request processing.
   */
  public void ping() {
    requestQueue.add(new FutureTask<>(doNothing(), null));
  }

  /** Stops the HTTP server. */
  public void stop() {
    try {
      Void unusedReturnValue = SimpleTimeLimiter.create(newCachedThreadPool())
          .callWithTimeout(
              () -> {
                server.stop();
                return null;
              },
              SHUTDOWN_TIMEOUT_MS,
              TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
  }

  /** Returns a URL that can be used to communicate with this server. */
  public URL getUrl(String path) {
    checkArgument(path.startsWith("/"), "Path must start with a slash: %s", path);
    try {
      return new URL(String.format("http://%s%s", urlAddress, path));
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  private Context createHandler(
      Map<String, Path> runfiles,
      ImmutableList<Route> routes,
      ImmutableList<Class<? extends Filter>> filters) {
    Context context = new Context(server, CONTEXT_PATH, Context.SESSIONS);
    context.addServlet(new ServletHolder(HealthzServlet.class), "/healthz");
    for (Map.Entry<String, Path> runfile : runfiles.entrySet()) {
      context.addServlet(
          StaticResourceServlet.create(runfile.getKey(), runfile.getValue()),
          runfile.getKey());
    }
    for (Route route : routes) {
      context.addServlet(
          new ServletHolder(wrapServlet(route.servletClass(), filters)), route.path());
    }
    ServletHolder holder = new ServletHolder(DefaultServlet.class);
    holder.setInitParameter("aliases", "1");
    context.addServlet(holder, "/*");
    return context;
  }

  private HttpServlet wrapServlet(
      Class<? extends HttpServlet> servletClass, ImmutableList<Class<? extends Filter>> filters) {
    return new ServletWrapperDelegatorServlet(servletClass, filters, requestQueue);
  }

  private static Connector createConnector(HostAndPort address) {
    SocketConnector connector = new SocketConnector();
    connector.setHost(address.getHost());
    connector.setPort(address.getPortOrDefault(DEFAULT_PORT));
    return connector;
  }

  /** Converts a bind address into an address that other machines can use to connect here. */
  private static HostAndPort createUrlAddress(HostAndPort address) {
    if (address.getHost().equals("::") || address.getHost().equals("0.0.0.0")) {
      return address.getPortOrDefault(DEFAULT_PORT) == DEFAULT_PORT
          ? HostAndPort.fromHost(getCanonicalHostName())
          : HostAndPort.fromParts(getCanonicalHostName(), address.getPort());
    } else {
      return address.getPortOrDefault(DEFAULT_PORT) == DEFAULT_PORT
          ? HostAndPort.fromHost(address.getHost())
          : address;
    }
  }
}
