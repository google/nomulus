// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.request;

import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.testing.LogsSubject.assertAboutLogs;

import com.google.common.testing.TestLogHandler;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.util.JdkLoggerConfig;
import java.time.Duration;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RequestMetrics}. */
class RequestMetricsTest {

  private final TestLogHandler logHandler = new TestLogHandler();
  private final RequestMetrics requestMetrics = new RequestMetrics();

  @BeforeEach
  void beforeEach() {
    JdkLoggerConfig.getConfig(RequestMetrics.class).addHandler(logHandler);
  }

  @AfterEach
  void afterEach() {
    JdkLoggerConfig.getConfig(RequestMetrics.class).removeHandler(logHandler);
  }

  @Test
  void testRecord_logsSuccessWithStatus200() {
    requestMetrics.record(
        Duration.ofMillis(12), "/rdap/domain/example.tld", GET, AuthLevel.NONE, true, 200);

    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Action called for path=/rdap/domain/example.tld, method=GET, authLevel=NONE,"
                + " success=true, status=200. Took: 0.012s.");
  }

  @Test
  void testRecord_logsClientErrorStatus404() {
    requestMetrics.record(
        Duration.ofMillis(9), "/rdap/domain/missing.tld", GET, AuthLevel.NONE, true, 404);

    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Action called for path=/rdap/domain/missing.tld, method=GET, authLevel=NONE,"
                + " success=true, status=404. Took: 0.009s.");
  }

  @Test
  void testRecord_logsHttpExceptionFailureWithStatus503() {
    requestMetrics.record(Duration.ofMillis(25), "/_dr/epp", POST, AuthLevel.APP, false, 503);

    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Action called for path=/_dr/epp, method=POST, authLevel=APP, success=false,"
                + " status=503. Took: 0.025s.");
  }
}
