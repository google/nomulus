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

package google.registry.request;

import com.google.common.net.MediaType;
import jakarta.inject.Inject;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import org.joda.time.DateTime;

/** HTTP response object. */
public final class ResponseImpl implements Response {

  private final HttpServletResponse rsp;

  @Inject
  public ResponseImpl(HttpServletResponse rsp) {
    this.rsp = rsp;
  }

  @Override
  public void sendRedirect(String url) throws IOException {
    rsp.sendRedirect(url);
  }

  @Override
  public void setStatus(int status) {
    rsp.setStatus(status);
  }

  @Override
  public void setContentType(MediaType contentType) {
    rsp.setContentType(contentType.toString());
  }

  @Override
  public void setPayload(String payload) {
    try {
      rsp.getWriter().write(payload);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void setHeader(String header, String value) {
    rsp.setHeader(header, value);
  }

  @Override
  public void setDateHeader(String header, DateTime timestamp) {
    rsp.setDateHeader(header, timestamp.getMillis());
  }

  @Override
  public void addCookie(Cookie cookie) {
    rsp.addCookie(cookie);
  }

  @Override
  public PrintWriter getWriter() throws IOException {
    return rsp.getWriter();
  }
}
