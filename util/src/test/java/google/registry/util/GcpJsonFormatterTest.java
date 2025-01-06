// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.flogger.FluentLogger;
import com.google.common.flogger.StackSize;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GcpJsonFormatter}.
 *
 * @see <a
 *     href="https://github.com/google/flogger/blob/master/google/src/test/java/com/google/common/flogger/GoogleLoggerTest.java">
 *     GoogleLoggerTest.java</a>
 */
class GcpJsonFormatterTest {

  private Logger jdkLogger;
  private FluentLogger logger;
  private Handler handler;
  private ByteArrayOutputStream ostream;

  private static final String LOG_TEMPLATE =
      """
      {"severity":"@@SEVERITY@@","logging.googleapis.com/sourceLocation":{"file":"GcpJsonFormatterTest.java","line":"@@LINE@@","function":"google.registry.util.GcpJsonFormatterTest.@@FUNCTION@@"},"message":"@@MESSAGE@@"}
      """;

  private static String makeJson(String severity, int line, String function, String message) {
    return LOG_TEMPLATE
        .replace("@@SEVERITY@@", severity)
        .replace("@@LINE@@", String.valueOf(line))
        .replace("@@FUNCTION@@", function)
        .replace("@@MESSAGE@@", message);
  }

  @BeforeEach
  void beforeEach() {
    logger = FluentLogger.forEnclosingClass();
    ostream = new ByteArrayOutputStream();
    handler = new StreamHandler(ostream, new GcpJsonFormatter());
    jdkLogger = Logger.getLogger(GcpJsonFormatterTest.class.getName());
    jdkLogger.setUseParentHandlers(false);
    jdkLogger.addHandler(handler);
    jdkLogger.setLevel(Level.INFO);
  }

  @AfterEach
  void afterEach() {
    jdkLogger.removeHandler(handler);
    GcpJsonFormatter.setCurrentTraceId(null);
  }

  @Test
  void testSuccess() {
    logger.atInfo().log("Something I have to say");
    handler.close();
    String output = ostream.toString(StandardCharsets.US_ASCII);
    assertThat(output).isEqualTo(makeJson("INFO", 78, "testSuccess", "Something I have to say"));
  }

  @Test
  void testSuccess_traceId() {
    GcpJsonFormatter.setCurrentTraceId("trace_id");
    logger.atInfo().log("Something I have to say");
    handler.close();
    String output = ostream.toString(StandardCharsets.US_ASCII);
    String expected = makeJson("INFO", 87, "testSuccess_traceId", "Something I have to say");
    // Remove the last two characters (}, \n) from the template and add the trace ID.
    expected =
        expected.substring(0, expected.length() - 2)
            + ",\"logging.googleapis.com/trace\":\"trace_id\"}\n";
    assertThat(output).isEqualTo(expected);
  }

  @Test
  void testSuccess_logLevel() {
    logger.atSevere().log("Something went terribly wrong");
    handler.close();
    String output = ostream.toString(StandardCharsets.US_ASCII);
    assertThat(output)
        .isEqualTo(makeJson("ERROR", 100, "testSuccess_logLevel", "Something went terribly wrong"));
  }

  @Test
  void testSuccess_withCause() {
    logger.atSevere().withCause(new RuntimeException("boom!")).log("Something went terribly wrong");
    handler.close();
    String output = ostream.toString(StandardCharsets.US_ASCII);
    String prefix =
        makeJson("ERROR", 109, "testSuccess_withCause", "Something went terribly wrong");
    // Remove the last two characters (}, \n) from the template as the actual output contains
    // the full stack trace.
    prefix = prefix.substring(0, prefix.length() - 2);
    prefix += ",\"stacktrace\":\"\\njava.lang.RuntimeException: boom!\\n";
    assertThat(output).startsWith(prefix);
  }

  @Test
  void testSuccess_withStackTrace() {
    logger.atSevere().withStackTrace(StackSize.FULL).log("Something is worth checking");
    handler.close();
    String output = ostream.toString(StandardCharsets.US_ASCII);
    String prefix =
        makeJson("ERROR", 123, "testSuccess_withStackTrace", "Something is worth checking");
    // Remove the last three characters (}, \n) from the template as the actual output contains
    // the full stack trace.
    prefix = prefix.substring(0, prefix.length() - 2);
    prefix += ",\"stacktrace\":\"\\ncom.google.common.flogger.LogSiteStackTrace: FULL\\n";
    assertThat(output).startsWith(prefix);
  }

  @Test
  void testSuccess_notFlogger() {
    jdkLogger.log(Level.INFO, "Something I have to say");
    handler.close();
    String output = ostream.toString(StandardCharsets.US_ASCII);
    // Only flogger populates the file and line fields.
    String expected =
        makeJson("INFO", 4321, "testSuccess_notFlogger", "Something I have to say")
            .replace("\"file\":\"GcpJsonFormatterTest.java\",", "")
            .replace("\"line\":\"4321\",", "");
    assertThat(output).isEqualTo(expected);
  }

  @Test
  void testSuccess_timeLimiter() throws Exception {
    TimeLimiter.create().callWithTimeout(this::logSomething, 1, TimeUnit.SECONDS);
    handler.close();
    String output = ostream.toString(StandardCharsets.US_ASCII);
    assertThat(output).isEqualTo(makeJson("INFO", 171, "logSomething", "Something I have to say"));
  }

  @Test
  void testSuccess_timeLimiter_traceId() throws Exception {
    GcpJsonFormatter.setCurrentTraceId("trace_id");
    TimeLimiter.create().callWithTimeout(this::logSomething, 1, TimeUnit.SECONDS);
    handler.close();
    String output = ostream.toString(StandardCharsets.US_ASCII);
    String expected = makeJson("INFO", 171, "logSomething", "Something I have to say");
    // Remove the last two characters (}, \n) from the template and add the trace ID.
    expected =
        expected.substring(0, expected.length() - 2)
            + ",\"logging.googleapis.com/trace\":\"trace_id\"}\n";
    assertThat(output).isEqualTo(expected);
  }

  private Void logSomething() {
    logger.atInfo().log("Something I have to say");
    return null;
  }
}
