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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.mosapi.MosApiException.MosApiAuthorizationException;
import java.io.IOException;
import java.util.Map;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class MosApiClientTest {
  private static final String SERVICE_URL = "https://mosapi.example.com/v1";
  private static final String ENTITY_TYPE = "registries";

  // Mocks
  private OkHttpClient mockHttpClient;
  private Call mockCall;

  private MosApiClient mosApiClient;

  @BeforeEach
  void setUp() {
    mockHttpClient = mock(OkHttpClient.class);
    mockCall = mock(Call.class);
    when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
    mosApiClient = new MosApiClient(mockHttpClient, SERVICE_URL, ENTITY_TYPE);
  }

  @Test
  void testConstructor_throwsOnInvalidUrl() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new MosApiClient(mockHttpClient, "ht tp://bad-url", ENTITY_TYPE));
    assertThat(thrown).hasMessageThat().contains("Invalid MoSAPI Service URL");
  }

  // --- GET Request Tests ---

  @Test
  void testSendGetRequest_success() throws Exception {
    // 1. Prepare Success Response
    Response successResponse = createResponse(200, "{\"status\":\"ok\"}");
    when(mockCall.execute()).thenReturn(successResponse);

    Map<String, String> params = ImmutableMap.of("since", "2024-01-01");
    Map<String, String> headers = ImmutableMap.of("Authorization", "Bearer token123");

    // 2. Execute
    try (Response response =
        mosApiClient.sendGetRequest("tld-1", "monitoring/state", params, headers)) {

      // Verify Response
      assertThat(response.isSuccessful()).isTrue();
      assertThat(response.body().string()).isEqualTo("{\"status\":\"ok\"}");

      // 3. Verify Request Construction
      ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
      verify(mockHttpClient).newCall(requestCaptor.capture());
      Request capturedRequest = requestCaptor.getValue();

      // Check URL:
      assertThat(capturedRequest.method()).isEqualTo("GET");
      assertThat(capturedRequest.url().encodedPath())
          .isEqualTo("/v1/registries/tld-1/monitoring/state");
      assertThat(capturedRequest.url().queryParameter("since")).isEqualTo("2024-01-01");

      // Check Headers
      assertThat(capturedRequest.header("Authorization")).isEqualTo("Bearer token123");
    }
  }

  @Test
  void testSendGetRequest_throwsOn401() throws IOException {
    // Prepare 401 Response
    Response unauthorizedResponse = createResponse(401, "Unauthorized");
    when(mockCall.execute()).thenReturn(unauthorizedResponse);

    MosApiAuthorizationException thrown =
        assertThrows(
            MosApiAuthorizationException.class,
            () ->
                mosApiClient.sendGetRequest("tld-1", "path", ImmutableMap.of(), ImmutableMap.of()));

    assertThat(thrown).hasMessageThat().contains("Authorization failed");
  }

  @Test
  void testSendGetRequest_wrapsIoException() throws IOException {
    // Simulate Network Failure
    when(mockCall.execute()).thenThrow(new IOException("Network error"));

    // Execute & Assert
    MosApiException thrown =
        assertThrows(
            MosApiException.class,
            () ->
                mosApiClient.sendGetRequest("tld-1", "path", ImmutableMap.of(), ImmutableMap.of()));

    assertThat(thrown).hasMessageThat().contains("Error during GET request to");
    assertThat(thrown).hasCauseThat().isInstanceOf(IOException.class);
  }

  // --- POST Request Tests ---

  @Test
  void testSendPostRequest_success() throws Exception {
    // 1. Prepare Response
    Response successResponse = createResponse(200, "{\"updated\":true}");
    when(mockCall.execute()).thenReturn(successResponse);

    String requestBody = "{\"data\":\"update\"}";
    Map<String, String> headers = ImmutableMap.of("Content-Type", "application/json");

    try (Response response =
        mosApiClient.sendPostRequest("tld-1", "update", null, headers, requestBody)) {

      assertThat(response.isSuccessful()).isTrue();

      ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
      verify(mockHttpClient).newCall(requestCaptor.capture());
      Request capturedRequest = requestCaptor.getValue();

      assertThat(capturedRequest.method()).isEqualTo("POST");
      assertThat(capturedRequest.url().encodedPath()).isEqualTo("/v1/registries/tld-1/update");

      // Verify Body content
      Buffer buffer = new Buffer();
      capturedRequest.body().writeTo(buffer);
      assertThat(buffer.readUtf8()).isEqualTo(requestBody);
    }
  }

  @Test
  void testSendPostRequest_throwsOn401() throws IOException {
    // Prepare 401 Response
    Response unauthorizedResponse = createResponse(401, "Unauthorized");
    when(mockCall.execute()).thenReturn(unauthorizedResponse);

    MosApiAuthorizationException thrown =
        assertThrows(
            MosApiAuthorizationException.class,
            () ->
                mosApiClient.sendPostRequest(
                    "tld-1", "path", ImmutableMap.of(), ImmutableMap.of(), "{}"));

    assertThat(thrown).hasMessageThat().contains("Authorization failed");
  }

  @Test
  void testSendPostRequest_wrapsIoException() throws IOException {
    // Simulate Network Failure
    when(mockCall.execute()).thenThrow(new IOException("Network error"));
    MosApiException thrown =
        assertThrows(
            MosApiException.class,
            () ->
                mosApiClient.sendPostRequest(
                    "tld-1", "path", ImmutableMap.of(), ImmutableMap.of(), "{}"));
    assertThat(thrown).hasMessageThat().contains("Error during POST request to");
    assertThat(thrown).hasCauseThat().isInstanceOf(IOException.class);
  }

  /** Helper to build a real OkHttp Response object manually. */
  private Response createResponse(int code, String bodyContent) {
    return new Response.Builder()
        .request(new Request.Builder().url("http://localhost/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Msg")
        .body(ResponseBody.create(bodyContent, MediaType.parse("application/json")))
        .build();
  }
}
