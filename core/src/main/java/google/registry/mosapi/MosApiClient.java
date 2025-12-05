// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.mosapi;

import google.registry.config.RegistryConfig.Config;
import google.registry.mosapi.MosApiException.MosApiAuthorizationException;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Singleton
public class MosApiClient {

  private final OkHttpClient httpClient;
  private final String baseUrl;

  @Inject
  public MosApiClient(
      @Named("mosapiHttpClient") OkHttpClient httpClient,
      @Config("mosapiServiceUrl") String mosapiUrl,
      @Config("mosapiEntityType") String entityType) {
    this.httpClient = httpClient;
    // Pre-calculate base URL and validate it to fail fast on bad config
    String fullUrl = String.format("%s/%s", mosapiUrl, entityType);
    if (HttpUrl.parse(fullUrl) == null) {
      throw new IllegalArgumentException("Invalid MoSAPI Service URL configuration: " + fullUrl);
    }
    this.baseUrl = fullUrl;
  }

  /**
   * Sends a GET request to the specified MoSAPI endpoint.
   *
   * @param entityId The TLD or registrar ID the request is for.
   * @param endpoint The specific API endpoint path (e.g., "v2/monitoring/state").
   * @param params A map of query parameters to be URL-encoded and appended to the request.
   * @param headers A map of HTTP headers to be included in the request.
   * @return The {@link Response} from the server if the request is successful. <b>The caller is
   *     responsible for closing this response.</b>
   * @throws MosApiException if the request fails due to a network error or an unhandled HTTP
   *     status.
   * @throws MosApiAuthorizationException if the server returns a 401 Unauthorized status.
   */
  public Response sendGetRequest(
      String entityId, String endpoint, Map<String, String> params, Map<String, String> headers)
      throws MosApiException {
    HttpUrl url = buildUri(entityId, endpoint, params);
    Request.Builder requestBuilder = new Request.Builder().url(url).get();
    headers.forEach(requestBuilder::addHeader);
    try {
      Response response = httpClient.newCall(requestBuilder.build()).execute();
      return checkResponseForAuthError(response);
    } catch (RuntimeException | IOException e) {
      // Check if it's the specific authorization exception (re-thrown or caught here)
      if (e instanceof MosApiAuthorizationException) {
        throw (MosApiAuthorizationException) e;
      }
      // Otherwise, treat as a generic connection/API error
      throw new MosApiException("Error during GET request to " + url, e);
    }
  }

  /**
   * Sends a POST request to the specified MoSAPI endpoint.
   *
   * <p><b>Note:</b> This method is for future use. There are currently no MoSAPI endpoints in the
   * project scope that require a POST request.
   *
   * @param entityId The TLD or registrar ID the request is for.
   * @param endpoint The specific API endpoint path.
   * @param params A map of query parameters to be URL-encoded.
   * @param headers A map of HTTP headers to be included in the request.
   * @param body The request body to be sent with the POST request.
   * @return The {@link Response} from the server. <b>The caller is responsible for closing this
   *     response.</b>
   * @throws MosApiException if the request fails.
   * @throws MosApiAuthorizationException if the server returns a 401 Unauthorized status.
   */
  public Response sendPostRequest(
      String entityId,
      String endpoint,
      Map<String, String> params,
      Map<String, String> headers,
      String body)
      throws MosApiException {
    HttpUrl url = buildUri(entityId, endpoint, params);
    RequestBody requestBody = RequestBody.create(body, MediaType.parse("application/json"));

    Request.Builder requestBuilder = new Request.Builder().url(url).post(requestBody);
    headers.forEach(requestBuilder::addHeader);
    try {
      Response response = httpClient.newCall(requestBuilder.build()).execute();
      return checkResponseForAuthError(response);
    } catch (RuntimeException | IOException e) {
      // Check if it's the specific authorization exception (re-thrown or caught here)
      if (e instanceof MosApiAuthorizationException) {
        throw (MosApiAuthorizationException) e;
      }
      // Otherwise, treat as a generic connection/API error
      throw new MosApiException("Error during POST request to " + url, e);
    }
  }

  private Response checkResponseForAuthError(Response response)
      throws MosApiAuthorizationException {
    if (response.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
      response.close();
      throw new MosApiAuthorizationException(
          "Authorization failed for the requested resource. The client certificate may not be"
              + " authorized for the specified TLD or Registrar.");
    }
    return response;
  }

  /**
   * Builds the full URL for a request, including the base URL, entityId, path, and query params.
   */
  private HttpUrl buildUri(String entityId, String path, Map<String, String> queryParams) {
    String sanitizedPath = path.startsWith("/") ? path.substring(1) : path;

    // We can safely use get() here because we validated baseUrl in the constructor
    HttpUrl.Builder urlBuilder =
        HttpUrl.get(baseUrl).newBuilder().addPathSegment(entityId).addPathSegments(sanitizedPath);

    if (queryParams != null) {
      queryParams.forEach(urlBuilder::addQueryParameter);
    }
    return urlBuilder.build();
  }
}
