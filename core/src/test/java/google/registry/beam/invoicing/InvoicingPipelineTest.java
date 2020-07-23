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

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.beam.TestPipelineExtension;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.ResourceUtils;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map.Entry;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.PCollection;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InvoicingPipeline}. */
@RunWith(JUnit4.class)
public class InvoicingPipelineTest {

  private static PipelineOptions pipelineOptions;

  @BeforeClass
  public static void initializePipelineOptions() {
    pipelineOptions = PipelineOptionsFactory.create();
    pipelineOptions.setRunner(DirectRunner.class);
  }

  @Rule
  public final transient TestPipelineExtension p =
      TestPipelineExtension.fromOptions(pipelineOptions);

  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  private InvoicingPipeline invoicingPipeline;

  @Before
  public void initializePipeline() throws IOException {
    File beamTempFolder = tempFolder.newFolder();
    String beamTempFolderPath = beamTempFolder.getAbsolutePath();
    invoicingPipeline = new InvoicingPipeline(
        "test-project",
        beamTempFolderPath,
        beamTempFolderPath + "/templates/invoicing",
        beamTempFolderPath + "/staging",
        tempFolder.getRoot().getAbsolutePath(),
        "REG-INV",
        GoogleCredentialsBundle.create(GoogleCredentials.create(null))
    );
  }

  private ImmutableList<BillingEvent> getInputEvents() {
    return ImmutableList.of(
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
            "SUNRISE ANCHOR_TENANT"));
  }

  /** Returns a map from filename to expected contents for detail reports. */
  private ImmutableMap<String, ImmutableList<String>> getExpectedDetailReportMap() {
    return ImmutableMap.of(
        "invoice_details_2017-10_theRegistrar_test.csv",
        ImmutableList.of(
            "1,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,theRegistrar,234,,"
                + "test,RENEW,mydomain2.test,REPO-ID,3,USD,20.50,",
            "1,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,theRegistrar,234,,"
                + "test,RENEW,mydomain.test,REPO-ID,3,USD,20.50,"),
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
  }

  private ImmutableList<String> getExpectedInvoiceOutput() {
    return ImmutableList.of(
        "2017-10-01,2020-09-30,234,41.00,USD,10125,1,PURCHASE,theRegistrar - test,2,"
            + "RENEW | TLD: test | TERM: 3-year,20.50,USD,",
        "2017-10-01,2022-09-30,234,70.75,JPY,10125,1,PURCHASE,theRegistrar - hello,1,"
            + "CREATE | TLD: hello | TERM: 5-year,70.75,JPY,",
        "2017-10-01,2018-09-30,456,20.50,USD,10125,1,PURCHASE,bestdomains - test,1,"
            + "RENEW | TLD: test | TERM: 1-year,20.50,USD,116688");
  }

  @Test
  public void testEndToEndPipeline_generatesExpectedFiles() throws Exception {
    ImmutableList<BillingEvent> inputRows = getInputEvents();
    PCollection<BillingEvent> input = p.apply(Create.of(inputRows));
    invoicingPipeline.applyTerminalTransforms(input, StaticValueProvider.of("2017-10"));
    p.run();

    for (Entry<String, ImmutableList<String>> entry : getExpectedDetailReportMap().entrySet()) {
      ImmutableList<String> detailReport = resultFileContents(entry.getKey());
      assertThat(detailReport.get(0))
          .isEqualTo("id,billingTime,eventTime,registrarId,billingId,poNumber,tld,action,"
              + "domain,repositoryId,years,currency,amount,flags");
      assertThat(detailReport.subList(1, detailReport.size()))
          .containsExactlyElementsIn(entry.getValue());
    }

    ImmutableList<String> overallInvoice = resultFileContents("REG-INV-2017-10.csv");
    assertThat(overallInvoice.get(0))
        .isEqualTo(
            "StartDate,EndDate,ProductAccountKey,Amount,AmountCurrency,BillingProductCode,"
                + "SalesChannel,LineItemType,UsageGroupingKey,Quantity,Description,UnitPrice,"
                + "UnitPriceCurrency,PONumber");
    assertThat(overallInvoice.subList(1, overallInvoice.size()))
        .containsExactlyElementsIn(getExpectedInvoiceOutput());
  }

  /** Returns the text contents of a file under the beamBucket/results directory. */
  private ImmutableList<String> resultFileContents(String filename) throws Exception {
    File resultFile =
        new File(
            String.format(
                "%s/invoices/2017-10/%s", tempFolder.getRoot().getAbsolutePath(), filename));
    return ImmutableList.copyOf(
        ResourceUtils.readResourceUtf8(resultFile.toURI().toURL()).split("\n"));
  }
}
