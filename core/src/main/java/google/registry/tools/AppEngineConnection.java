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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.X_REQUESTED_WITH;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static google.registry.security.JsonHttp.JSON_SAFETY_PREFIX;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import com.google.common.net.MediaType;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import google.registry.config.RegistryConfig;
import google.registry.request.Action.Service;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.json.simple.JSONValue;

/**
 * An http connection to an appengine server.
 *
 * <p>By default - connects to the TOOLS service. To create a Connection to another service, call
 * the {@link #withService} function.
 */
class AppEngineConnection {

  /** Pattern to heuristically extract title tag contents in HTML responses. */
  private static final Pattern HTML_TITLE_TAG_PATTERN = Pattern.compile("<title>(.*?)</title>");

  @Inject HttpRequestFactory requestFactory;
  private final Service service;

  @Inject
  AppEngineConnection() {
    service = Service.TOOLS;
  }

  private AppEngineConnection(Service service, HttpRequestFactory requestFactory) {
    this.service = service;
    this.requestFactory = requestFactory;
  }

  /** Returns a copy of this connection that talks to a different service. */
  public AppEngineConnection withService(Service service) {
    return new AppEngineConnection(service, requestFactory);
  }

  /** Returns the contents of the title tag in the given HTML, or null if not found. */
  private static String extractHtmlTitle(String html) {
    Matcher matcher = HTML_TITLE_TAG_PATTERN.matcher(html);
    return (matcher.find() ? matcher.group(1) : null);
  }

  /** Returns the HTML from the connection error stream, if any, otherwise the empty string. */
  private static String getErrorHtmlAsString(HttpResponse response) throws IOException {
    return CharStreams.toString(new InputStreamReader(response.getContent(), UTF_8));
  }

  private String internalSend(
      String endpoint, Map<String, ?> params, MediaType contentType, @Nullable byte[] payload)
      throws IOException {
    GenericUrl url = new GenericUrl(String.format("%s%s", getServer(), endpoint));
    url.putAll(params);
    HttpRequest request =
        (payload != null)
            ? requestFactory.buildPostRequest(
                url, new ByteArrayContent(contentType.toString(), payload))
            : requestFactory.buildGetRequest(url);
    HttpHeaders headers = request.getHeaders();
    headers.setCacheControl("no-cache");
    headers.put(X_REQUESTED_WITH, ImmutableList.of("RegistryTool"));
    request.setHeaders(headers);
    request.setFollowRedirects(false);
    request.setThrowExceptionOnExecuteError(false);
    request.setUnsuccessfulResponseHandler(
        (request1, response, supportsRetry) -> {
          String errorTitle = extractHtmlTitle(getErrorHtmlAsString(response));
          throw new IOException(
              String.format(
                  "Error from %s: %d %s%s",
                  request1.getUrl().toString(),
                  response.getStatusCode(),
                  response.getStatusMessage(),
                  (errorTitle == null ? "" : ": " + errorTitle)));
        });
    HttpResponse response = null;
    try {
      response = request.execute();
      return CharStreams.toString(new InputStreamReader(response.getContent(), UTF_8));
    } finally {
      if (response != null) {
        response.disconnect();
      }
    }
  }

  public String sendPostRequest(
      String endpoint, Map<String, ?> params, MediaType contentType, byte[] payload)
      throws IOException {
    return internalSend(endpoint, params, contentType, checkNotNull(payload, "payload"));
  }

  public String sendGetRequest(String endpoint, Map<String, ?> params) throws IOException {
    return internalSend(endpoint, params, MediaType.PLAIN_TEXT_UTF_8, null);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> sendJson(String endpoint, Map<String, ?> object) throws IOException {
    String response =
        sendPostRequest(
            endpoint,
            ImmutableMap.of(),
            JSON_UTF_8,
            JSONValue.toJSONString(object).getBytes(UTF_8));
    return (Map<String, Object>) JSONValue.parse(response.substring(JSON_SAFETY_PREFIX.length()));
  }

  public URL getServer() {
    switch (service) {
      case DEFAULT:
        return RegistryConfig.getDefaultServer();
      case TOOLS:
        return RegistryConfig.getToolsServer();
      case BACKEND:
        return RegistryConfig.getBackendServer();
      case PUBAPI:
        return RegistryConfig.getPubapiServer();
    }
    throw new IllegalStateException("Unknown service: " + service);
  }
}
