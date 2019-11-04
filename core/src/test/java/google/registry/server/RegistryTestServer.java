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

import static google.registry.server.Route.route;
import static google.registry.util.BuildPathUtils.getProjectRoot;
import static google.registry.util.BuildPathUtils.getResourcesDir;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.googlecode.objectify.ObjectifyFilter;
import google.registry.model.ofy.OfyFilter;
import google.registry.module.backend.BackendServlet;
import google.registry.module.frontend.FrontendServlet;
import java.net.URL;
import java.nio.file.Path;
import javax.servlet.Filter;

/** Lightweight HTTP server for testing the Nomulus Admin and Registrar consoles. */
public final class RegistryTestServer {

  private static final Path PROJECT_ROOT = getProjectRoot();
  private static final Path RESOURCES_DIR = getResourcesDir();

  public static final ImmutableMap<String, Path> RUNFILES =
      new ImmutableMap.Builder<String, Path>()
          .put("/index.html",
              PROJECT_ROOT.resolve("core/src/main/java/google/registry/ui/html/index.html"))
          .put("/error.html",
              PROJECT_ROOT.resolve("core/src/main/java/google/registry/ui/html/error.html"))
          .put("/assets/js/*", RESOURCES_DIR.resolve("google/registry/ui"))
          .put("/assets/css/*", RESOURCES_DIR.resolve("google/registry/ui/css"))
          .put("/assets/sources/*", PROJECT_ROOT)
          .put("/assets/*", PROJECT_ROOT.resolve("core/src/main/java/google/registry/ui/assets"))
          .build();

  private static final ImmutableList<Route> ROUTES =
      ImmutableList.of(
          // Frontend Services
          route("/whois/*", FrontendServlet.class),
          route("/rdap/*", FrontendServlet.class),
          route("/check", FrontendServlet.class),

          // Proxy Services
          route("/_dr/epp", FrontendServlet.class),
          route("/_dr/whois", FrontendServlet.class),

          // Registry Data Escrow (RDE)
          route("/_dr/cron/rdeCreate", BackendServlet.class),
          route("/_dr/task/rdeStaging", BackendServlet.class),
          route("/_dr/task/rdeUpload", BackendServlet.class),
          route("/_dr/task/rdeReport", BackendServlet.class),
          route("/_dr/task/brdaCopy", BackendServlet.class),

          // Trademark Clearinghouse (TMCH)
          route("/_dr/cron/tmchDnl", BackendServlet.class),
          route("/_dr/task/tmchSmdrl", BackendServlet.class),
          route("/_dr/task/tmchCrl", BackendServlet.class),

          // Notification of Registered Domain Names (NORDN)
          route("/_dr/task/nordnUpload", BackendServlet.class),
          route("/_dr/task/nordnVerify", BackendServlet.class),

          // Process DNS pull queue
          route("/_dr/cron/readDnsQueue", BackendServlet.class),

          // Registrar Console
          route("/registrar", FrontendServlet.class),
          route("/registrar-create", FrontendServlet.class),
          route("/registrar-ote-setup", FrontendServlet.class),
          route("/registrar-ote-status", FrontendServlet.class),
          route("/registrar-settings", FrontendServlet.class),
          route("/registry-lock-get", FrontendServlet.class),
          route("/registry-lock-post", FrontendServlet.class));

  private static final ImmutableList<Class<? extends Filter>> FILTERS = ImmutableList.of(
      ObjectifyFilter.class,
      OfyFilter.class);

  private final TestServer server;

  /** @see TestServer#TestServer(HostAndPort, ImmutableMap, ImmutableList, ImmutableList) */
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
