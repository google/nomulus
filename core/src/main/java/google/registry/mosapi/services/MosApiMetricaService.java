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

package google.registry.mosapi.services;

import google.registry.mosapi.client.DomainMetricaClient;
import google.registry.mosapi.dto.domainmetrica.MetricaReport;
import google.registry.mosapi.dto.domainmetrica.MetricaReportInfo;
import google.registry.mosapi.exception.MosApiException;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Service to fetch Domain METRICA reports from the MoSAPI. */
public class MosApiMetricaService {
  private final DomainMetricaClient metricaClient;

  @Inject
  public MosApiMetricaService(DomainMetricaClient metricaClient) {
    this.metricaClient = metricaClient;
  }

  /**
   * Fetches a METRICA report for a given TLD.
   *
   * @param tld the TLD to query for.
   * @param date if present, fetches the report for this specific date, otherwise fetches the latest
   *     available report.
   * @return The {@link MetricaReport}.
   * @throws MosApiException if the API call fails.
   */
  public MetricaReport getReport(String tld, Optional<LocalDate> date) throws MosApiException {
    if (date.isPresent()) {
      return metricaClient.getMetricaReportForDate(tld, date.get());
    } else {
      return metricaClient.getLatestMetricaReport(tld);
    }
  }

  /** Lists available Domain METRICA report dates for a given TLD and optional date range. */
  public List<MetricaReportInfo> listAvailableReports(
      String tld, Optional<LocalDate> startDate, Optional<LocalDate> endDate)
      throws MosApiException {
    return metricaClient.listAvailableMetricaReports(
        tld, startDate.orElse(null), endDate.orElse(null));
  }
}
