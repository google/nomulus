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

package google.registry.request;

import com.google.common.net.MediaType;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import org.joda.time.DateTime;

/**
 * HTTP request response object.
 *
 * @see ResponseImpl
 */
public interface Response {

  void sendRedirect(String url) throws IOException;

  /** Sets the HTTP status code. */
  void setStatus(int status);

  /** Sets the HTTP Content-Type and possibly encoding. */
  void setContentType(MediaType contentType);

  /**
   * Writes the HTTP payload.
   *
   * @throws IllegalStateException if you've already written the payload
   */
  void setPayload(String payload);

  /**
   * Writes an HTTP header to the response.
   *
   * @see HttpServletResponse#setHeader(String, String)
   */
  void setHeader(String header, String value);

  /**
   * Writes an HTTP header with a timestamp value.
   *
   * @see HttpServletResponse#setDateHeader(String, long)
   */
  void setDateHeader(String header, DateTime timestamp);

  /**
   * Adds a cookie to the response
   *
   * @see HttpServletResponse#addCookie(Cookie)
   */
  void addCookie(Cookie cookie);

  PrintWriter getWriter() throws IOException;
}
