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

package google.registry.security;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.CONTENT_DISPOSITION;
import static com.google.common.net.HttpHeaders.X_CONTENT_TYPE_OPTIONS;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static org.json.simple.JSONValue.writeJSONString;

import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

/**
 * Helper class for servlets that read or write JSON.
 *
 * @see JsonResponseHelper
 */
public final class JsonHttp {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** String prefixed to all JSON-like responses. */
  public static final String JSON_SAFETY_PREFIX = ")]}'\n";

  /**
   * Extracts a JSON object from a servlet request.
   *
   * @return JSON object or {@code null} on error, in which case servlet should return.
   * @throws IOException if we failed to read from {@code req}.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  public static Map<String, ?> read(HttpServletRequest req) throws IOException {
    if (!"POST".equals(req.getMethod())
        && !"PUT".equals(req.getMethod())) {
      logger.atWarning().log("JSON request payload only allowed for POST/PUT.");
      return null;
    }
    if (!JSON_UTF_8.is(MediaType.parse(req.getContentType()))) {
      logger.atWarning().log("Invalid JSON Content-Type: %s", req.getContentType());
      return null;
    }
    try (Reader jsonReader = req.getReader()) {
      try {
        return checkNotNull((Map<String, ?>) JSONValue.parseWithException(jsonReader));
      } catch (ParseException | NullPointerException | ClassCastException e) {
        logger.atWarning().withCause(e).log("Malformed JSON.");
        return null;
      }
    }
  }

  /**
   * Writes a JSON servlet response securely with a parser breaker.
   *
   * @throws IOException if we failed to write to {@code rsp}.
   */
  public static void write(HttpServletResponse rsp, Map<String, ?> jsonObject) throws IOException {
    checkNotNull(jsonObject);
    rsp.setContentType(JSON_UTF_8.toString());
    // This prevents IE from MIME-sniffing a response away from the declared Content-Type.
    rsp.setHeader(X_CONTENT_TYPE_OPTIONS, "nosniff");
    // This is a defense in depth that prevents browsers from trying to render the content of the
    // response, even if all else fails. It's basically another anti-sniffing mechanism in the sense
    // that if you hit this url directly, it would try to download the file instead of showing it.
    rsp.setHeader(CONTENT_DISPOSITION, "attachment");
    try (Writer writer = rsp.getWriter()) {
      writer.write(JSON_SAFETY_PREFIX);
      writeJSONString(jsonObject, writer);
    }
  }
}
