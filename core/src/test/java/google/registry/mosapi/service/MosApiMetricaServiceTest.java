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
package google.registry.mosapi.service;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.mosapi.client.DomainMetricaClient;
import google.registry.mosapi.dto.domainmetrica.MetricaReport;
import google.registry.mosapi.dto.domainmetrica.MetricaReportInfo;
import google.registry.mosapi.services.MosApiMetricaService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MosApiMetricaService}. */
@ExtendWith(MockitoExtension.class)
public class MosApiMetricaServiceTest {
  @Mock private DomainMetricaClient metricaClient;

  private MosApiMetricaService metricaService;
  private final String tld = "example";

  @BeforeEach
  void setUp() {
    metricaService = new MosApiMetricaService(metricaClient);
  }

  @Test
  void getReport_withDate_callsClientForDate() throws Exception {
    LocalDate date = LocalDate.of(2025, 5, 20);
    MetricaReport mockReport = new MetricaReport(2, tld, "2025-05-20", 0, ImmutableList.of());

    when(metricaClient.getMetricaReportForDate(tld, date)).thenReturn(mockReport);

    MetricaReport result = metricaService.getReport(tld, Optional.of(date));

    assertThat(result).isEqualTo(mockReport);
    verify(metricaClient).getMetricaReportForDate(tld, date);
  }

  @Test
  void getReport_withoutDate_callsClientForLatest() throws Exception {
    MetricaReport mockReport = new MetricaReport(2, tld, "2025-05-20", 0, ImmutableList.of());

    when(metricaClient.getLatestMetricaReport(tld)).thenReturn(mockReport);

    MetricaReport result = metricaService.getReport(tld, Optional.empty());

    assertThat(result).isEqualTo(mockReport);
    verify(metricaClient).getLatestMetricaReport(tld);
  }

  @Test
  void listAvailableReports_delegatesToClient() throws Exception {
    LocalDate start = LocalDate.of(2025, 1, 1);
    List<MetricaReportInfo> expectedList = ImmutableList.of();

    when(metricaClient.listAvailableMetricaReports(tld, start, null)).thenReturn(expectedList);

    List<MetricaReportInfo> result =
        metricaService.listAvailableReports(tld, Optional.of(start), Optional.empty());

    assertThat(result).isSameInstanceAs(expectedList);
    verify(metricaClient).listAvailableMetricaReports(tld, start, null);
  }
}
