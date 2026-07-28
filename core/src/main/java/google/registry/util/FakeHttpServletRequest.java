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

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Simple fake {@link HttpServletRequest} for internal use. */
public class FakeHttpServletRequest implements HttpServletRequest {

  private final Map<String, String> headers = new HashMap<>();
  private final Map<String, Object> attributes = new HashMap<>();
  private String method = "POST";
  private String requestUri = "/_dr/epp";
  private final String serverName = "localhost";
  private byte[] body = new byte[0];
  private final String contentType = "application/epp+xml";

  public void setHeader(String name, String value) {
    headers.put(name, value);
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public void setRequestUri(String requestUri) {
    this.requestUri = requestUri;
  }

  public void setBody(byte[] body) {
    this.body = body.clone();
  }

  @Override
  public String getHeader(String name) {
    return headers.get(name);
  }

  @Override
  public Enumeration<String> getHeaders(String name) {
    String value = headers.get(name);
    return Collections.enumeration(
        value == null ? Collections.emptyList() : Collections.singletonList(value));
  }

  @Override
  public Enumeration<String> getHeaderNames() {
    return Collections.enumeration(headers.keySet());
  }

  @Override
  public int getIntHeader(String name) {
    String value = headers.get(name);
    if (value == null) {
      return -1;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          String.format("Invalid integer value for header '%s': %s", name, value), e);
    }
  }

  @Override
  public String getMethod() {
    return method;
  }

  @Override
  public String getRequestURI() {
    return requestUri;
  }

  @Override
  public String getServerName() {
    return serverName;
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public boolean isFinished() {
        return byteArrayInputStream.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {}

      @Override
      public int read() throws IOException {
        return byteArrayInputStream.read();
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        return byteArrayInputStream.read(b, off, len);
      }
    };
  }

  @Override
  public String getContentType() {
    return contentType;
  }

  // --- Implement other methods with defaults ---
  @Override
  public String getAuthType() {
    return null;
  }

  @Override
  public Cookie[] getCookies() {
    return new Cookie[0];
  }

  @Override
  public long getDateHeader(String name) {
    return -1;
  }

  @Override
  public String getPathInfo() {
    return null;
  }

  @Override
  public String getPathTranslated() {
    return null;
  }

  @Override
  public String getContextPath() {
    return "";
  }

  @Override
  public String getQueryString() {
    return null;
  }

  @Override
  public String getRemoteUser() {
    return null;
  }

  @Override
  public boolean isUserInRole(String role) {
    return false;
  }

  @Override
  public Principal getUserPrincipal() {
    return null;
  }

  @Override
  public String getRequestedSessionId() {
    return null;
  }

  @Override
  public StringBuffer getRequestURL() {
    return new StringBuffer(requestUri);
  }

  @Override
  public String getServletPath() {
    return "";
  }

  @Override
  public HttpSession getSession(boolean create) {
    return null;
  }

  @Override
  public HttpSession getSession() {
    return null;
  }

  @Override
  public String changeSessionId() {
    return null;
  }

  @Override
  public boolean isRequestedSessionIdValid() {
    return false;
  }

  @Override
  public boolean isRequestedSessionIdFromCookie() {
    return false;
  }

  @Override
  public boolean isRequestedSessionIdFromURL() {
    return false;
  }

  @Override
  public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
    return false;
  }

  @Override
  public void login(String username, String password) throws ServletException {}

  @Override
  public void logout() throws ServletException {}

  @Override
  public Collection<Part> getParts() throws IOException, ServletException {
    return null;
  }

  @Override
  public Part getPart(String name) throws IOException, ServletException {
    return null;
  }

  @Override
  public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass)
      throws IOException, ServletException {
    return null;
  }

  @Override
  public Object getAttribute(String name) {
    return attributes.get(name);
  }

  @Override
  public Enumeration<String> getAttributeNames() {
    return Collections.enumeration(attributes.keySet());
  }

  @Override
  public String getCharacterEncoding() {
    return "UTF-8";
  }

  @Override
  public void setCharacterEncoding(String env) throws UnsupportedEncodingException {}

  @Override
  public int getContentLength() {
    return body.length;
  }

  @Override
  public long getContentLengthLong() {
    return body.length;
  }

  @Override
  public String getParameter(String name) {
    return null;
  }

  @Override
  public Enumeration<String> getParameterNames() {
    return Collections.emptyEnumeration();
  }

  @Override
  public String[] getParameterValues(String name) {
    return null;
  }

  @Override
  public Map<String, String[]> getParameterMap() {
    return Collections.emptyMap();
  }

  @Override
  public String getProtocol() {
    return "HTTP/1.1";
  }

  @Override
  public String getScheme() {
    return "http";
  }

  @Override
  public int getServerPort() {
    return 8080;
  }

  @Override
  public BufferedReader getReader() throws IOException {
    return new BufferedReader(new InputStreamReader(getInputStream(), UTF_8));
  }

  @Override
  public String getRemoteAddr() {
    return "127.0.0.1";
  }

  @Override
  public String getRemoteHost() {
    return "localhost";
  }

  @Override
  public void setAttribute(String name, Object o) {
    attributes.put(name, o);
  }

  @Override
  public void removeAttribute(String name) {
    attributes.remove(name);
  }

  @Override
  public Locale getLocale() {
    return Locale.US;
  }

  @Override
  public Enumeration<Locale> getLocales() {
    return Collections.enumeration(Collections.singleton(Locale.US));
  }

  @Override
  public boolean isSecure() {
    return false;
  }

  @Override
  public RequestDispatcher getRequestDispatcher(String path) {
    return null;
  }

  @Override
  public int getRemotePort() {
    return 0;
  }

  @Override
  public String getLocalName() {
    return "localhost";
  }

  @Override
  public String getLocalAddr() {
    return "127.0.0.1";
  }

  @Override
  public int getLocalPort() {
    return 8080;
  }

  @Override
  public ServletContext getServletContext() {
    return null;
  }

  @Override
  public AsyncContext startAsync() throws IllegalStateException {
    return null;
  }

  @Override
  public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse)
      throws IllegalStateException {
    return null;
  }

  @Override
  public boolean isAsyncStarted() {
    return false;
  }

  @Override
  public boolean isAsyncSupported() {
    return false;
  }

  @Override
  public AsyncContext getAsyncContext() {
    return null;
  }

  @Override
  public DispatcherType getDispatcherType() {
    return DispatcherType.REQUEST;
  }

  @Override
  public String getRequestId() {
    return null;
  }

  @Override
  public String getProtocolRequestId() {
    return null;
  }

  @Override
  public ServletConnection getServletConnection() {
    return null;
  }
}
