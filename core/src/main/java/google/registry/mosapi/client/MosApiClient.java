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

package google.registry.mosapi.client;

import google.registry.config.RegistryConfig.Config;
import google.registry.mosapi.exception.MosApiException;
import google.registry.mosapi.exception.MosApiException.MosApiAuthorizationException;
import google.registry.util.HttpUtils;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

@Singleton
public class MosApiClient {

  private final HttpClient httpClient;
  private final String baseUrl;

  @Inject
  public MosApiClient(
      @Named("mosapiHttpClient") HttpClient httpClient,
      @Config("mosapiUrl") String mosapiUrl,
      @Config("entityType") String entityType) {
    this.httpClient = httpClient;
    this.baseUrl = String.format("%s/%s", mosapiUrl, entityType);
  }

  public HttpResponse<String> sendGetRequest(
      String entityId, String endpoint, Map<String, String> params, Map<String, String> headers)
      throws MosApiException {
    String url = buildUrl(entityId, endpoint, params);
    try {
      HttpResponse<String> response = HttpUtils.sendGetRequest(httpClient, url, headers);
      return checkResponseForAuthError(response);
    } catch (RuntimeException e) {
      throw new MosApiException("Error during GET request to " + url, e);
    }
  }

  /**
   * Sends a GET request and decompresses the response body if it is gzip-encoded.
   *
   * <p>Note that this method returns the response body directly as a {@code String} rather than the
   * full {@link HttpResponse} because constructing a new {@code HttpResponse} with the modified
   * (decompressed) body is overly complex. The status code is checked for common error conditions
   * before returning.
   */
  public HttpResponse<String> sendGetRequestWithDecompression(
      String entityId, String endpoint, Map<String, String> params, Map<String, String> headers)
      throws MosApiException {
    String url = buildUrl(entityId, endpoint, params);
    try {
      HttpResponse<String> response =
          HttpUtils.sendGetRequestWithDecompression(httpClient, url, headers);
      return checkResponseForAuthError(response);

    } catch (RuntimeException e) {
      throw new MosApiException("Error during GET request to " + url, e);
    }
  }

  public HttpResponse<String> sendPostRequest(
      String entityId,
      String endpoint,
      Map<String, String> params,
      Map<String, String> headers,
      String body)
      throws MosApiException {
    String url = buildUrl(entityId, endpoint, params);
    try {
      HttpResponse<String> response = HttpUtils.sendPostRequest(httpClient, url, headers, body);
      return checkResponseForAuthError(response);
    } catch (RuntimeException e) {
      throw new MosApiException("Error during POST request to " + url, e);
    }
  }

  private HttpResponse<String> checkResponseForAuthError(HttpResponse<String> response)
      throws MosApiAuthorizationException {
    if (response.statusCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
      throw new MosApiAuthorizationException(
          "Authorization failed for the requested resource. The client certificate may not be"
              + " authorized for the specified TLD or Registrar.");
    }
    return response;
  }

  /**
   * Builds the full URL for a request, including the base URL, entityId, path, and query params.
   */
  private String buildUrl(String entityId, String path, Map<String, String> queryParams) {
    String sanitizedPath = path.startsWith("/") ? path : "/" + path;
    String fullPath = "/" + entityId + sanitizedPath;

    if (queryParams == null || queryParams.isEmpty()) {
      return baseUrl + fullPath;
    }
    String queryString =
        queryParams.entrySet().stream()
            .map(
                entry ->
                    entry.getKey()
                        + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    return baseUrl + fullPath + "?" + queryString;
  }
}
