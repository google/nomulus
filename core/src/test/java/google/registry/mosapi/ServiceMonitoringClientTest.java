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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import google.registry.mosapi.MosApiModels.TldServiceState;
import google.registry.tools.GsonUtils;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ServiceMonitoringClientTest {

  private static final String TLD = "example";
  private static final String ENDPOINT = "v2/monitoring/state";
  private final MosApiClient mosApiClient = mock(MosApiClient.class);
  private final Gson gson = GsonUtils.provideGson();
  private ServiceMonitoringClient client;

  @BeforeEach
  void beforeEach() {
    client = new ServiceMonitoringClient(mosApiClient, gson);
  }

  @Test
  void testGetTldServiceState_success() throws Exception {
    String jsonResponse =
        """
        {
          "tld": "example",
          "services": [
            {
              "service": "DNS",
              "status": "OPERATIONAL"
            }
          ]
        }
        """;

    try (Response response = createMockResponse(200, jsonResponse)) {
      when(mosApiClient.sendGetRequest(eq(TLD), eq(ENDPOINT), anyMap(), anyMap()))
          .thenReturn(response);

      TldServiceState result = client.getTldServiceState(TLD);
      assertThat(gson.toJson(result)).contains("example");
    }
  }

  @Test
  void testGetTldServiceState_apiError_throwsMosApiException() throws Exception {
    String errorJson =
        """
        {
          "resultCode": "2011",
          "message": "Invalid duration"
        }
        """;

    try (Response response = createMockResponse(400, errorJson)) {
      when(mosApiClient.sendGetRequest(eq(TLD), eq(ENDPOINT), anyMap(), anyMap()))
          .thenReturn(response);

      MosApiException thrown =
          assertThrows(MosApiException.class, () -> client.getTldServiceState(TLD));
      assertThat(thrown.getMessage()).contains("2011");
      assertThat(thrown.getMessage()).contains("Invalid duration");
    }
  }

  @Test
  void testGetTldServiceState_nonJsonError_throwsMosApiException() throws Exception {
    String htmlError =
        """
        <html>
          <body>502 Bad Gateway</body>
        </html>
        """;

    try (Response response = createMockResponse(502, htmlError)) {
      when(mosApiClient.sendGetRequest(eq(TLD), eq(ENDPOINT), anyMap(), anyMap()))
          .thenReturn(response);

      MosApiException thrown =
          assertThrows(MosApiException.class, () -> client.getTldServiceState(TLD));
      assertThat(thrown.getMessage()).contains("MoSAPI json parsing error (502)");
      assertThat(thrown.getMessage()).contains("502 Bad Gateway");
    }
  }

  @Test
  void testGetTldServiceState_emptyBody_throwsMosApiException() throws Exception {
    Response response =
        new Response.Builder()
            .request(new Request.Builder().url("http://localhost").build())
            .protocol(Protocol.HTTP_1_1)
            .code(204)
            .message("No Content")
            .build();

    when(mosApiClient.sendGetRequest(eq(TLD), eq(ENDPOINT), anyMap(), anyMap()))
        .thenReturn(response);

    MosApiException thrown =
        assertThrows(MosApiException.class, () -> client.getTldServiceState(TLD));
    assertThat(thrown.getMessage()).contains("returned an empty body");
  }

  private Response createMockResponse(int code, String body) {
    return new Response.Builder()
        .request(new Request.Builder().url("http://localhost").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(code == 200 ? "OK" : "Error")
        .body(ResponseBody.create(body, MediaType.parse("application/json")))
        .build();
  }
}
