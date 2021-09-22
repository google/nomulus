// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.reporting.icann.IcannReportingModule.CLOUD_SQL_ICANN_REPORTING_DATASET;
import static google.registry.reporting.icann.IcannReportingModule.DATASTORE_EXPORT_DATA_SET;
import static google.registry.reporting.icann.IcannReportingModule.ICANN_REPORTING_DATA_SET;
import static google.registry.reporting.icann.QueryBuilderUtils.getQueryFromFile;
import static google.registry.reporting.icann.QueryBuilderUtils.getTableName;

import com.google.common.collect.ImmutableMap;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.SqlTemplate;
import javax.inject.Inject;
import org.joda.time.LocalDate;
import org.joda.time.YearMonth;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/** Utility class that produces SQL queries used to generate activity reports from Bigquery. */
public final class ActivityReportingQueryBuilder implements QueryBuilder {

  // Names for intermediary tables for overall activity reporting query.
  static final String REGISTRAR_OPERATING_STATUS = "registrar_operating_status";
  static final String DNS_COUNTS = "dns_counts";
  static final String MONTHLY_LOGS = "monthly_logs";
  static final String EPP_METRICS = "epp_metrics";
  static final String WHOIS_COUNTS = "whois_counts";
  static final String ACTIVITY_REPORT_AGGREGATION = "activity_report_aggregation";

  @Inject
  @Config("projectId")
  String projectId;

  @Inject DnsCountQueryCoordinator dnsCountQueryCoordinator;

  @Inject
  ActivityReportingQueryBuilder() {}

  /** Returns the aggregate query which generates the activity report from the saved view. */
  @Override
  public String getReportQuery(YearMonth yearMonth) {
    return String.format(
        "#standardSQL\nSELECT * FROM `%s.%s.%s`",
        projectId,
        tm().isOfy() ? ICANN_REPORTING_DATA_SET : CLOUD_SQL_ICANN_REPORTING_DATASET,
        getTableName(ACTIVITY_REPORT_AGGREGATION, yearMonth));
  }

  /** Sets the month we're doing activity reporting for, and returns the view query map. */
  @Override
  public ImmutableMap<String, String> getViewQueryMap(YearMonth yearMonth) {

    LocalDate firstDayOfMonth = yearMonth.toLocalDate(1);
    // The pattern-matching is inclusive, so we subtract 1 day to only report that month's data.
    LocalDate lastDayOfMonth = yearMonth.toLocalDate(1).plusMonths(1).minusDays(1);

    ImmutableMap.Builder<String, String> queriesBuilder = ImmutableMap.builder();
    String operationalRegistrarsQuery;
    if (tm().isOfy()) {
      operationalRegistrarsQuery =
          SqlTemplate.create(getQueryFromFile("registrar_operating_status.sql"))
              .put("PROJECT_ID", projectId)
              .put("DATASTORE_EXPORT_DATA_SET", DATASTORE_EXPORT_DATA_SET)
              .put("REGISTRAR_TABLE", "Registrar")
              .build();
    } else {
      operationalRegistrarsQuery =
          SqlTemplate.create(getQueryFromFile("cloud_sql_registrar_operating_status.sql"))
              .put("PROJECT_ID", projectId)
              .build();
    }
    queriesBuilder.put(
        getTableName(REGISTRAR_OPERATING_STATUS, yearMonth), operationalRegistrarsQuery);

    String dnsCountsQuery = dnsCountQueryCoordinator.createQuery(yearMonth);
    queriesBuilder.put(getTableName(DNS_COUNTS, yearMonth), dnsCountsQuery);

    // Convert reportingMonth into YYYYMMDD format for Bigquery table partition pattern-matching.
    DateTimeFormatter logTableFormatter = DateTimeFormat.forPattern("yyyyMMdd");
    // The monthly logs are a shared dependency for epp counts and whois metrics
    String monthlyLogsQuery =
        SqlTemplate.create(getQueryFromFile("monthly_logs.sql"))
            .put("PROJECT_ID", projectId)
            .put("APPENGINE_LOGS_DATA_SET", "appengine_logs")
            .put("REQUEST_TABLE", "appengine_googleapis_com_request_log_")
            .put("FIRST_DAY_OF_MONTH", logTableFormatter.print(firstDayOfMonth))
            .put("LAST_DAY_OF_MONTH", logTableFormatter.print(lastDayOfMonth))
            .build();
    queriesBuilder.put(getTableName(MONTHLY_LOGS, yearMonth), monthlyLogsQuery);

    String eppQuery =
        SqlTemplate.create(getQueryFromFile("epp_metrics.sql"))
            .put("PROJECT_ID", projectId)
            .put("ICANN_REPORTING_DATA_SET", ICANN_REPORTING_DATA_SET)
            .put("MONTHLY_LOGS_TABLE", getTableName(MONTHLY_LOGS, yearMonth))
            // All metadata logs for reporting come from google.registry.flows.FlowReporter.
            .put(
                "METADATA_LOG_PREFIX",
                "google.registry.flows.FlowReporter recordToLogs: FLOW-LOG-SIGNATURE-METADATA")
            .build();
    queriesBuilder.put(getTableName(EPP_METRICS, yearMonth), eppQuery);

    String whoisQuery =
        SqlTemplate.create(getQueryFromFile("whois_counts.sql"))
            .put("PROJECT_ID", projectId)
            .put("ICANN_REPORTING_DATA_SET", ICANN_REPORTING_DATA_SET)
            .put("MONTHLY_LOGS_TABLE", getTableName(MONTHLY_LOGS, yearMonth))
            .build();
    queriesBuilder.put(getTableName(WHOIS_COUNTS, yearMonth), whoisQuery);

    SqlTemplate aggregateQuery =
        SqlTemplate.create(
                getQueryFromFile(
                    tm().isOfy()
                        ? "activity_report_aggregation.sql"
                        : "cloud_sql_activity_report_aggregation.sql"))
            .put("PROJECT_ID", projectId)
            .put(
                "REGISTRAR_OPERATING_STATUS_TABLE",
                getTableName(REGISTRAR_OPERATING_STATUS, yearMonth))
            .put("ICANN_REPORTING_DATA_SET", ICANN_REPORTING_DATA_SET)
            .put("DNS_COUNTS_TABLE", getTableName(DNS_COUNTS, yearMonth))
            .put("EPP_METRICS_TABLE", getTableName(EPP_METRICS, yearMonth))
            .put("WHOIS_COUNTS_TABLE", getTableName(WHOIS_COUNTS, yearMonth));

    if (tm().isOfy()) {
      aggregateQuery =
          aggregateQuery
              .put("REGISTRY_TABLE", "Registry")
              .put("DATASTORE_EXPORT_DATA_SET", DATASTORE_EXPORT_DATA_SET);
    } else {
      aggregateQuery =
          aggregateQuery.put(
              "CLOUD_SQL_ICANN_REPORTING_DATA_SET", CLOUD_SQL_ICANN_REPORTING_DATASET);
    }

    queriesBuilder.put(
        getTableName(ACTIVITY_REPORT_AGGREGATION, yearMonth), aggregateQuery.build());

    return queriesBuilder.build();
  }

  public void prepareForQuery(YearMonth yearMonth) throws InterruptedException {
    dnsCountQueryCoordinator.prepareForQuery(yearMonth);
  }
}
