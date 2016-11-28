// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.base.Suppliers.memoize;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.HttpHeaders.X_REQUESTED_WITH;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static google.registry.security.JsonHttp.JSON_SAFETY_PREFIX;
import static google.registry.security.XsrfTokenManager.X_CSRF_TOKEN;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import com.google.common.net.HostAndPort;
import com.google.common.net.MediaType;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import google.registry.config.RegistryEnvironment;
import google.registry.security.XsrfTokenManager;
import google.registry.tools.ServerSideCommand.Connection;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.Map.Entry;
import org.json.simple.JSONValue;

/** An http connection to the appengine server. */
@Parameters(separators = " =")
class AppEngineConnection implements Connection {

  /** Pattern to heuristically extract title tag contents in HTML responses. */
  private static final Pattern HTML_TITLE_TAG_PATTERN = Pattern.compile("<title>(.*?)</title>");


  @Parameter(
      names = "--server",
      description = "HOST[:PORT] to which remote commands are sent.")
  private HostAndPort server = RegistryEnvironment.get().config().getServer();


  /**
   * Memoized XSRF security token.
   *
   * <p>Computing this is expensive since it needs to load {@code ServerSecret} so do it once.
   */
  private final Supplier<String> xsrfToken =
      memoize(new Supplier<String>() {
        @Override
        public String get() {
          return XsrfTokenManager.generateToken("admin", getUserId());
        }});

  @Override
  public void prefetchXsrfToken() throws IOException {
    // Cause XSRF token to be fetched, and then stay resident in cache (since it's memoized).
    xsrfToken.get();
  }

  /** Returns the contents of the title tag in the given HTML, or null if not found. */
  private static String extractHtmlTitle(String html) {
    Matcher matcher = HTML_TITLE_TAG_PATTERN.matcher(html);
    return (matcher.find() ? matcher.group(1) : null);
  }

  /** Returns the HTML from the connection error stream, if any, otherwise the empty string. */
  private static String getErrorHtmlAsString(HttpURLConnection connection) throws IOException {
    return connection.getErrorStream() != null
        ? CharStreams.toString(new InputStreamReader(connection.getErrorStream(), UTF_8))
        : "";
  }

  @Override
  public String send(
      String endpoint, Map<String, ?> params, MediaType contentType, byte[] payload)
          throws IOException {
    HttpURLConnection connection = getHttpURLConnection(
        new URL(String.format("%s%s?%s", getServerUrl(), endpoint, encodeParams(params))));
    connection.setRequestMethod("POST");
    // Disable following redirects, which we shouldn't normally encounter.
    connection.setInstanceFollowRedirects(false);
    connection.setUseCaches(false);
    connection.setRequestProperty(CONTENT_TYPE, contentType.toString());
    connection.setRequestProperty(X_CSRF_TOKEN, xsrfToken.get());
    connection.setRequestProperty(X_REQUESTED_WITH, "RegistryTool");
    connection.setDoOutput(true);
    connection.connect();
    try (OutputStream output = connection.getOutputStream()) {
      output.write(payload);
    }
    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
      String errorTitle = extractHtmlTitle(getErrorHtmlAsString(connection));
      throw new IOException(String.format(
          "Error from %s: %d %s%s",
          connection.getURL(),
          connection.getResponseCode(),
          connection.getResponseMessage(),
          (errorTitle == null ? "" : ": " + errorTitle)));
    }
    return CharStreams.toString(new InputStreamReader(connection.getInputStream(), UTF_8));
  }

  private String encodeParams(Map<String, ?> params) {
    return Joiner.on('&').join(Iterables.transform(
        params.entrySet(),
        new Function<Entry<String, ?>, String>() {
          @Override
          public String apply(Entry<String, ?> entry) {
            try {
              return entry.getKey()
                  + "=" + URLEncoder.encode(entry.getValue().toString(), UTF_8.name());
            } catch (Exception e) {  // UnsupportedEncodingException
              throw new RuntimeException(e);
            }
          }}));
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> sendJson(String endpoint, Map<String, ?> object) throws IOException {
    String response = send(
        endpoint,
        ImmutableMap.<String, Object>of(),
        JSON_UTF_8,
        JSONValue.toJSONString(object).getBytes(UTF_8));
    return (Map<String, Object>) JSONValue.parse(response.substring(JSON_SAFETY_PREFIX.length()));
  }

  private HttpURLConnection getHttpURLConnection(URL remoteUrl) throws IOException {
    // TODO(b/28219927): Figure out authentication.
    return (HttpURLConnection) remoteUrl.openConnection();
  }

  @Override
  public String getServerUrl() {
    return (isLocalhost() ? "http://" : "https://") + getServer().toString();
  }

  HostAndPort getServer() {
    return server.withDefaultPort(443);  // Default to HTTPS port if unspecified.
  }

  boolean isLocalhost() {
    return server.getHostText().equals("localhost");
  }

  /* 
   * MERC-365  Relaxing security on repository tool server side commands
   * 
   * No User is used to reconstruct the XSRF token while processing server 
   * side command in GAE.
   */
  private String getUserId() {
    return isLocalhost()
        ? UserIdProvider.getTestUserId()
        : getEmptyAsNoUser();
  }
  
  private String getEmptyAsNoUser() {
    return ""; 
  }
}
