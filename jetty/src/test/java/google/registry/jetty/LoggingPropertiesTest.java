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

package google.registry.jetty;

import static com.google.cloud.logging.TraceLoggingEnhancer.setCurrentTraceId;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Splitter;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Unit tests for verifying the `logging.properties` used by the Nomulus image.
 *
 * <p>The path to the property file is set as a system property by the Gradle test task. See the
 * `jetty/build.gradle` script for more information.
 */
class LoggingPropertiesTest {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private PrintStream origStdout;

  private ByteArrayOutputStream stdout;

  @BeforeEach
  void setup() {
    origStdout = System.out;
    stdout = new ByteArrayOutputStream();
    System.setOut(new PrintStream(stdout));
    setCurrentTraceId("my custom trace id");
  }

  @AfterEach
  void teardown() {
    System.setOut(origStdout);
    setCurrentTraceId(null);
  }

  @Test
  void success_messageLogged_withTraceIdAndSourceLocation(TestInfo testInfo) {
    logger.atInfo().log("My log message.");
    String logs = stdout.toString(UTF_8);
    Optional<String> log =
        Splitter.on('\n')
            .splitToStream(logs)
            .filter(line -> line.contains("My log message."))
            .findAny();
    assertThat(log).isPresent();
    Map<String, String> logRecord = new Gson().fromJson(log.get(), Map.class);
    assertThat(logRecord).containsEntry("severity", "INFO");
    assertThat(logRecord).containsEntry("logging.googleapis.com/trace", "my custom trace id");
    assertThat(logRecord).containsKey("message");
    assertThat(logRecord.get("message")).contains("My log message.");
    // Verify that log contains full log site info: `{class_name} {method_name} line:{line_number}`
    assertThat(logRecord.get("message"))
        .containsMatch(
            String.format(
                "%s %s line:\\d+",
                LoggingPropertiesTest.class.getName(), testInfo.getTestMethod().get().getName()));
  }
}
