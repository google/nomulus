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
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.mosapi.dto.domainmetrica.MetricaReportInfo;
import google.registry.mosapi.exception.MosApiException;
import google.registry.mosapi.services.MosApiMetricaService;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.testing.FakeResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link ListMetricaReportsAction}. */
@ExtendWith(MockitoExtension.class)
public class ListMetricaReportsActionTest {
  @Mock private MosApiMetricaService metricaService;
  private final FakeResponse response = new FakeResponse();
  private final Gson gson = new Gson();
  private final String tld = "example";
  private final LocalDate startDate = LocalDate.of(2025, 1, 1);
  private final LocalDate endDate = LocalDate.of(2025, 1, 31);

  @Test
  void testRun_returnsList() throws Exception {
    ListMetricaReportsAction action =
        new ListMetricaReportsAction(
            metricaService, response, gson, tld, Optional.of(startDate), Optional.of(endDate));

    List<MetricaReportInfo> infoList = ImmutableList.of();
    when(metricaService.listAvailableReports(tld, Optional.of(startDate), Optional.of(endDate)))
        .thenReturn(infoList);

    action.run();

    assertThat(response.getContentType()).isEqualTo(MediaType.JSON_UTF_8);
    assertThat(response.getPayload()).isEqualTo("[]");
  }

  @Test
  void testRun_emptyDates_passedAsEmptyOptionals() throws Exception {
    ListMetricaReportsAction action =
        new ListMetricaReportsAction(
            metricaService, response, gson, tld, Optional.empty(), Optional.empty());

    when(metricaService.listAvailableReports(tld, Optional.empty(), Optional.empty()))
        .thenReturn(ImmutableList.of());

    action.run();

    assertThat(response.getPayload()).isEqualTo("[]");
  }

  @Test
  void testRun_serviceThrowsException_throwsServiceUnavailable() throws Exception {
    ListMetricaReportsAction action =
        new ListMetricaReportsAction(
            metricaService, response, gson, tld, Optional.empty(), Optional.empty());

    doThrow(new MosApiException("Failure"))
        .when(metricaService)
        .listAvailableReports(any(), any(), any());

    ServiceUnavailableException thrown =
        assertThrows(ServiceUnavailableException.class, action::run);

    assertThat(thrown).hasMessageThat().isEqualTo("Error listing METRICA reports.");
  }
}
