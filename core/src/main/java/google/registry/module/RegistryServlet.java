// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.module;

import static google.registry.util.GcpJsonFormatter.setCurrentRequest;
import static google.registry.util.GcpJsonFormatter.setCurrentTraceId;
import static google.registry.util.GcpJsonFormatter.unsetCurrentRequest;
import static google.registry.util.RandomStringGenerator.insecureRandomStringGenerator;
import static google.registry.util.StringGenerator.Alphabets.HEX_DIGITS_ONLY;

import com.google.monitoring.metrics.MetricReporter;
import dagger.Lazy;
import google.registry.request.RequestHandler;
import google.registry.util.GcpJsonFormatter;
import google.registry.util.JdkLoggerConfig;
import google.registry.util.RandomStringGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;

/** Servlet that handles all requests. */
public class RegistryServlet extends ServletBase {

  // Length of a log trace_id, arbitrarily chosen.
  private static final int LOG_TRACE_ID_LENGTH = 32;
  // GCP log trace pattern. Fill in project_id and trace id
  private static final String LOG_TRACE_PATTERN = "projects/%s/traces/%s";
  private static final RandomStringGenerator LOG_TRACE_ID_GENERATOR =
      insecureRandomStringGenerator(HEX_DIGITS_ONLY);

  private static final RegistryComponent component = DaggerRegistryComponent.create();
  private static final RequestHandler<RequestComponent> requestHandler = component.requestHandler();
  private static final Lazy<MetricReporter> metricReporter = component.metricReporter();

  private final String projectId;

  static {
    // Remove all other handlers on the root logger to avoid double logging.
    JdkLoggerConfig rootLoggerConfig = JdkLoggerConfig.getConfig("");
    Arrays.asList(rootLoggerConfig.getHandlers()).forEach(rootLoggerConfig::removeHandler);

    Handler rootHandler = new ConsoleHandler();
    rootHandler.setLevel(Level.INFO);
    rootHandler.setFormatter(new GcpJsonFormatter());
    rootLoggerConfig.addHandler(rootHandler);
  }

  public RegistryServlet() {
    super(requestHandler, metricReporter);
    this.projectId = component.projectId();
  }

  @Override
  public void service(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    setCurrentTraceId(traceId());
    String requestMethod = req.getMethod();
    String requestUrl = req.getRequestURI();
    String userAgent = String.valueOf(req.getHeader("User-Agent"));
    String protocol = req.getProtocol();
    setCurrentRequest(requestMethod, requestUrl, userAgent, protocol);
    try {
      super.service(req, rsp);
    } finally {
      setCurrentTraceId(null);
      unsetCurrentRequest();
    }
  }

  String traceId() {
    return String.format(
        LOG_TRACE_PATTERN, projectId, LOG_TRACE_ID_GENERATOR.createString(LOG_TRACE_ID_LENGTH));
  }
}
