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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ActivityReportingQueryBuilder}. */
class TransactionsReportingQueryBuilderTest {

  private final YearMonth yearMonth = new YearMonth(2017, 9);

  private TransactionsReportingQueryBuilder createQueryBuilder(String datasetName) {
    return new TransactionsReportingQueryBuilder("domain-registry-alpha", datasetName);
  }

  @Test
  void testAggregateQueryMatch() {
    TransactionsReportingQueryBuilder queryBuilder =
        createQueryBuilder("cloud_sql_icann_reporting");
    assertThat(queryBuilder.getReportQuery(yearMonth))
        .isEqualTo(
            "#standardSQL\n"
                + "SELECT * FROM "
                + "`domain-registry-alpha.cloud_sql_icann_reporting"
                + ".transactions_report_aggregation_201709`");
  }

  @Test
  void testIntermediaryQueryMatch() {
    ImmutableList<String> expectedQueryNames =
        ImmutableList.of(
            TransactionsReportingQueryBuilder.REGISTRAR_IANA_ID,
            TransactionsReportingQueryBuilder.TOTAL_DOMAINS,
            TransactionsReportingQueryBuilder.TOTAL_NAMESERVERS,
            TransactionsReportingQueryBuilder.TRANSACTION_COUNTS,
            TransactionsReportingQueryBuilder.TRANSACTION_TRANSFER_LOSING,
            TransactionsReportingQueryBuilder.ATTEMPTED_ADDS,
            TransactionsReportingQueryBuilder.TRANSACTIONS_REPORT_AGGREGATION);

    TransactionsReportingQueryBuilder queryBuilder =
        createQueryBuilder("cloud_sql_icann_reporting");
    ImmutableMap<String, String> actualQueries = queryBuilder.getViewQueryMap(yearMonth);
    for (String queryName : expectedQueryNames) {
      String actualTableName = String.format("%s_201709", queryName);
      String testFilename = String.format("%s_test.sql", queryName);
      assertWithMessage("Query expected in test data file %s differs", testFilename)
          .that(actualQueries.get(actualTableName))
          .isEqualTo(ReportingTestData.loadFile(testFilename));
    }
  }
}


