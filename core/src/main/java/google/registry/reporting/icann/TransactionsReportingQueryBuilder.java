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

import static google.registry.reporting.icann.IcannReportingModule.ICANN_REPORTING_DATA_SET;
import static google.registry.reporting.icann.QueryBuilderUtils.getQueryFromFile;
import static google.registry.reporting.icann.QueryBuilderUtils.getTableName;

import com.google.common.collect.ImmutableMap;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.SqlTemplate;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.DateTime;
import org.joda.time.LocalTime;
import org.joda.time.YearMonth;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/**
 * Utility class that produces SQL queries used to generate activity reports from Bigquery.
 */
public final class TransactionsReportingQueryBuilder implements QueryBuilder {

  final String projectId;
  private final String icannReportingDataSet;

  @Inject
  TransactionsReportingQueryBuilder(
      @Config("projectId") String projectId,
      @Named(ICANN_REPORTING_DATA_SET) String icannReportingDataSet) {
    this.projectId = projectId;
    this.icannReportingDataSet = icannReportingDataSet;
  }

  static final String TRANSACTIONS_REPORT_AGGREGATION = "transactions_report_aggregation";
  static final String REGISTRAR_IANA_ID = "registrar_iana_id";
  static final String TOTAL_DOMAINS = "total_domains";
  static final String TOTAL_NAMESERVERS = "total_nameservers";
  static final String TRANSACTION_COUNTS = "transaction_counts";
  static final String TRANSACTION_TRANSFER_LOSING = "transaction_transfer_losing";
  static final String ATTEMPTED_ADDS = "attempted_adds";

  /** Returns the aggregate query which generates the transactions report from the saved view. */
  @Override
  public String getReportQuery(YearMonth yearMonth) {
    return String.format(
        "#standardSQL\nSELECT * FROM `%s.%s.%s`",
        projectId, icannReportingDataSet, getTableName(TRANSACTIONS_REPORT_AGGREGATION, yearMonth));
  }

  /** Sets the month we're doing transactions reporting for, and returns the view query map. */
  @Override
  public ImmutableMap<String, String> getViewQueryMap(YearMonth yearMonth) {
    // Set the earliest date to to yearMonth on day 1 at 00:00:00
    DateTime earliestReportTime = yearMonth.toLocalDate(1).toDateTime(new LocalTime(0, 0, 0));
    // Set the latest date to yearMonth on the last day at 23:59:59.999
    DateTime latestReportTime = earliestReportTime.plusMonths(1).minusMillis(1);

    ImmutableMap.Builder<String, String> queriesBuilder = ImmutableMap.builder();
    String registrarIanaIdQuery =
        SqlTemplate.create(getQueryFromFile("cloud_sql_registrar_iana_id.sql"))
            .put("PROJECT_ID", projectId)
            .build();
    queriesBuilder.put(getTableName(REGISTRAR_IANA_ID, yearMonth), registrarIanaIdQuery);

    String totalDomainsQuery =
        SqlTemplate.create(getQueryFromFile("cloud_sql_total_domains.sql"))
            .put("PROJECT_ID", projectId)
            .build();
    queriesBuilder.put(getTableName(TOTAL_DOMAINS, yearMonth), totalDomainsQuery);

    DateTimeFormatter timestampFormatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS");
    String totalNameserversQuery =
        SqlTemplate.create(getQueryFromFile("cloud_sql_total_nameservers.sql"))
            .put("PROJECT_ID", projectId)
            .put("LATEST_REPORT_TIME", timestampFormatter.print(latestReportTime))
            .build();
    queriesBuilder.put(getTableName(TOTAL_NAMESERVERS, yearMonth), totalNameserversQuery);

    String transactionCountsQuery =
        SqlTemplate.create(getQueryFromFile("cloud_sql_transaction_counts.sql"))
            .put("PROJECT_ID", projectId)
            .put("EARLIEST_REPORT_TIME", timestampFormatter.print(earliestReportTime))
            .put("LATEST_REPORT_TIME", timestampFormatter.print(latestReportTime))
            .build();
    queriesBuilder.put(getTableName(TRANSACTION_COUNTS, yearMonth), transactionCountsQuery);

    String transactionTransferLosingQuery =
        SqlTemplate.create(getQueryFromFile("cloud_sql_transaction_transfer_losing.sql"))
            .put("PROJECT_ID", projectId)
            .put("EARLIEST_REPORT_TIME", timestampFormatter.print(earliestReportTime))
            .put("LATEST_REPORT_TIME", timestampFormatter.print(latestReportTime))
            .build();
    queriesBuilder.put(
        getTableName(TRANSACTION_TRANSFER_LOSING, yearMonth), transactionTransferLosingQuery);

    // App Engine log table suffixes use YYYYMMDD format
    DateTimeFormatter logTableFormatter = DateTimeFormat.forPattern("yyyyMMdd");
    String attemptedAddsQuery =
        SqlTemplate.create(getQueryFromFile("cloud_sql_attempted_adds.sql"))
            .put("PROJECT_ID", projectId)
            .put("APPENGINE_LOGS_DATA_SET", "appengine_logs")
            .put("GKE_LOGS_DATA_SET", "gke_logs")
            .put("APP_LOGS_TABLE", "_var_log_app_")
            .put("FIRST_DAY_OF_MONTH", logTableFormatter.print(earliestReportTime))
            .put("LAST_DAY_OF_MONTH", logTableFormatter.print(latestReportTime))
            .build();
    queriesBuilder.put(getTableName(ATTEMPTED_ADDS, yearMonth), attemptedAddsQuery);

    String aggregateQuery =
        SqlTemplate.create(getQueryFromFile("cloud_sql_transactions_report_aggregation.sql"))
            .put("PROJECT_ID", projectId)
            .put("ICANN_REPORTING_DATA_SET", icannReportingDataSet)
            .put("REGISTRAR_IANA_ID_TABLE", getTableName(REGISTRAR_IANA_ID, yearMonth))
            .put("TOTAL_DOMAINS_TABLE", getTableName(TOTAL_DOMAINS, yearMonth))
            .put("TOTAL_NAMESERVERS_TABLE", getTableName(TOTAL_NAMESERVERS, yearMonth))
            .put("TRANSACTION_COUNTS_TABLE", getTableName(TRANSACTION_COUNTS, yearMonth))
            .put(
                "TRANSACTION_TRANSFER_LOSING_TABLE",
                getTableName(TRANSACTION_TRANSFER_LOSING, yearMonth))
            .put("ATTEMPTED_ADDS_TABLE", getTableName(ATTEMPTED_ADDS, yearMonth))
            .build();
    queriesBuilder.put(getTableName(TRANSACTIONS_REPORT_AGGREGATION, yearMonth), aggregateQuery);

    return queriesBuilder.build();
  }
}
