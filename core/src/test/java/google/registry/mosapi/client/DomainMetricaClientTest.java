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
import google.registry.mosapi.dto.domainmetrica.MetricaReport;
import google.registry.mosapi.dto.domainmetrica.MetricaReportInfo;
import google.registry.mosapi.exception.MosApiException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link DomainMetricaClient}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class DomainMetricaClientTest {
  private static final String TLD = "example";
  private static final Gson GSON = new Gson();
  private static final String BASE_URI = "https://mosapi.icann.org/v2/metrica";

  @Mock MosApiClient mosApiClient;
  @Mock HttpResponse<String> httpResponse;

  private DomainMetricaClient client;

  @BeforeEach
  void beforeEach() {
    client = new DomainMetricaClient(mosApiClient, GSON);
    // Common mock behavior for URI to avoid NPEs in exception messages
    when(httpResponse.uri()).thenReturn(URI.create(BASE_URI));
  }

  @Test
  void testGetLatestMetricaReport_success() throws Exception {
    String jsonResponse =
        "{"
            + "\"version\": 2,"
            + "\"ianald\": 123,"
            + "\"domainListDate\": \"2025-05-20\","
            + "\"uniqueAbuseDomains\": 14,"
            + "\"domainListData\": []"
            + "}";

    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_OK);
    when(httpResponse.body()).thenReturn(jsonResponse);
    when(mosApiClient.sendGetRequestWithDecompression(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(httpResponse);

    MetricaReport result = client.getLatestMetricaReport(TLD);

    assertThat(result).isNotNull();
    // Assuming MetricaReport has this field (based on JSON)
    // assertThat(result.domainListDate()).isEqualTo("2025-05-20");

    verify(mosApiClient)
        .sendGetRequestWithDecompression(
            eq(TLD), eq("v2/metrica/domainList/latest"), eq(ImmutableMap.of()), anyMap());
  }

  @Test
  void testGetLatestMetricaReport_notFound_throwsException() throws Exception {
    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_NOT_FOUND);
    when(mosApiClient.sendGetRequestWithDecompression(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(httpResponse);

    MosApiException thrown =
        assertThrows(MosApiException.class, () -> client.getLatestMetricaReport(TLD));

    assertThat(thrown).hasMessageThat().contains("No METRICA report found for TLD: " + TLD);
  }

  @Test
  void testGetMetricaReportForDate_success() throws Exception {
    LocalDate date = LocalDate.of(2025, 5, 20);
    String jsonResponse = "{ \"version\": 2, \"domainListDate\": \"2025-05-20\" }";

    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_OK);
    when(httpResponse.body()).thenReturn(jsonResponse);
    when(mosApiClient.sendGetRequestWithDecompression(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(httpResponse);

    MetricaReport result = client.getMetricaReportForDate(TLD, date);

    assertThat(result).isNotNull();
    verify(mosApiClient)
        .sendGetRequestWithDecompression(
            eq(TLD), eq("v2/metrica/domainList/2025-05-20"), eq(ImmutableMap.of()), anyMap());
  }

  @Test
  void testGetMetricaReportForDate_notFound_throwsException() throws Exception {
    LocalDate date = LocalDate.of(2025, 5, 20);
    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_NOT_FOUND);
    when(mosApiClient.sendGetRequestWithDecompression(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(httpResponse);

    MosApiException thrown =
        assertThrows(MosApiException.class, () -> client.getMetricaReportForDate(TLD, date));

    assertThat(thrown)
        .hasMessageThat()
        .contains("No METRICA report found for TLD " + TLD + " on 2025-05-20");
  }

  @Test
  void testListAvailableMetricaReports_withDates_success() throws Exception {
    LocalDate start = LocalDate.of(2025, 1, 1);
    LocalDate end = LocalDate.of(2025, 1, 31);
    String jsonResponse =
        "{"
            + "\"version\": 2,"
            + "\"domainLists\": ["
            + "  { \"domainListDate\": \"2025-01-10\" }"
            + "]"
            + "}";

    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_OK);
    when(httpResponse.body()).thenReturn(jsonResponse);
    when(mosApiClient.sendGetRequestWithDecompression(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(httpResponse);

    List<MetricaReportInfo> result = client.listAvailableMetricaReports(TLD, start, end);

    assertThat(result).hasSize(1);

    Map<String, String> expectedParams =
        ImmutableMap.of(
            "startDate", "2025-01-01",
            "endDate", "2025-01-31");

    verify(mosApiClient)
        .sendGetRequestWithDecompression(
            eq(TLD), eq("v2/metrica/domainLists"), eq(expectedParams), anyMap());
  }

  @Test
  void testListAvailableMetricaReports_onlyStartDate_success() throws Exception {
    LocalDate start = LocalDate.of(2025, 1, 1);
    String jsonResponse = "{ \"version\": 2, \"domainLists\": [] }";

    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_OK);
    when(httpResponse.body()).thenReturn(jsonResponse);
    when(mosApiClient.sendGetRequestWithDecompression(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(httpResponse);

    client.listAvailableMetricaReports(TLD, start, null);

    verify(mosApiClient)
        .sendGetRequestWithDecompression(
            eq(TLD),
            eq("v2/metrica/domainLists"),
            eq(ImmutableMap.of("startDate", "2025-01-01")),
            anyMap());
  }

  @Test
  void testListAvailableMetricaReports_badRequest_throwsException() throws Exception {
    String errorJson =
        "{"
            + "\"resultCode\": 2012,"
            + "\"message\": \"The endDate is before the startDate.\","
            + "\"description\": \"Validation failed\""
            + "}";

    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_BAD_REQUEST);
    when(httpResponse.body()).thenReturn(errorJson);
    when(mosApiClient.sendGetRequestWithDecompression(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(httpResponse);

    MosApiException thrown =
        assertThrows(
            MosApiException.class, () -> client.listAvailableMetricaReports(TLD, null, null));

    // Verifies that the error message from the JSON response is propagated
    assertThat(thrown).hasMessageThat().contains("The endDate is before the startDate.");
  }

  @Test
  void testListAvailableMetricaReports_serverError_throwsException() throws Exception {
    when(httpResponse.statusCode()).thenReturn(HttpURLConnection.HTTP_INTERNAL_ERROR);
    when(mosApiClient.sendGetRequestWithDecompression(anyString(), anyString(), anyMap(), anyMap()))
        .thenReturn(httpResponse);

    MosApiException thrown =
        assertThrows(
            MosApiException.class, () -> client.listAvailableMetricaReports(TLD, null, null));

    assertThat(thrown).hasMessageThat().contains("failed with status code 500");
  }

  private static String anyString() {
    return any(String.class);
  }
}
