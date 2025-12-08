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
package google.registry.mosapi.action;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.mosapi.dto.domainmetrica.MetricaReport;
import google.registry.mosapi.exception.MosApiException;
import google.registry.mosapi.services.MosApiMetricaService;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.testing.FakeResponse;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link GetMetricaReportAction}. */
@ExtendWith(MockitoExtension.class)
public class GetMetricaReportActionTest {
  @Mock private MosApiMetricaService metricaService;
  private final FakeResponse response = new FakeResponse();
  private final Gson gson = new Gson();
  private final String tld = "example";
  private final LocalDate date = LocalDate.of(2025, 1, 1);

  private GetMetricaReportAction action;

  @BeforeEach
  void beforeEach() {
    action = new GetMetricaReportAction(metricaService, response, gson, tld, Optional.of(date));
  }

  @Test
  void testRun_returnsReport() throws Exception {
    MetricaReport mockReport =
        new MetricaReport(2, "example", "2025-01-01", 10, ImmutableList.of());

    when(metricaService.getReport(tld, Optional.of(date))).thenReturn(mockReport);

    action.run();

    assertThat(response.getContentType()).isEqualTo(MediaType.JSON_UTF_8);
    assertThat(response.getPayload()).contains("\"tld\":\"example\"");
    assertThat(response.getPayload()).contains("\"uniqueAbuseDomains\":10");
    verify(metricaService).getReport(tld, Optional.of(date));
  }

  @Test
  void testRun_serviceThrowsMosApiException_throwsServiceUnavailable() throws Exception {
    doThrow(new MosApiException("Backend failure")).when(metricaService).getReport(any(), any());

    ServiceUnavailableException thrown =
        assertThrows(ServiceUnavailableException.class, action::run);

    assertThat(thrown).hasMessageThat().isEqualTo("Error fetching METRICA report.");
  }
}
