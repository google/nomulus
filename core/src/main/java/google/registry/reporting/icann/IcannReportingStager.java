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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.reporting.icann.IcannReportingModule.MANIFEST_FILE_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.cloud.storage.BlobId;
import com.google.common.base.Ascii;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.ListMultimap;
import com.google.common.flogger.FluentLogger;
import google.registry.bigquery.BigqueryConnection;
import google.registry.bigquery.BigqueryUtils.TableType;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.reporting.icann.IcannReportingModule.ReportType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.joda.time.YearMonth;
import org.joda.time.format.DateTimeFormat;

/**
 * Class containing methods for staging ICANN monthly reports on GCS.
 *
 * <p>The main entrypoint is stageReports, which generates a given type of reports.
 */
public class IcannReportingStager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject @Config("reportingBucket") String reportingBucket;

  @Inject ActivityReportingQueryBuilder activityQueryBuilder;
  @Inject TransactionsReportingQueryBuilder transactionsQueryBuilder;
  @Inject GcsUtils gcsUtils;
  @Inject BigqueryConnection bigquery;

  @Inject
  IcannReportingStager() {}

  /**
   * Creates and stores reports of a given type on GCS.
   *
   * <p>This is factored out to facilitate choosing which reports to upload,
   */
  ImmutableList<String> stageReports(YearMonth yearMonth, String subdir, ReportType reportType)
      throws Exception {
    QueryBuilder queryBuilder =
        (reportType == ReportType.ACTIVITY) ? activityQueryBuilder : transactionsQueryBuilder;

    if (reportType == ReportType.ACTIVITY) {
      // Prepare for the DNS count query, which may have special needs.
      activityQueryBuilder.prepareForQuery(yearMonth);
    }

    ImmutableMap<String, String> viewQueryMap = queryBuilder.getViewQueryMap(yearMonth);
    // Generate intermediary views
    for (Entry<String, String> entry : viewQueryMap.entrySet()) {
      createIntermediaryTableView(entry.getKey(), entry.getValue(), reportType);
    }

    // Get an in-memory table of the aggregate query's result
    ImmutableTable<Integer, TableFieldSchema, Object> reportTable =
        bigquery.queryToLocalTableSync(queryBuilder.getReportQuery(yearMonth));

    // Get report headers from the table schema and convert into CSV format
    String headerRow = constructRow(getHeaders(reportTable.columnKeySet()));

    return (reportType == ReportType.ACTIVITY)
        ? stageActivityReports(yearMonth, subdir, headerRow, reportTable.rowMap().values())
        : stageTransactionsReports(yearMonth, subdir, headerRow, reportTable.rowMap().values());
  }

  private void createIntermediaryTableView(String queryName, String query, ReportType reportType)
      throws ExecutionException, InterruptedException {
    // Later views depend on the results of earlier ones, so query everything synchronously
    logger.atInfo().log("Generating intermediary view %s.", queryName);
    bigquery
        .startQuery(
            query,
            bigquery
                .buildDestinationTable(queryName)
                .description(
                    String.format(
                        "An intermediary view to generate %s reports for this month.", reportType))
                .type(TableType.VIEW)
                .build())
        .get();
  }

  private Iterable<String> getHeaders(ImmutableSet<TableFieldSchema> fields) {
    return fields
        .stream()
        .map((schema) -> schema.getName().replace('_', '-'))
        .collect(toImmutableList());
  }

  /** Creates and stores activity reports on GCS, returns a list of files stored. */
  private ImmutableList<String> stageActivityReports(
      YearMonth yearMonth,
      String subdir,
      String headerRow,
      ImmutableCollection<Map<TableFieldSchema, Object>> rows)
      throws IOException {
    ImmutableList.Builder<String> manifestBuilder = new ImmutableList.Builder<>();
    // Create a report csv for each tld from query table, and upload to GCS
    for (Map<TableFieldSchema, Object> row : rows) {
      // Get the tld (first cell in each row)
      String tld = row.values().iterator().next().toString();
      if (isNullOrEmpty(tld)) {
        throw new RuntimeException("Found an empty row in the activity report table!");
      }
      ImmutableList<String> rowStrings = ImmutableList.of(constructRow(row.values()));
      // Create and upload the activity report with a single row
      manifestBuilder.add(
          saveReportToGcs(
              tld, yearMonth, subdir, createReport(headerRow, rowStrings), ReportType.ACTIVITY));
    }
    return manifestBuilder.build();
  }

  /** Creates and stores transactions reports on GCS, returns a list of files stored. */
  private ImmutableList<String> stageTransactionsReports(
      YearMonth yearMonth,
      String subdir,
      String headerRow,
      ImmutableCollection<Map<TableFieldSchema, Object>> rows)
      throws IOException {
    // Map from tld to rows
    ListMultimap<String, String> tldToRows = ArrayListMultimap.create();
    // Map from tld to totals
    HashMap<String, List<Integer>> tldToTotals = new HashMap<>();
    for (Map<TableFieldSchema, Object> row : rows) {
      // Get the tld (first cell in each row)
      String tld = row.values().iterator().next().toString();
      if (isNullOrEmpty(tld)) {
        throw new RuntimeException("Found an empty row in the transactions report table!");
      }
      tldToRows.put(tld, constructRow(row.values()));
      // Construct totals for each tld, skipping non-summable columns (TLD, registrar name, iana-id)
      if (!tldToTotals.containsKey(tld)) {
        tldToTotals.put(tld, new ArrayList<>(Collections.nCopies(row.values().size() - 3, 0)));
      }
      addToTotal(tldToTotals.get(tld), row);
    }
    ImmutableList.Builder<String> manifestBuilder = new ImmutableList.Builder<>();
    // Create and upload a transactions report for each tld via its rows
    for (String tld : tldToRows.keySet()) {
      // Append the totals row
      tldToRows.put(tld, constructTotalRow(tldToTotals.get(tld)));
      manifestBuilder.add(
          saveReportToGcs(
              tld,
              yearMonth,
              subdir,
              createReport(headerRow, tldToRows.get(tld)),
              ReportType.TRANSACTIONS));
    }
    return manifestBuilder.build();
  }

  /** Adds a row's values to an existing list of integers (totals). */
  private void addToTotal(List<Integer> totals, Map<TableFieldSchema, Object> row) {
    List<Integer> rowVals =
        row.values()
            .stream()
            // Ignore TLD, Registrar name and IANA id
            .skip(3)
            .map((Object o) -> Integer.parseInt(o.toString()))
            .collect(toImmutableList());
    checkState(
        rowVals.size() == totals.size(),
        "Number of elements in totals not equal to number of elements in row!");
    for (int i = 0; i < rowVals.size(); i++) {
      totals.set(i, totals.get(i) + rowVals.get(i));
    }
  }

  /** Returns a list of integers (totals) as a comma separated string. */
  private String constructTotalRow(List<Integer> totals) {
    return "Totals,," + totals.stream().map(Object::toString).collect(Collectors.joining(","));
  }

  /**
   * Makes a row of the report by appending the string representation of all objects in an iterable
   * with commas separating individual fields.
   *
   * <p>This discards the first object, which is assumed to be the TLD field.
   * */
  private String constructRow(Iterable<?> iterable) {
    Iterator<?> rowIter = iterable.iterator();
    StringBuilder rowString = new StringBuilder();
    // Skip the TLD column
    rowIter.next();
    while (rowIter.hasNext()) {
      rowString.append(String.format("%s,", rowIter.next().toString()));
    }
    // Remove trailing comma
    rowString.deleteCharAt(rowString.length() - 1);
    return rowString.toString();
  }

  /**
   * Constructs a report given its headers and rows as a string.
   *
   * <p>Note that activity reports will only have one row, while transactions reports may have
   * multiple rows.
   */
  private String createReport(String headers, List<String> rows) {
    StringBuilder reportCsv = new StringBuilder(headers);
    for (String row : rows) {
      // Add CRLF between rows per ICANN specification
      reportCsv.append("\r\n");
      reportCsv.append(row);
    }
    return reportCsv.toString();
  }

  /** Stores a report on GCS, returning the name of the file stored. */
  private String saveReportToGcs(
      String tld, YearMonth yearMonth, String subdir, String reportCsv, ReportType reportType)
      throws IOException {
    // Upload resulting CSV file to GCS
    byte[] reportBytes = reportCsv.getBytes(UTF_8);
    String reportFilename =
        String.format(
            "%s-%s-%s.csv",
            tld,
            Ascii.toLowerCase(reportType.toString()),
            DateTimeFormat.forPattern("yyyyMM").print(yearMonth));
    final BlobId gcsFilename =
        BlobId.of(reportingBucket, String.format("%s/%s", subdir, reportFilename));
    gcsUtils.createFromBytes(gcsFilename, reportBytes);
    logger.atInfo().log("Wrote %d bytes to file location '%s'.", reportBytes.length, gcsFilename);
    return reportFilename;
  }

  /** Creates and stores a manifest file on GCS, indicating which reports were generated. */
  void createAndUploadManifest(String subdir, ImmutableList<String> filenames) throws IOException {
    final BlobId gcsFilename =
        BlobId.of(reportingBucket, String.format("%s/%s", subdir, MANIFEST_FILE_NAME));
    StringBuilder manifestString = new StringBuilder();
    filenames.forEach((filename) -> manifestString.append(filename).append("\n"));
    gcsUtils.createFromBytes(gcsFilename, manifestString.toString().getBytes(UTF_8));
    logger.atInfo().log("Wrote %d filenames to manifest at '%s'.", filenames.size(), gcsFilename);
  }
}
