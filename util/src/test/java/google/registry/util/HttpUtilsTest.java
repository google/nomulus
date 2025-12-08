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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import org.apache.http.HttpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class HttpUtilsTest {
  private static final String TEST_URL = "https://example.com/test";

  @Mock private HttpClient mockHttpClient;
  @Mock private HttpResponse<String> mockHttpResponse;
  @Captor private ArgumentCaptor<HttpRequest> httpRequestCaptor;

  @Test
  void sendGetRequest_noHeaders_success() throws IOException, InterruptedException {
    when(mockHttpClient.send(httpRequestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockHttpResponse);

    HttpResponse<String> response = HttpUtils.sendGetRequest(mockHttpClient, TEST_URL);

    assertThat(response).isSameInstanceAs(mockHttpResponse);
    HttpRequest request = httpRequestCaptor.getValue();

    assertThat(request.uri()).isEqualTo(URI.create(TEST_URL));
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.headers().map()).isEmpty();
  }

  @Test
  void sendGetRequest_withHeaders_success() throws IOException, InterruptedException {
    when(mockHttpClient.send(httpRequestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockHttpResponse);
    Map<String, String> headers =
        ImmutableMap.of("Auth", "Bearer token", "Content-Type", "application/json");

    HttpResponse<String> response = HttpUtils.sendGetRequest(mockHttpClient, TEST_URL, headers);

    assertThat(response).isSameInstanceAs(mockHttpResponse);
    HttpRequest request = httpRequestCaptor.getValue();

    assertThat(request.uri()).isEqualTo(URI.create(TEST_URL));
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.headers().firstValue("Auth")).hasValue("Bearer token");
    assertThat(request.headers().firstValue("Content-Type")).hasValue("application/json");
  }

  @Test
  void sendPostRequest_noBody_noHeaders_success()
      throws HttpException, IOException, InterruptedException {
    when(mockHttpClient.send(httpRequestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockHttpResponse);

    HttpResponse<String> response = HttpUtils.sendPostRequest(mockHttpClient, TEST_URL);

    assertThat(response).isSameInstanceAs(mockHttpResponse);
    HttpRequest request = httpRequestCaptor.getValue();

    assertThat(request.uri()).isEqualTo(URI.create(TEST_URL));
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.headers().map()).isEmpty();
    assertThat(request.bodyPublisher()).isPresent();
    assertThat(request.bodyPublisher().get().contentLength()).isEqualTo(0);
  }

  @Test
  void sendPostRequest_noBody_withHeaders_success() throws IOException, InterruptedException {
    when(mockHttpClient.send(httpRequestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockHttpResponse);
    Map<String, String> headers = ImmutableMap.of("X-Request-ID", "12345");

    HttpResponse<String> response = HttpUtils.sendPostRequest(mockHttpClient, TEST_URL, headers);

    assertThat(response).isSameInstanceAs(mockHttpResponse);
    HttpRequest request = httpRequestCaptor.getValue();

    assertThat(request.uri()).isEqualTo(URI.create(TEST_URL));
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.headers().firstValue("X-Request-ID")).hasValue("12345");
    assertThat(request.bodyPublisher()).isPresent();
    assertThat(request.bodyPublisher().get().contentLength()).isEqualTo(0);
  }

  @Test
  void sendPostRequest_withBody_success() throws IOException, InterruptedException {
    when(mockHttpClient.send(httpRequestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockHttpResponse);
    Map<String, String> headers = ImmutableMap.of("Content-Type", "application/json");
    String body = "{\"key\":\"value\"}";

    HttpResponse<String> response =
        HttpUtils.sendPostRequest(mockHttpClient, TEST_URL, headers, body);

    assertThat(response).isSameInstanceAs(mockHttpResponse);
    HttpRequest request = httpRequestCaptor.getValue();

    assertThat(request.uri()).isEqualTo(URI.create(TEST_URL));
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.headers().firstValue("Content-Type")).hasValue("application/json");
    assertThat(request.bodyPublisher()).isPresent();
    // Corrected line with StandardCharsets.UTF_8
    assertThat(request.bodyPublisher().get().contentLength())
        .isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
  }

  @Test
  void sendPostRequest_withNullBody_success() throws IOException, InterruptedException {
    when(mockHttpClient.send(httpRequestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockHttpResponse);
    Map<String, String> headers = ImmutableMap.of("X-Request-ID", "abc");

    HttpResponse<String> response =
        HttpUtils.sendPostRequest(mockHttpClient, TEST_URL, headers, null);

    assertThat(response).isSameInstanceAs(mockHttpResponse);
    HttpRequest request = httpRequestCaptor.getValue();

    assertThat(request.uri()).isEqualTo(URI.create(TEST_URL));
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.headers().firstValue("X-Request-ID")).hasValue("abc");
    assertThat(request.bodyPublisher()).isPresent();
    assertThat(request.bodyPublisher().get().contentLength()).isEqualTo(0);
  }

  @Test
  void sendPostRequest_withEmptyBody_success() throws IOException, InterruptedException {
    when(mockHttpClient.send(httpRequestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockHttpResponse);
    Map<String, String> headers = Collections.emptyMap();

    HttpResponse<String> response =
        HttpUtils.sendPostRequest(mockHttpClient, TEST_URL, headers, "");

    assertThat(response).isSameInstanceAs(mockHttpResponse);
    HttpRequest request = httpRequestCaptor.getValue();

    assertThat(request.uri()).isEqualTo(URI.create(TEST_URL));
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.headers().map()).isEmpty();
    assertThat(request.bodyPublisher()).isPresent();
    assertThat(request.bodyPublisher().get().contentLength()).isEqualTo(0);
  }

  @Test
  void send_throwsIOException() throws IOException, InterruptedException {
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("Network failed"));

    assertThrows(
        IOException.class,
        () -> HttpUtils.sendGetRequest(mockHttpClient, TEST_URL, Collections.emptyMap()));

    assertThrows(
        IOException.class,
        () -> HttpUtils.sendPostRequest(mockHttpClient, TEST_URL, Collections.emptyMap(), "body"));
  }

  @Test
  void send_throwsInterruptedException() throws IOException, InterruptedException {
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new InterruptedException("Request cancelled"));

    assertThrows(
        InterruptedException.class,
        () -> HttpUtils.sendGetRequest(mockHttpClient, TEST_URL, Collections.emptyMap()));

    assertThrows(
        InterruptedException.class,
        () -> HttpUtils.sendPostRequest(mockHttpClient, TEST_URL, Collections.emptyMap(), "body"));
  }
}
