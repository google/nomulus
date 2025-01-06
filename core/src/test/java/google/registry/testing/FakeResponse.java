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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.unmodifiableMap;

import com.google.common.base.Throwables;
import com.google.common.net.MediaType;
import google.registry.request.Response;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joda.time.DateTime;

/** Fake implementation of {@link Response} for testing. */
public final class FakeResponse implements Response {

  private int status = 200;
  private MediaType contentType = MediaType.HTML_UTF_8;
  private String payload = "";
  private final Map<String, Object> headers = new HashMap<>();
  private boolean wasMutuallyExclusiveResponseSet;
  private String lastResponseStackTrace;

  private final StringWriter writer = new StringWriter();
  private PrintWriter printWriter = new PrintWriter(writer);

  private ArrayList<Cookie> cookies = new ArrayList<>();

  public int getStatus() {
    return status;
  }

  public MediaType getContentType() {
    return contentType;
  }

  public String getPayload() {
    return payload;
  }

  public Map<String, Object> getHeaders() {
    return unmodifiableMap(headers);
  }

  public StringWriter getStringWriter() {
    return writer;
  }

  @Override
  public void sendRedirect(String url) throws IOException {
    status = HttpServletResponse.SC_FOUND;
    this.payload = String.format("Redirected to %s", url);
  }

  @Override
  public void setStatus(int status) {
    checkArgument(status >= 100);
    this.status = status;
  }

  @Override
  public void setContentType(MediaType contentType) {
    checkArgument(
        payload.isEmpty(),
        "setContentType must be called before setPayload; payload is: %s",
        payload);
    this.contentType = checkNotNull(contentType);
  }

  @Override
  public void setPayload(String payload) {
    checkResponsePerformedOnce();
    this.payload = checkNotNull(payload);
  }

  @Override
  public void setHeader(String header, String value) {
    headers.put(checkNotNull(header), checkNotNull(value));
  }

  @Override
  public void setDateHeader(String header, DateTime timestamp) {
    headers.put(checkNotNull(header), checkNotNull(timestamp));
  }

  @Override
  public void addCookie(Cookie cookie) {
    cookies.add(cookie);
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    return printWriter;
  }

  public List<Cookie> getCookies() {
    return cookies;
  }

  private void checkResponsePerformedOnce() {
    checkState(
        !wasMutuallyExclusiveResponseSet,
        "Two responses were sent. Here's the previous call:\n%s",
        lastResponseStackTrace);
    wasMutuallyExclusiveResponseSet = true;
    lastResponseStackTrace = getStackTrace();
  }

  private static String getStackTrace() {
    try {
      throw new Exception();
    } catch (Exception e) {
      return Throwables.getStackTraceAsString(e);
    }
  }

}
