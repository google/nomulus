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

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import google.registry.mosapi.dto.MosApiErrorResponse;
import google.registry.mosapi.dto.domainmetrica.MetricaReport;
import google.registry.mosapi.dto.domainmetrica.MetricaReportInfo;
import google.registry.mosapi.exception.MosApiException;
import jakarta.inject.Inject;
import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/** Facade for MoSAPI's Domain METRICA endpoints. */
public class DomainMetricaClient {
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final MosApiClient mosApiClient;
  private final Gson gson;

  @Inject
  public DomainMetricaClient(MosApiClient mosApiClient, Gson gson) {
    this.mosApiClient = mosApiClient;
    this.gson = gson;
  }

  /**
   * Fetches the most recent daily Domain METRICA report.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.1</a>
   */
  public MetricaReport getLatestMetricaReport(String tld) throws MosApiException {
    String endpoint = String.format("v2/metrica/domainList/latest");

    HttpResponse<String> response =
        mosApiClient.sendGetRequestWithDecompression(
            tld,
            endpoint,
            Collections.emptyMap(),
            ImmutableMap.of("Accept-Encoding", "gzip, deflate"));

    // Return 404 if no report exists for the TLD.
    if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
      throw new MosApiException("No METRICA report found for TLD: " + tld);
    }
    return gson.fromJson(response.body(), MetricaReport.class);
  }

  /**
   * Fetches the Domain METRICA report for a specific date.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.2</a>
   */
  public MetricaReport getMetricaReportForDate(String tld, LocalDate date) throws MosApiException {
    String formattedDate = date.format(DATE_FORMATTER);
    String endpoint = String.format("v2/metrica/domainList/%s", formattedDate);
    HttpResponse<String> response =
        mosApiClient.sendGetRequestWithDecompression(
            tld,
            endpoint,
            Collections.emptyMap(),
            ImmutableMap.of("Accept-Encoding", "gzip, deflate"));

    // Return 404 if no report exists for the specified date.
    if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
      throw new MosApiException(
          String.format("No METRICA report found for TLD %s on %s", tld, formattedDate));
    }
    return gson.fromJson(response.body(), MetricaReport.class);
  }

  /**
   * Lists available Domain METRICA report dates within a given range.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.3</a>
   */
  public List<MetricaReportInfo> listAvailableMetricaReports(
      String tld, @Nullable LocalDate startDate, @Nullable LocalDate endDate)
      throws MosApiException {
    String endpoint = String.format("v2/metrica/domainLists");
    ImmutableMap.Builder<String, String> params = new ImmutableMap.Builder<>();
    // Optional startDate and endDate parameters
    if (startDate != null) {
      params.put("startDate", startDate.format(DATE_FORMATTER));
    }
    if (endDate != null) {
      params.put("endDate", endDate.format(DATE_FORMATTER));
    }

    HttpResponse<String> response =
        mosApiClient.sendGetRequestWithDecompression(
            tld, endpoint, params.build(), ImmutableMap.of("Accept-Encoding", "gzip, deflate"));
    // Handle HTTP 400 for specific business logic errors (codes 2012, 2013, 2014)
    if (response.statusCode() == HttpURLConnection.HTTP_BAD_REQUEST) {
      MosApiErrorResponse error = gson.fromJson(response.body(), MosApiErrorResponse.class);
      throw MosApiException.create(error);
    }

    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      throw new MosApiException(
          String.format(
              "Request to %s failed with status code %d", response.uri(), response.statusCode()));
    }

    MetricaReportListResponse listResponse =
        gson.fromJson(response.body(), MetricaReportListResponse.class);
    return listResponse.domainLists();
  }

  /** Helper class for deserializing the response from the 'domainLists' endpoint. */
  private static class MetricaReportListResponse {
    @Expose
    @SerializedName("domainLists")
    private List<MetricaReportInfo> domainLists;

    List<MetricaReportInfo> domainLists() {
      return domainLists;
    }
  }
}
