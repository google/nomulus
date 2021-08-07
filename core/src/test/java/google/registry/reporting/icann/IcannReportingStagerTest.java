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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableTable;
import com.google.common.util.concurrent.ListenableFuture;
import google.registry.bigquery.BigqueryConnection;
import google.registry.bigquery.BigqueryConnection.DestinationTable;
import google.registry.bigquery.BigqueryUtils.TableType;
import google.registry.gcs.GcsUtils;
import google.registry.reporting.icann.IcannReportingModule.ReportType;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeResponse;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.reporting.icann.IcannReportingStager}. */
class IcannReportingStagerTest {

  private BigqueryConnection bigquery = mock(BigqueryConnection.class);
  FakeResponse response = new FakeResponse();
  private YearMonth yearMonth = new YearMonth(2017, 6);
  private String subdir = "icann/monthly/2017-06";
  private GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withLocalModules().build();

  private IcannReportingStager createStager() {
    IcannReportingStager action = new IcannReportingStager();
    ActivityReportingQueryBuilder activityBuilder = new ActivityReportingQueryBuilder();
    activityBuilder.projectId = "test-project";
    activityBuilder.dnsCountQueryCoordinator = new BasicDnsCountQueryCoordinator(null);
    action.activityQueryBuilder = activityBuilder;
    TransactionsReportingQueryBuilder transactionsBuilder = new TransactionsReportingQueryBuilder();
    transactionsBuilder.projectId = "test-project";
    action.transactionsQueryBuilder = transactionsBuilder;
    action.reportingBucket = "test-bucket";
    action.bigquery = bigquery;
    action.gcsUtils = gcsUtils;
    return action;
  }

  private void setUpBigquery() {
    when(bigquery.startQuery(any(String.class), any(DestinationTable.class)))
        .thenReturn(fakeFuture());
    DestinationTable.Builder tableBuilder =
        new DestinationTable.Builder()
            .datasetId("testdataset")
            .type(TableType.TABLE)
            .name("tablename")
            .overwrite(true);
    when(bigquery.buildDestinationTable(any(String.class))).thenReturn(tableBuilder);
  }

  @Test
  void testRunSuccess_activityReport() throws Exception {
    setUpBigquery();
    ImmutableTable<Integer, TableFieldSchema, Object> activityReportTable =
        new ImmutableTable.Builder<Integer, TableFieldSchema, Object>()
            .put(1, new TableFieldSchema().setName("tld"), "fooTld")
            .put(1, new TableFieldSchema().setName("fooField"), "12")
            .put(1, new TableFieldSchema().setName("barField"), "34")
            .put(2, new TableFieldSchema().setName("tld"), "barTld")
            .put(2, new TableFieldSchema().setName("fooField"), "56")
            .put(2, new TableFieldSchema().setName("barField"), "78")
            .build();
    when(bigquery.queryToLocalTableSync(any(String.class))).thenReturn(activityReportTable);
    IcannReportingStager stager = createStager();
    stager.stageReports(yearMonth, subdir, ReportType.ACTIVITY);

    String expectedReport1 = "fooField,barField\r\n12,34";
    String expectedReport2 = "fooField,barField\r\n56,78";
    byte[] generatedFile1 =
        gcsUtils.readBytesFrom(
            BlobId.of("test-bucket/icann/monthly/2017-06", "fooTld-activity-201706.csv"));
    assertThat(new String(generatedFile1, UTF_8)).isEqualTo(expectedReport1);
    byte[] generatedFile2 =
        gcsUtils.readBytesFrom(
            BlobId.of("test-bucket/icann/monthly/2017-06", "barTld-activity-201706.csv"));
    assertThat(new String(generatedFile2, UTF_8)).isEqualTo(expectedReport2);
  }

  @Test
  void testRunSuccess_transactionsReport() throws Exception {
    setUpBigquery();
    /*
      The fake table result looks like:
         tld     registrar iana   field
       1 fooTld  reg1      123    10
       2 fooTld  reg2      456    20
       3 barTld  reg1      123    30
    */
    ImmutableTable<Integer, TableFieldSchema, Object> transactionReportTable =
        new ImmutableTable.Builder<Integer, TableFieldSchema, Object>()
            .put(1, new TableFieldSchema().setName("tld"), "fooTld")
            .put(1, new TableFieldSchema().setName("registrar"), "\"reg1\"")
            .put(1, new TableFieldSchema().setName("iana"), "123")
            .put(1, new TableFieldSchema().setName("field"), "10")
            .put(2, new TableFieldSchema().setName("tld"), "fooTld")
            .put(2, new TableFieldSchema().setName("registrar"), "\"reg2\"")
            .put(2, new TableFieldSchema().setName("iana"), "456")
            .put(2, new TableFieldSchema().setName("field"), "20")
            .put(3, new TableFieldSchema().setName("tld"), "barTld")
            .put(3, new TableFieldSchema().setName("registrar"), "\"reg1\"")
            .put(3, new TableFieldSchema().setName("iana"), "123")
            .put(3, new TableFieldSchema().setName("field"), "30")
            .build();
    when(bigquery.queryToLocalTableSync(any(String.class))).thenReturn(transactionReportTable);
    IcannReportingStager stager = createStager();
    stager.stageReports(yearMonth, subdir, ReportType.TRANSACTIONS);

    String expectedReport1 =
        "registrar,iana,field\r\n\"reg1\",123,10\r\n\"reg2\",456,20\r\nTotals,,30";
    String expectedReport2 = "registrar,iana,field\r\n\"reg1\",123,30\r\nTotals,,30";
    byte[] generatedFile1 =
        gcsUtils.readBytesFrom(
            BlobId.of("test-bucket/icann/monthly/2017-06", "fooTld-transactions-201706.csv"));
    assertThat(new String(generatedFile1, UTF_8)).isEqualTo(expectedReport1);
    byte[] generatedFile2 =
        gcsUtils.readBytesFrom(
            BlobId.of("test-bucket/icann/monthly/2017-06", "barTld-transactions-201706.csv"));
    assertThat(new String(generatedFile2, UTF_8)).isEqualTo(expectedReport2);
  }

  @Test
  void testRunSuccess_createAndUploadManifest() throws Exception {
    IcannReportingStager stager = createStager();
    ImmutableList<String> filenames =
        ImmutableList.of("fooTld-transactions-201706.csv", "barTld-activity-201706.csv");
    stager.createAndUploadManifest(subdir, filenames);

    String expectedManifest = "fooTld-transactions-201706.csv\nbarTld-activity-201706.csv\n";
    byte[] generatedManifest =
        gcsUtils.readBytesFrom(BlobId.of("test-bucket/icann/monthly/2017-06", "MANIFEST.txt"));
    assertThat(new String(generatedManifest, UTF_8)).isEqualTo(expectedManifest);
  }

  private ListenableFuture<DestinationTable> fakeFuture() {
    return new ListenableFuture<DestinationTable>() {
      @Override
      public void addListener(Runnable runnable, Executor executor) {
        // No-op
      }

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
      }

      @Override
      public boolean isCancelled() {
        return false;
      }

      @Override
      public boolean isDone() {
        return false;
      }

      @Override
      public DestinationTable get() {
        return null;
      }

      @Override
      public DestinationTable get(long timeout, TimeUnit unit) {
        return null;
      }
    };
  }
}
