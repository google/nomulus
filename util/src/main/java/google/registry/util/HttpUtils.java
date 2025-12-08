// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.util;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.apache.http.HttpException;

public final class HttpUtils {
  /** Private constructor to prevent instantiation. */
  private HttpUtils() {}

  /**
   * Sends an HTTP GET request to the specified URL without any custom headers.
   *
   * @param httpClient the {@link HttpClient} to use for sending the request
   * @param url the target URL
   * @return the {@link HttpResponse} as a String
   */
  public static HttpResponse<String> sendGetRequest(HttpClient httpClient, String url)
      throws IOException, InterruptedException {
    return sendGetRequest(httpClient, url, ImmutableMap.of());
  }

  /**
   * Sends an HTTP GET request with custom headers to the specified URL.
   *
   * @param httpClient the {@link HttpClient} to use for sending the request
   * @param url the target URL
   * @param headers a {@link Map} of header keys and values to add to the request
   * @return the {@link HttpResponse} as a String
   * @throws IOException if an I/O error occurs
   * @throws InterruptedException if the request is interrupted
   */
  public static HttpResponse<String> sendGetRequest(
      HttpClient httpClient, String url, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(url)).GET();
    for (Map.Entry<String, String> header : headers.entrySet()) {
      requestBuilder.header(header.getKey(), header.getValue());
    }
    return send(httpClient, requestBuilder.build());
  }

  /**
   * Sends an HTTP POST request with an empty body to the specified URL.
   *
   * @param httpClient the {@link HttpClient} to use for sending the request
   * @param url the target URL
   * @return the {@link HttpResponse} as a String
   * @throws IOException if an I/O error occurs
   * @throws InterruptedException if the request is interrupted
   */
  public static HttpResponse<String> sendPostRequest(HttpClient httpClient, String url)
      throws HttpException, IOException, InterruptedException {
    return sendPostRequest(httpClient, url, ImmutableMap.of());
  }

  /**
   * Sends an HTTP POST request with an empty body and custom headers to the specified URL.
   *
   * @param httpClient the {@link HttpClient} to use for sending the request
   * @param url the target URL
   * @param headers a {@link Map} of header keys and values to add to the request
   * @return the {@link HttpResponse} as a String
   * @throws IOException if an I/O error occurs
   * @throws InterruptedException if the request is interrupted
   */
  public static HttpResponse<String> sendPostRequest(
      HttpClient httpClient, String url, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder().uri(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody());
    for (Map.Entry<String, String> header : headers.entrySet()) {
      requestBuilder.header(header.getKey(), header.getValue());
    }
    return send(httpClient, requestBuilder.build());
  }

  /**
   * Sends an HTTP POST request with a String body and custom headers to the specified URL.
   *
   * @param httpClient the {@link HttpClient} to use for sending the request
   * @param url the target URL
   * @param headers a {@link Map} of header keys and values to add to the request
   * @param body the String request body to send (can be null or empty for no body)
   * @return the {@link HttpResponse} as a String
   * @throws IOException if an I/O error occurs
   * @throws InterruptedException if the request is interrupted
   */
  public static HttpResponse<String> sendPostRequest(
      HttpClient httpClient, String url, Map<String, String> headers, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(URI.create(url));

    if (body == null || body.isEmpty()) {
      requestBuilder.POST(HttpRequest.BodyPublishers.noBody());
    } else {
      requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
    }

    for (Map.Entry<String, String> header : headers.entrySet()) {
      requestBuilder.header(header.getKey(), header.getValue());
    }
    return send(httpClient, requestBuilder.build());
  }

  /**
   * Sends a pre-built {@link HttpRequest} and handles exceptions.
   *
   * @param httpClient the client
   * @param request the request
   * @return the {@link HttpResponse}
   * @throws IOException if an I/O error occurs
   * @throws InterruptedException if the request is interrupted
   */
  private static HttpResponse<String> send(HttpClient httpClient, HttpRequest request)
      throws IOException, InterruptedException {
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
