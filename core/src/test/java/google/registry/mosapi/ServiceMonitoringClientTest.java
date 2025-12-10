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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import google.registry.mosapi.model.TldServiceState;
import java.io.IOException;
import java.io.Reader;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ServiceMonitoringClientTest {

  private final MosApiClient mosApiClient = mock(MosApiClient.class);
  private final Gson gson = new Gson();
  private ServiceMonitoringClient client;

  @BeforeEach
  void setUp() {
    client = new ServiceMonitoringClient(mosApiClient, gson);
  }

  @Test
  void getTldServiceState_success() throws Exception {
    String json = "{ \"service\": \"RDAP\", \"status\": \"ACTIVE\" }";

    // FIX: Do not mock Response. Use a real instance via Builder.
    Response realResponse = createResponse(200, json);

    when(mosApiClient.sendGetRequest(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(realResponse);

    TldServiceState result = client.getTldServiceState("example");

    assertNotNull(result);
    // We don't verify(response).close() on real objects, the try-with-resources handles it.
  }

  @Test
  void getTldServiceState_apiErrorResponse_throwsMosApiException() throws Exception {
    String errorJson = "{ \"code\": 400, \"message\": \"Invalid TLD\" }";

    // FIX: Do not mock Response. Use a real instance via Builder.
    Response realResponse = createResponse(400, errorJson);

    when(mosApiClient.sendGetRequest(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(realResponse);

    MosApiException thrown = assertThrows(MosApiException.class, () -> {
      client.getTldServiceState("invalid");
    });

    // Optional: Assert on the exception message if your MosApiErrorResponse parsing logic sets it
  }

  @Test
  void getTldServiceState_malformedJson_throwsMosApiException() throws Exception {
    String garbage = "<html><body>Gateway Timeout</body></html>";
    Response realResponse = createResponse(200, garbage);

    when(mosApiClient.sendGetRequest(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(realResponse);

    MosApiException thrown = assertThrows(MosApiException.class, () -> {
      client.getTldServiceState("example");
    });

    assertEquals("Failed to parse TLD service state response", thrown.getMessage());
    assertEquals(JsonSyntaxException.class, thrown.getCause().getClass());
  }

  @Test
  void getTldServiceState_networkFailureDuringRead_throwsMosApiException() throws Exception {
    // Strategy: We need a REAL Response (to satisfy lint) but a MOCK Body (to force failure)

    // 1. Mock the internal pieces needed to cause a failure
    ResponseBody mockBody = mock(ResponseBody.class);
    Reader mockReader = mock(Reader.class);

    // 2. Wrap the Mock Body in a Real Response
    Response mixedResponse = new Response.Builder()
        .request(new Request.Builder().url("http://localhost/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(mockBody)
        .build();

    // 3. Set up the mocks
    when(mosApiClient.sendGetRequest(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(mixedResponse);
    when(mockBody.charStream()).thenReturn(mockReader);

    // Force the low-level IOException during read
    when(mockReader.read(any(char[].class), anyInt(), anyInt()))
        .thenThrow(new IOException("Network failure during read"));

    // 4. Act & Assert
    MosApiException thrown = assertThrows(MosApiException.class, () -> {
      client.getTldServiceState("example");
    });

    assertEquals("Failed to parse TLD service state response", thrown.getMessage());
    assertEquals("Network failure during read", thrown.getCause().getCause().getMessage());
  }

  /**
   * Helper to create a REAL OkHttp Response object.
   * This satisfies "Do not mock Response" rules while being easy to use.
   */
  private Response createResponse(int code, String jsonBody) {
    return new Response.Builder()
        .request(new Request.Builder().url("http://localhost/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(code == 200 ? "OK" : "Error")
        .body(ResponseBody.create(jsonBody, MediaType.get("application/json")))
        .build();
  }
}
