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

package google.registry.proxy;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link google.registry.proxy.GcpJsonFormatter}. */
class GcpJsonFormatterTest {

  private static final String LOGGER_NAME = "example.company.app.logger";
  private static final String SOURCE_CLASS_NAME = "example.company.app.component.Doer";
  private static final String SOURCE_METHOD_NAME = "doStuff";
  private static final String MESSAGE = "Something I have to say";

  private final GcpJsonFormatter formatter = new GcpJsonFormatter();
  private final LogRecord logRecord = new LogRecord(Level.WARNING, MESSAGE);

  private static String makeJson(String severity, String source, String message) {
    return "{"
        + Joiner.on(",")
            .join(
                makeJsonField("severity", severity),
                makeJsonField("source", source),
                makeJsonField("message", "\\n" + message))
        + "}\n";
  }

  private static String makeJsonField(String name, String content) {
    return Joiner.on(":").join(addQuoteAndReplaceNewline(name), addQuoteAndReplaceNewline(content));
  }

  private static String addQuoteAndReplaceNewline(String content) {
    // This quadruple escaping is hurting my eyes.
    return "\"" + content.replaceAll("\n", "\\\\n") + "\"";
  }

  @BeforeEach
  void beforeEach() {
    logRecord.setLoggerName(LOGGER_NAME);
  }

  @Test
  void testSuccess() {
    String actual = formatter.format(logRecord);
    String expected = makeJson("WARNING", LOGGER_NAME, MESSAGE);
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void testSuccess_sourceClassAndMethod() {
    logRecord.setSourceClassName(SOURCE_CLASS_NAME);
    logRecord.setSourceMethodName(SOURCE_METHOD_NAME);
    String actual = formatter.format(logRecord);
    String expected = makeJson("WARNING", SOURCE_CLASS_NAME + " " + SOURCE_METHOD_NAME, MESSAGE);
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void testSuccess_multilineMessage() {
    String multilineMessage = "First line message\nSecond line message\n";
    logRecord.setMessage(multilineMessage);
    String actual = formatter.format(logRecord);
    String expected = makeJson("WARNING", LOGGER_NAME, multilineMessage);
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void testSuccess_withCause() {
    Throwable throwable = new Throwable("Some reason");
    StackTraceElement[] stacktrace = {
      new StackTraceElement("class1", "method1", "file1", 5),
      new StackTraceElement("class2", "method2", "file2", 10),
    };
    String stacktraceString =
        "java.lang.Throwable: Some reason\\n"
            + "\\tat class1.method1(file1:5)\\n"
            + "\\tat class2.method2(file2:10)\\n";
    throwable.setStackTrace(stacktrace);
    logRecord.setThrown(throwable);
    String actual = formatter.format(logRecord);
    String expected = makeJson("WARNING", LOGGER_NAME, MESSAGE + "\\n" + stacktraceString);
    assertThat(actual).isEqualTo(expected);
  }
}
