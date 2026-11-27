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

package google.registry.module;

import static google.registry.testing.LogsSubject.assertAboutLogs;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.google.common.testing.TestLogHandler;
import google.registry.request.RequestHandler;
import google.registry.util.JdkLoggerConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link ServletBase}. */
@ExtendWith(MockitoExtension.class)
class ServletBaseTest {

  @Mock private RequestHandler<?> requestHandler;
  @Mock private HttpServletRequest req;
  @Mock private HttpServletResponse rsp;

  private final TestLogHandler logHandler = new TestLogHandler();
  private ServletBase servlet;

  @BeforeEach
  void beforeEach() {
    JdkLoggerConfig.getConfig(ServletBase.class).addHandler(logHandler);
    JdkLoggerConfig.getConfig(ServletBase.class).setLevel(Level.FINE);
    servlet = new ServletBase(requestHandler);
  }

  @AfterEach
  void afterEach() {
    JdkLoggerConfig.getConfig(ServletBase.class).removeHandler(logHandler);
    JdkLoggerConfig.getConfig(ServletBase.class).setLevel(null);
  }

  @Test
  void testService_logsReceivedAndFinishedAtFine() throws Exception {
    servlet.service(req, rsp);

    verify(requestHandler).handleRequest(req, rsp);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.FINE, "Received ServletBase request.");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.FINE, "Finished ServletBase request. Latency:");
    assertAboutLogs().that(logHandler).hasNoLogsAtLevel(Level.INFO);
  }

  @Test
  void testService_whenHandlerThrows_stillLogsFinishedAtFine() throws Exception {
    doThrow(new RuntimeException("boom")).when(requestHandler).handleRequest(req, rsp);

    assertThrows(RuntimeException.class, () -> servlet.service(req, rsp));
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.FINE, "Received ServletBase request.");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.FINE, "Finished ServletBase request. Latency:");
    assertAboutLogs().that(logHandler).hasNoLogsAtLevel(Level.INFO);
  }
}
