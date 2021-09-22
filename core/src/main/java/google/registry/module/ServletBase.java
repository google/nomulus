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

import com.google.appengine.api.LifecycleManager;
import com.google.common.flogger.FluentLogger;
import com.google.monitoring.metrics.MetricReporter;
import dagger.Lazy;
import google.registry.request.RequestHandler;
import google.registry.util.SystemClock;
import java.io.IOException;
import java.security.Security;
import java.util.concurrent.TimeoutException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.joda.time.DateTime;

/** Base for Servlets that handle all requests to our App Engine modules. */
public class ServletBase extends HttpServlet {

  private final RequestHandler<?> requestHandler;
  private final Lazy<MetricReporter> metricReporter;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final SystemClock clock = new SystemClock();

  public ServletBase(RequestHandler<?> requestHandler, Lazy<MetricReporter> metricReporter) {
    this.requestHandler = requestHandler;
    this.metricReporter = metricReporter;
  }

  @Override
  public void init() {
    Security.addProvider(new BouncyCastleProvider());

    // If metric reporter failed to instantiate for any reason (bad keyring, bad json credential,
    // etc), we log the error but keep the main thread running. Also the shutdown hook will only be
    // registered if metric reporter starts up correctly.
    try {
      metricReporter.get().startAsync().awaitRunning(java.time.Duration.ofSeconds(10));
      logger.atInfo().log("Started up MetricReporter.");
      LifecycleManager.getInstance()
          .setShutdownHook(
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
              });
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Failed to initialize MetricReporter.");
    }
  }

  @Override
  public void service(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    logger.atInfo().log("Received %s request.", getClass().getSimpleName());
    DateTime startTime = clock.nowUtc();
    try {
      requestHandler.handleRequest(req, rsp);
    } finally {
      logger.atInfo().log(
          "Finished %s request. Latency: %.3fs.",
          getClass().getSimpleName(), (clock.nowUtc().getMillis() - startTime.getMillis()) / 1000d);
    }
  }
}
