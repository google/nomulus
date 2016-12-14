// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.server.Route.route;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.googlecode.objectify.ObjectifyFilter;
import google.registry.model.ofy.OfyFilter;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.servlet.Filter;

/** Lightweight HTTP server for testing the Nomulus Admin and Registrar consoles. */
public final class RegistryTestServer {

  public static final ImmutableMap<String, Path> RUNFILES =
      new ImmutableMap.Builder<String, Path>()
          .put(
              "/index.html",
              Paths.get(
                  "../domain_registry/java/google/registry/ui/html/index.html"))
          .put(
              "/error.html",
              Paths.get(
                  "../domain_registry/java/google/registry/ui/html/error.html"))
          .put(
              "/assets/js/*",
              Paths.get("../domain_registry/java/google/registry/ui"))
          .put(
              "/assets/css/*",
              Paths.get("../domain_registry/java/google/registry/ui/css"))
          .put("/assets/sources/*", Paths.get(".."))
          .put(
              "/assets/*",
              Paths.get("../domain_registry/java/google/registry/ui/assets"))
          .build();

  private static final ImmutableList<Route> ROUTES = ImmutableList.of(
      // Frontend Services
      route("/whois/*", google.registry.module.frontend.FrontendServlet.class),
      route("/rdap/*", google.registry.module.frontend.FrontendServlet.class),
      route("/registrar-xhr", google.registry.module.frontend.FrontendServlet.class),
      route("/check", google.registry.module.frontend.FrontendServlet.class),

      // Proxy Services
      route("/_dr/epp", google.registry.module.frontend.FrontendServlet.class),
      route("/_dr/whois", google.registry.module.frontend.FrontendServlet.class),

      // Registry Data Escrow (RDE)
      route("/_dr/cron/rdeCreate", google.registry.module.backend.BackendServlet.class),
      route("/_dr/task/rdeStaging", google.registry.module.backend.BackendServlet.class),
      route("/_dr/task/rdeUpload", google.registry.module.backend.BackendServlet.class),
      route("/_dr/task/rdeReport", google.registry.module.backend.BackendServlet.class),
      route("/_dr/task/brdaCopy", google.registry.module.backend.BackendServlet.class),

      // Trademark Clearinghouse (TMCH)
      route("/_dr/cron/tmchDnl", google.registry.module.backend.BackendServlet.class),
      route("/_dr/task/tmchSmdrl", google.registry.module.backend.BackendServlet.class),
      route("/_dr/task/tmchCrl", google.registry.module.backend.BackendServlet.class),

      // Notification of Registered Domain Names (NORDN)
      route("/_dr/task/nordnUpload",
          google.registry.module.backend.BackendServlet.class),
      route("/_dr/task/nordnVerify",
          google.registry.module.backend.BackendServlet.class),

      // Process DNS pull queue
      route("/_dr/cron/readDnsQueue",
          google.registry.module.backend.BackendServlet.class),

      // Registrar Console
      route("/registrar", google.registry.module.frontend.FrontendServlet.class),
      route("/registrar-settings",
          google.registry.module.frontend.FrontendServlet.class),
      route("/registrar-payment",
          google.registry.module.frontend.FrontendServlet.class),
      route("/registrar-payment-setup",
          google.registry.module.frontend.FrontendServlet.class));

  private static final ImmutableList<Class<? extends Filter>> FILTERS = ImmutableList.of(
      ObjectifyFilter.class,
      OfyFilter.class);

  private final TestServer server;

  /** @see TestServer#TestServer(HostAndPort, java.util.Map, Iterable, Iterable) */
  public RegistryTestServer(HostAndPort address) {
    server = new TestServer(address, RUNFILES, ROUTES, FILTERS);
  }

  /** @see TestServer#start() */
  public void start() {
    server.start();
  }

  /** @see TestServer#process() */
  public void process() throws InterruptedException {
    server.process();
  }

  /** @see TestServer#stop() */
  public void stop() {
    server.stop();
  }

  /** @see TestServer#getUrl(java.lang.String) */
  public URL getUrl(String path) {
    return server.getUrl(path);
  }
}
