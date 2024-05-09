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

import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.LogSite;
import com.google.common.flogger.backend.LogData;
import com.google.common.flogger.backend.system.SimpleLogRecord;
import com.google.gson.Gson;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * JUL formatter that formats log messages in a single-line JSON that Stackdriver logging can parse.
 *
 * <p>The structured logs written to {@code STDOUT} and {@code STDERR} will be picked up by GAE/GKE
 * logging agent and automatically ingested by Stackdriver. Certain fields (see below) in the JSON
 * will be converted to the corresponding <a
 * href="https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry">{@code Log Entry}</a>
 * fields and parsed by Log Explorer.
 *
 * @see <a
 *     href="https://cloud.google.com/logging/docs/structured-logging#structured_logging_special_fields">
 *     Logging agent: special JSON fields</a>
 */
public class GcpJsonFormatter extends Formatter {

  /** JSON field that determines the log level. */
  private static final String SEVERITY = "severity";

  /**
   * JSON field that stores information regarding the source code location information associated
   * with the log entry, if any.
   */
  private static final String SOURCE_LOCATION = "logging.googleapis.com/sourceLocation";

  /** JSON field that contains the content, this will show up as the main entry in a log. */
  private static final String MESSAGE = "message";

  private static final String FILE = "file";

  private static final String FUNCTION = "function";

  private static final String LINE = "line";

  private static final Gson gson = new Gson();

  @Override
  public String format(LogRecord record) {
    // Add an extra newline before the message for better displaying of multi-line logs. To see the
    // correctly indented multi-line logs, expand the log and look for the testPayload field. This
    // newline makes sure that the entire message starts on its own line, so that indentation within
    // the message is correct.

    String message = '\n' + record.getMessage();
    String severity = severityFor(record.getLevel());

    // The rest is mostly lifted from java.util.logging.SimpleFormatter.
    String stacktrace = "";
    if (record.getThrown() != null) {
      StringWriter sw = new StringWriter();
      try (PrintWriter pw = new PrintWriter(sw)) {
        pw.println();
        record.getThrown().printStackTrace(pw);
      }
      stacktrace = sw.toString();
    }

    String function = "";
    if (record.getSourceClassName() != null) {
      function = record.getSourceClassName();
      if (record.getSourceMethodName() != null) {
        function += "." + record.getSourceMethodName();
      }
    }

    String line = "";
    String file = "";
    if (record instanceof SimpleLogRecord simpleLogRecord) {
      Optional<LogSite> logSite =
          Optional.ofNullable(simpleLogRecord.getLogData()).map(LogData::getLogSite);
      if (logSite.isPresent()) {
        line = String.valueOf(logSite.get().getLineNumber());
        file = logSite.get().getFileName();
      }
    }

    Map<String, String> sourceLocation = new LinkedHashMap<>();
    if (!file.isEmpty()) {
      sourceLocation.put(FILE, file);
    }
    if (!line.isEmpty()) {
      sourceLocation.put(LINE, line);
    }
    if (!function.isEmpty()) {
      sourceLocation.put(FUNCTION, function);
    }
    return gson.toJson(
            ImmutableMap.of(
                SEVERITY,
                severity,
                SOURCE_LOCATION,
                sourceLocation,
                // ImmutableMap.of(FILE, file, LINE, line, FUNCTION, function),
                MESSAGE,
                message + stacktrace))
        // This trailing newline is required for the proxy because otherwise multiple logs might be
        // sent to Stackdriver together (due to the async nature of the proxy), and not parsed
        // correctly.
        + '\n';
  }

  /**
   * Maps a {@link Level} to a severity string that Stackdriver understands.
   *
   * @see <a
   *     href="https://github.com/googleapis/java-logging/blob/main/google-cloud-logging/src/main/java/com/google/cloud/logging/LoggingHandler.java">
   *     LoggingHandler.java</a>
   */
  private static String severityFor(Level level) {
    return switch (level.intValue()) {
      case 300 -> "DEBUG"; // FINEST
      case 400 -> "DEBUG"; // FINER
      case 500 -> "DEBUG"; // FINE
      case 700 -> "INFO"; // CONFIG
      case 800 -> "INFO"; // INFO
      case 900 -> "WARNING"; // WARNING
      case 1000 -> "ERROR"; // SEVERE
      default -> "DEFAULT";
    };
  }
}
