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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.appengine.api.log.LogQuery;
import com.google.appengine.api.log.LogService;
import com.google.appengine.api.log.RequestLogs;
import com.google.apphosting.api.ApiProxy;
import com.google.common.collect.ImmutableList;
import com.google.common.testing.TestLogHandler;
import google.registry.testing.AppEngineExtension;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RequestStatusCheckerImpl}. */
final class RequestStatusCheckerImplTest {

  private static final TestLogHandler logHandler = new TestLogHandler();

  private static final RequestStatusChecker requestStatusChecker = new RequestStatusCheckerImpl();

  /**
   * Matcher for the expected LogQuery in {@link RequestStatusCheckerImpl#isRunning}.
   *
   * Because LogQuery doesn't have a .equals function, we have to create an actual matcher to make
   * sure we have the right argument in our mocks.
   */
  private static LogQuery expectedLogQuery(final String requestLogId) {
    return argThat(
        object -> {
          assertThat(object).isInstanceOf(LogQuery.class);
          assertThat(object.getRequestIds()).containsExactly(requestLogId);
          assertThat(object.getIncludeAppLogs()).isFalse();
          assertThat(object.getIncludeIncomplete()).isTrue();
          return true;
        });
  }

  @RegisterExtension AppEngineExtension appEngineExtension = AppEngineExtension.builder().build();

  @BeforeEach
  void beforeEach() {
    JdkLoggerConfig.getConfig(RequestStatusCheckerImpl.class).addHandler(logHandler);
    RequestStatusCheckerImpl.logService = mock(LogService.class);
  }

  @AfterEach
  void afterEach() {
    JdkLoggerConfig.getConfig(RequestStatusCheckerImpl.class).removeHandler(logHandler);
  }

  // If a logId is unrecognized, it could be that the log hasn't been uploaded yet - so we assume
  // it's a request that has just started running recently.
  @Test
  void testIsRunning_unrecognized() {
    when(RequestStatusCheckerImpl.logService.fetch(expectedLogQuery("12345678")))
        .thenReturn(ImmutableList.of());
    assertThat(requestStatusChecker.isRunning("12345678")).isTrue();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.INFO, "Queried an unrecognized requestLogId");
  }

  @Test
  void testIsRunning_notFinished() {
    RequestLogs requestLogs = new RequestLogs();
    requestLogs.setFinished(false);

    when(RequestStatusCheckerImpl.logService.fetch(expectedLogQuery("12345678")))
        .thenReturn(ImmutableList.of(requestLogs));

    assertThat(requestStatusChecker.isRunning("12345678")).isTrue();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.INFO, "isFinished: false");
  }

  @Test
  void testIsRunning_finished() {
    RequestLogs requestLogs = new RequestLogs();
    requestLogs.setFinished(true);

    when(RequestStatusCheckerImpl.logService.fetch(expectedLogQuery("12345678")))
        .thenReturn(ImmutableList.of(requestLogs));

    assertThat(requestStatusChecker.isRunning("12345678")).isFalse();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.INFO, "isFinished: true");
  }

  @Test
  void testGetLogId_returnsRequestLogId() {
    String expectedLogId = ApiProxy.getCurrentEnvironment().getAttributes().get(
        "com.google.appengine.runtime.request_log_id").toString();
    assertThat(requestStatusChecker.getLogId()).isEqualTo(expectedLogId);
  }

  @Test
  void testGetLogId_createsLog() {
    requestStatusChecker.getLogId();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.INFO, "Current requestLogId: ");
  }
}
