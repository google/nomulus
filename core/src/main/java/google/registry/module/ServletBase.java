// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static com.google.cloud.logging.TraceLoggingEnhancer.setCurrentTraceId;
import static google.registry.util.RandomStringGenerator.insecureRandomStringGenerator;
import static google.registry.util.RegistryEnvironment.isOnJetty;
import static google.registry.util.StringGenerator.Alphabets.HEX_DIGITS_ONLY;

import com.google.common.flogger.FluentLogger;
import com.google.monitoring.metrics.MetricReporter;
import dagger.Lazy;
import google.registry.request.RequestHandler;
import google.registry.util.RandomStringGenerator;
import google.registry.util.RegistryEnvironment;
import google.registry.util.SystemClock;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Security;
import java.util.concurrent.TimeoutException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.joda.time.DateTime;

/** Base for Servlets that handle all requests to our App Engine modules. */
public class ServletBase extends HttpServlet {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // Length of a log trace_id, arbitrarily chosen.
  private static final int LOG_TRACE_ID_LENGTH = 32;
  // GCP log trace pattern. Fill in project_id and trace id
  private static final String LOG_TRACE_PATTERN = "projects/%s/traces/%s";
  private static final RandomStringGenerator LOG_TRACE_ID_GENERATOR =
      insecureRandomStringGenerator(HEX_DIGITS_ONLY);

  private final RequestHandler<?> requestHandler;
  private final Lazy<MetricReporter> metricReporter;

  private final String projectId;
  private static final SystemClock clock = new SystemClock();

  public ServletBase(
      String projectId, RequestHandler<?> requestHandler, Lazy<MetricReporter> metricReporter) {
    this.projectId = projectId;
    this.requestHandler = requestHandler;
    this.metricReporter = metricReporter;
  }

  @Override
  public void init() {
    Security.addProvider(new BouncyCastleProvider());

    // If the metric reporter failed to instantiate for any reason (bad keyring, bad json
    // credential, etc.), we log the error but keep the main thread running. Also, the shutdown hook
    // will only be registered if the metric reporter starts up correctly.
    try {
      metricReporter.get().startAsync().awaitRunning(java.time.Duration.ofSeconds(10));
      logger.atInfo().log("Started up MetricReporter.");
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      metricReporter
                          .get()
                          .stopAsync()
                          .awaitTerminated(java.time.Duration.ofSeconds(10));
                      logger.atInfo().log("Shut down MetricReporter.");
                    } catch (TimeoutException e) {
                      logger.atSevere().withCause(e).log("Failed to stop MetricReporter.");
                    }
                  }));
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Failed to initialize MetricReporter.");
    }
  }

  @Override
  public void service(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    DateTime startTime = clock.nowUtc();
    try {
      setTraceId();
      logger.atInfo().log("Received %s request.", getClass().getSimpleName());
      requestHandler.handleRequest(req, rsp);
    } finally {
      logger.atInfo().log(
          "Finished %s request. Latency: %.3fs.",
          getClass().getSimpleName(), (clock.nowUtc().getMillis() - startTime.getMillis()) / 1000d);
      removeTraceId();
    }
  }

  /** Sets a thread-local trace id for request scope log tracing. */
  void setTraceId() {
    if (RegistryEnvironment.get().equals(RegistryEnvironment.LOCAL)) {
      return;
    }
    // Use `isOnJetty == True` as a stand-in for GKE check in Nomulus. Do not manually set trace_ids
    // on platforms already doing so, like AppEngine or Cloud Run.
    if (isOnJetty()) {
      setCurrentTraceId(traceId());
    }
  }

  /** Removes any thread-local trace id. */
  void removeTraceId() {
    if (isOnJetty()) {
      setCurrentTraceId(null);
    }
  }

  String traceId() {
    return String.format(
        LOG_TRACE_PATTERN, projectId, LOG_TRACE_ID_GENERATOR.createString(LOG_TRACE_ID_LENGTH));
  }
}
