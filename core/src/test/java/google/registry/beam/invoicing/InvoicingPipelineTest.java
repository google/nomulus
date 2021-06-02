// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.invoicing;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.beam.TestPipelineExtension;
import google.registry.testing.TestDataHelper;
import google.registry.util.ResourceUtils;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map.Entry;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.PCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link InvoicingPipeline}. */
class InvoicingPipelineTest {

  private static final String BILLING_BUCKET_URL = "billing_bucket";
  private static final String YEAR_MONTH = "2017-10";
  private static final String INVOICE_FILE_PREFIX = "REG-INV";

  private static final ImmutableList<BillingEvent> INPUT_EVENTS =
      ImmutableList.of(
          BillingEvent.create(
              1,
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              "theRegistrar",
              "234",
              "",
              "test",
              "RENEW",
              "mydomain.test",
              "REPO-ID",
              3,
              "USD",
              20.5,
              ""),
          BillingEvent.create(
              1,
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              "theRegistrar",
              "234",
              "",
              "test",
              "RENEW",
              "mydomain2.test",
              "REPO-ID",
              3,
              "USD",
              20.5,
              ""),
          BillingEvent.create(
              1,
              ZonedDateTime.of(2017, 10, 2, 0, 0, 0, 0, ZoneId.of("UTC")),
              ZonedDateTime.of(2017, 9, 29, 0, 0, 0, 0, ZoneId.of("UTC")),
              "theRegistrar",
              "234",
              "",
              "hello",
              "CREATE",
              "mydomain3.hello",
              "REPO-ID",
              5,
              "JPY",
              70.75,
              ""),
          BillingEvent.create(
              1,
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              "bestdomains",
              "456",
              "116688",
              "test",
              "RENEW",
              "mydomain4.test",
              "REPO-ID",
              1,
              "USD",
              20.5,
              ""),
          BillingEvent.create(
              1,
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              "anotherRegistrar",
              "789",
              "",
              "test",
              "CREATE",
              "mydomain5.test",
              "REPO-ID",
              1,
              "USD",
              0,
              "SUNRISE ANCHOR_TENANT"),
          BillingEvent.create(
              1,
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              "theRegistrar",
              "234",
              "",
              "test",
              "SERVER_STATUS",
              "locked.test",
              "REPO-ID",
              0,
              "USD",
              0,
              ""),
          BillingEvent.create(
              1,
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              ZonedDateTime.of(2017, 10, 4, 0, 0, 0, 0, ZoneId.of("UTC")),
              "theRegistrar",
              "234",
              "",
              "test",
              "SERVER_STATUS",
              "update-prohibited.test",
              "REPO-ID",
              0,
              "USD",
              20,
              ""));

  private static final ImmutableMap<String, ImmutableList<String>> EXPECTED_DETAILED_REPORT_MAP =
      ImmutableMap.of(
          "invoice_details_2017-10_theRegistrar_test.csv",
          ImmutableList.of(
              "1,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,theRegistrar,234,,"
                  + "test,RENEW,mydomain2.test,REPO-ID,3,USD,20.50,",
              "1,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,theRegistrar,234,,"
                  + "test,RENEW,mydomain.test,REPO-ID,3,USD,20.50,",
              "1,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,theRegistrar,234,,"
                  + "test,SERVER_STATUS,update-prohibited.test,REPO-ID,0,USD,20.00,",
              "1,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,theRegistrar,234,,"
                  + "test,SERVER_STATUS,locked.test,REPO-ID,0,USD,0.00,"),
          "invoice_details_2017-10_theRegistrar_hello.csv",
          ImmutableList.of(
              "1,2017-10-02 00:00:00 UTC,2017-09-29 00:00:00 UTC,theRegistrar,234,,"
                  + "hello,CREATE,mydomain3.hello,REPO-ID,5,JPY,70.75,"),
          "invoice_details_2017-10_bestdomains_test.csv",
          ImmutableList.of(
              "1,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,bestdomains,456,116688,"
                  + "test,RENEW,mydomain4.test,REPO-ID,1,USD,20.50,"),
          "invoice_details_2017-10_anotherRegistrar_test.csv",
          ImmutableList.of(
              "1,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,anotherRegistrar,789,,"
                  + "test,CREATE,mydomain5.test,REPO-ID,1,USD,0.00,SUNRISE ANCHOR_TENANT"));

  private static final ImmutableList<String> EXPECTED_INVOICE_OUTPUT =
      ImmutableList.of(
          "2017-10-01,2020-09-30,234,41.00,USD,10125,1,PURCHASE,theRegistrar - test,2,"
              + "RENEW | TLD: test | TERM: 3-year,20.50,USD,",
          "2017-10-01,2022-09-30,234,70.75,JPY,10125,1,PURCHASE,theRegistrar - hello,1,"
              + "CREATE | TLD: hello | TERM: 5-year,70.75,JPY,",
          "2017-10-01,,234,20.00,USD,10125,1,PURCHASE,theRegistrar - test,1,"
              + "SERVER_STATUS | TLD: test | TERM: 0-year,20.00,USD,",
          "2017-10-01,2018-09-30,456,20.50,USD,10125,1,PURCHASE,bestdomains - test,1,"
              + "RENEW | TLD: test | TERM: 1-year,20.50,USD,116688");

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @TempDir Path tmpDir;

  private final InvoicingPipelineOptions options =
      PipelineOptionsFactory.create().as(InvoicingPipelineOptions.class);

  private File billingBucketUrl;
  private PCollection<BillingEvent> billingEvents;

  @BeforeEach
  void beforeEach() throws Exception {
    billingBucketUrl = Files.createDirectory(tmpDir.resolve(BILLING_BUCKET_URL)).toFile();
    options.setBillingBucketUrl(billingBucketUrl.getAbsolutePath());
    options.setYearMonth(YEAR_MONTH);
    options.setInvoiceFilePrefix(INVOICE_FILE_PREFIX);
    billingEvents =
        pipeline.apply(Create.of(INPUT_EVENTS).withCoder(SerializableCoder.of(BillingEvent.class)));
  }

  @Test
  void testSuccess_makeQuery() {
    String query = InvoicingPipeline.makeQuery("2017-10", "my-project-id");
    assertThat(query)
        .isEqualTo(TestDataHelper.loadFile(this.getClass(), "billing_events_test.sql"));
    // This is necessary because the TestPipelineExtension verifies that the pipelien is run.
    pipeline.run();
  }

  @Test
  void testSuccess_saveInvoiceCsv() throws Exception {
    InvoicingPipeline.saveInvoiceCsv(billingEvents, options);
    pipeline.run().waitUntilFinish();
    ImmutableList<String> overallInvoice = resultFileContents("REG-INV-2017-10.csv");
    assertThat(overallInvoice.get(0))
        .isEqualTo(
            "StartDate,EndDate,ProductAccountKey,Amount,AmountCurrency,BillingProductCode,"
                + "SalesChannel,LineItemType,UsageGroupingKey,Quantity,Description,UnitPrice,"
                + "UnitPriceCurrency,PONumber");
    assertThat(overallInvoice.subList(1, overallInvoice.size()))
        .containsExactlyElementsIn(EXPECTED_INVOICE_OUTPUT);
  }

  @Test
  void testSuccess_saveDetailedCsv() throws Exception {
    InvoicingPipeline.saveDetailedCsv(billingEvents, options);
    pipeline.run().waitUntilFinish();
    for (Entry<String, ImmutableList<String>> entry : EXPECTED_DETAILED_REPORT_MAP.entrySet()) {
      ImmutableList<String> detailReport = resultFileContents(entry.getKey());
      assertThat(detailReport.get(0))
          .isEqualTo(
              "id,billingTime,eventTime,registrarId,billingId,poNumber,tld,action,"
                  + "domain,repositoryId,years,currency,amount,flags");
      assertThat(detailReport.subList(1, detailReport.size()))
          .containsExactlyElementsIn(entry.getValue());
    }
  }

  /** Returns the text contents of a file under the beamBucket/results directory. */
  private ImmutableList<String> resultFileContents(String filename) throws Exception {
    File resultFile =
        new File(
            String.format(
                "%s/invoices/2017-10/%s", billingBucketUrl.getAbsolutePath().toString(), filename));
    return ImmutableList.copyOf(
        ResourceUtils.readResourceUtf8(resultFile.toURI().toURL()).split("\n"));
  }
}
