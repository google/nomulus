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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.mosapi.dto.servicemonitoring.ServiceAlarm;
import google.registry.mosapi.dto.servicemonitoring.ServiceDowntime;
import google.registry.mosapi.dto.servicemonitoring.TldServiceState;
import google.registry.mosapi.exception.MosApiException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link ServiceMonitoringClient}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ServiceMonitoringClientTest {
  private static final String TLD = "example";
  private static final String SERVICE = "dns";

  @Mock private MosApiClient mosApiClient;
  @Mock private HttpResponse<String> httpResponse;

  private ServiceMonitoringClient client;

  @BeforeEach
  void beforeEach() throws MosApiException {
    client = new ServiceMonitoringClient(mosApiClient, new Gson());
    when(httpResponse.uri()).thenReturn(URI.create("https://mosapi.icann.org/v2/monitoring"));
    // Default: successful mock response for any GET request, specific tests override body/status
    when(mosApiClient.sendGetRequest(any(), any(), anyMap(), anyMap())).thenReturn(httpResponse);
  }

  @Test
  void getServiceState_success() throws MosApiException {
    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_OK);
    when(httpResponse.body())
        .thenReturn(
            """
            {
              "tld": "example",
              "status": "Up",
              "testedServices": []
            }
            """);

    TldServiceState result = client.getServiceState(TLD);

    assertThat(result).isNotNull();
    assertThat(result.getTld()).isEqualTo("example");
    assertThat(result.getStatus()).isEqualTo("Up");
    verify(mosApiClient)
        .sendGetRequest(
            eq(TLD), eq("v2/monitoring/state"), eq(ImmutableMap.of()), eq(ImmutableMap.of()));
  }

  @Test
  void getDowntime_success() throws MosApiException {
    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_OK);
    when(httpResponse.body())
        .thenReturn(
            """
            {
              "version": 2,
              "lastUpdateApiDatabase": 1000,
              "downtime": 45
            }
            """);

    ServiceDowntime result = client.getDowntime(TLD, SERVICE);

    assertThat(result.getDowntime()).isEqualTo(45);
    verify(mosApiClient)
        .sendGetRequest(
            eq(TLD),
            eq("v2/monitoring/dns/downtime"),
            eq(ImmutableMap.of()),
            eq(ImmutableMap.of()));
  }

  @Test
  void getDowntime_notFound_returnsDisabled() throws MosApiException {
    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_NOT_FOUND);
    when(httpResponse.body()).thenReturn("Not available");

    ServiceDowntime result = client.getDowntime(TLD, SERVICE);

    assertThat(result.getDowntime()).isEqualTo(0);
    assertThat(result.getDisabledMonitoring()).isTrue();
  }

  @Test
  void getDowntime_serverError_throwsException() {
    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_INTERNAL_ERROR);

    MosApiException thrown =
        assertThrows(MosApiException.class, () -> client.getDowntime(TLD, SERVICE));

    assertThat(thrown).hasMessageThat().contains("failed with status code 500");
  }

  @Test
  void serviceAlarmed_success() throws MosApiException {
    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_OK);
    when(httpResponse.body())
        .thenReturn(
            """
            {
              "version": 2,
              "lastUpdateApiDatabase": 1000,
              "alarmed": "Yes"
            }
            """);

    ServiceAlarm result = client.serviceAlarmed(TLD, SERVICE);

    assertThat(result.getAlarmed()).isEqualTo("Yes");
    verify(mosApiClient)
        .sendGetRequest(
            eq(TLD), eq("v2/monitoring/dns/alarmed"), eq(ImmutableMap.of()), eq(ImmutableMap.of()));
  }

  @Test
  void serviceAlarmed_notFound_returnsDisabled() throws MosApiException {
    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_NOT_FOUND);
    when(httpResponse.body()).thenReturn("Not available");

    ServiceAlarm result = client.serviceAlarmed(TLD, SERVICE);

    assertThat(result.getAlarmed()).isEqualTo("Disabled");
  }

  @Test
  void serviceAlarmed_serverError_throwsException() {
    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_INTERNAL_ERROR);

    MosApiException thrown =
        assertThrows(MosApiException.class, () -> client.serviceAlarmed(TLD, SERVICE));

    assertThat(thrown).hasMessageThat().contains("failed with status code 500");
  }
}
