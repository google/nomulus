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
import javax.annotation.Nullable;

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

  /** JSON field that stores the trace associated with the log entry, if any. */
  private static final String TRACE = "logging.googleapis.com/trace";

  /** JSON field that stores the parameters of the current request, if any. */
  private static final String HTTP_REQUEST = "httRequest";

  /** JSON field that contains the content, this will show up as the main entry in a log. */
  private static final String MESSAGE = "message";

  /**
   * JSON field that contains the stack trace, if any.
   *
   * <p>Note that this field is not part of the structured logging that stackdriver understands.
   * Normally we'd just append the stack trace to the message itself. However, for unclear reasons,
   * if we do that on GKE, the log entry containing a stack trace will be lumped together with
   * several following log entries and makes it hard to read.
   */
  private static final String STACKTRACE = "stacktrace";

  private static final String FILE = "file";

  private static final String FUNCTION = "function";

  private static final String LINE = "line";

  private static final Gson gson = new Gson();

  private static final ThreadLocal<String> traceId = new ThreadLocal<>();

  private static final ThreadLocal<HttpRequest> request = new ThreadLocal<>();

  /**
   * Set the Trace ID associated with any logging done by the current thread.
   *
   * @param id The traceID, in the form projects/[PROJECT_ID]/traces/[TRACE_ID]
   */
  public static void setCurrentTraceId(@Nullable String id) {
    if (id == null) {
      traceId.remove();
    } else {
      traceId.set(id);
    }
  }

  /**
   * Record the parameters of the current request.
   *
   * @see <a
   *     href="https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#httprequest">HttpRequest</a>
   */
  public static void setCurrentRequest(
      String requestMethod, String requestUrl, String userAgent, String protocol) {
    request.set(new HttpRequest(requestMethod, requestUrl, userAgent, protocol));
  }

  public static void unsetCurrentRequest() {
    request.remove();
  }

  /**
   * Get the Trace ID associated with any logging done by the current thread.
   *
   * @return id The traceID
   */
  public static String getCurrentTraceId() {
    return traceId.get();
  }

  @Override
  public String format(LogRecord record) {
    String severity = severityFor(record.getLevel());

    // The rest is mostly lifted from java.util.logging.SimpleFormatter.
    String throwable = "";
    if (record.getThrown() != null) {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      pw.println();
      record.getThrown().printStackTrace(pw);
      pw.close();
      throwable = sw.toString();
    }

    String message = formatMessage(record);

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

    Map<String, Object> json = new LinkedHashMap<>();
    json.put(SEVERITY, severity);
    json.put(SOURCE_LOCATION, sourceLocation);
    json.put(MESSAGE, message);
    if (!throwable.isEmpty()) {
      json.put(STACKTRACE, throwable);
    }
    if (traceId.get() != null) {
      json.put(TRACE, traceId.get());
    }
    if (request.get() != null) {
      json.put(HTTP_REQUEST, request.get());
    }
    // This trailing newline is required for the proxy because otherwise multiple logs might be
    // sent to Stackdriver together (due to the async nature of the proxy), and not parsed
    // correctly.
    return gson.toJson(json) + '\n';
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

  private record HttpRequest(
      String requestMethod, String requestUrl, String userAgent, String protocol) {}
}
