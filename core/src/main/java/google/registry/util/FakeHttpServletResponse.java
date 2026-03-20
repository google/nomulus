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

package google.registry.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ArrayListMultimap;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Simple fake {@link HttpServletResponse} for internal use. */
public class FakeHttpServletResponse implements HttpServletResponse {

  private int status = SC_OK;
  private String contentType;
  private final ArrayListMultimap<String, String> headers = ArrayListMultimap.create();
  private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
  private final PrintWriter writer = new PrintWriter(outputStream, false, UTF_8);

  @Override
  public void setStatus(int sc) {
    this.status = sc;
  }

  @Override
  public int getStatus() {
    return status;
  }

  @Override
  public void setContentType(String type) {
    this.contentType = type;
  }

  @Override
  public String getContentType() {
    return contentType;
  }

  @Override
  public void setHeader(String name, String value) {
    headers.removeAll(name);
    headers.put(name, value);
  }

  @Override
  public void addHeader(String name, String value) {
    headers.put(name, value);
  }

  @Override
  public String getHeader(String name) {
    List<String> values = headers.get(name);
    return values.isEmpty() ? null : values.get(0);
  }

  @Override
  public Collection<String> getHeaders(String name) {
    return headers.get(name);
  }

  @Override
  public Collection<String> getHeaderNames() {
    return headers.keySet();
  }

  @Override
  public ServletOutputStream getOutputStream() throws IOException {
    return new ServletOutputStream() {
      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setWriteListener(WriteListener writeListener) {}

      @Override
      public void write(int b) throws IOException {
        outputStream.write(b);
      }
    };
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    return writer;
  }

  public byte[] getPayload() {
    writer.flush();
    return outputStream.toByteArray();
  }

  // --- Implement other methods with defaults ---
  @Override
  public void addCookie(Cookie cookie) {}

  @Override
  public boolean containsHeader(String name) {
    return headers.containsKey(name);
  }

  @Override
  public String encodeURL(String url) {
    return url;
  }

  @Override
  public String encodeRedirectURL(String url) {
    return url;
  }

  @Override
  public void sendError(int sc, String msg) throws IOException {
    this.status = sc;
  }

  @Override
  public void sendError(int sc) throws IOException {
    this.status = sc;
  }

  @Override
  public void sendRedirect(String location) throws IOException {
    this.status = SC_FOUND;
  }

  @Override
  public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
    this.status = sc;
  }

  @Override
  public void setDateHeader(String name, long date) {}

  @Override
  public void addDateHeader(String name, long date) {}

  @Override
  public void setIntHeader(String name, int value) {}

  @Override
  public void addIntHeader(String name, int value) {}

  @Override
  public void setCharacterEncoding(String charset) {}

  @Override
  public void setContentLength(int len) {}

  @Override
  public void setContentLengthLong(long len) {}

  @Override
  public void setBufferSize(int size) {}

  @Override
  public int getBufferSize() {
    return 0;
  }

  @Override
  public void flushBuffer() throws IOException {
    writer.flush();
  }

  @Override
  public void resetBuffer() {
    writer.flush();
    outputStream.reset();
  }

  @Override
  public boolean isCommitted() {
    return false;
  }

  @Override
  public void reset() {
    writer.flush();
    outputStream.reset();
    headers.clear();
    status = SC_OK;
  }

  @Override
  public void setLocale(Locale loc) {}

  @Override
  public Locale getLocale() {
    return Locale.US;
  }

  @Override
  public String getCharacterEncoding() {
    return "UTF-8";
  }

  @Override
  public void sendEarlyHints() {}
}
