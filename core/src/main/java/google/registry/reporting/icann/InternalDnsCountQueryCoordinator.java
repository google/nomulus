// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
package google.registry.reporting.icann;

import static google.registry.reporting.icann.IcannReportingModule.ICANN_REPORTING_DATA_SET;

import com.google.common.flogger.FluentLogger;
import com.google.common.io.Resources;
import google.registry.bigquery.BigqueryConnection;
import google.registry.bigquery.BigqueryUtils.TableType;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.ResourceUtils;
import google.registry.util.SqlTemplate;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.YearMonth;
import org.joda.time.format.DateTimeFormat;

/**
 * DNS Count query that relies on a table Cloud DNS publishes internally.
 *
 * <p>The internal Plx table is exposed as a BigQuery table via BQ-TS federation. This is not
 * applicable to external users who also happen to use Cloud DNS as the plx table is specific to
 * Google Registry's zones.
 */
public class InternalDnsCountQueryCoordinator implements DnsCountQueryCoordinator {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String PLX_DNS_TABLE_NAME = "dns_counts_from_plx";
  private static final String DATASET_ID = "reporting";
  private static final String TABLE_ID = "zoneman_counts";

  @Inject BigqueryConnection bigquery;

  @Inject
  @Config("projectId")
  String projectId;

  @Inject
  @Named(ICANN_REPORTING_DATA_SET)
  String icannReportingDataSet;

  @Inject
  @Config("dnsCountProjectId")
  String dnsCountProjectId;

  @Override
  public String createQuery(YearMonth yearMonth) {
    String template =
        ResourceUtils.readResourceUtf8(
            Resources.getResource(this.getClass(), "sql/dns_counts_internal.sql"));
    return SqlTemplate.create(template)
        .put("PROJECT_ID", projectId)
        .put("ICANN_REPORTING_DATA_SET", icannReportingDataSet)
        .put("DNS_TABLE_NAME", PLX_DNS_TABLE_NAME)
        .build();
  }

  @Override
  public void prepareForQuery(YearMonth yearMonth) throws InterruptedException {
    logger.atInfo().log("Generating intermediary table dns_counts");
    String query = getPlxDnsTableQuery(yearMonth);
    try {
      bigquery
          .startQuery(
              query,
              bigquery
                  .buildDestinationTable(PLX_DNS_TABLE_NAME)
                  .description("A table holding DNS query counts to generate ACTIVITY reports.")
                  .type(TableType.TABLE)
                  .build())
          .get();
    } catch (ExecutionException e) {
      throw new RuntimeException("Error while running BigQuery query", e.getCause());
    }
  }

  String getPlxDnsTableQuery(YearMonth yearMonth) {
    String template =
        ResourceUtils.readResourceUtf8(
            Resources.getResource(this.getClass(), "sql/prepare_dns_counts_internal.sql"));
    SqlTemplate queryTemplate =
        SqlTemplate.create(template)
            .put("DNS_COUNT_PROJECT_ID", dnsCountProjectId)
            .put("DATASET_ID", DATASET_ID)
            .put("TABLE_ID", TABLE_ID)
            .put("YEAR_MONTH", DateTimeFormat.forPattern("yyyyMM").print(yearMonth));
    return queryTemplate.build();
  }
}
