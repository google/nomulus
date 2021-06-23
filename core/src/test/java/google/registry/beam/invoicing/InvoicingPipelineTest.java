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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.TransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TestDataHelper;
import google.registry.util.ResourceUtils;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Optional;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.PCollection;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
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
              2,
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
              3,
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
              70.0,
              ""),
          BillingEvent.create(
              4,
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
              5,
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
              0.0,
              "SUNRISE ANCHOR_TENANT"),
          BillingEvent.create(
              6,
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
              0.0,
              ""),
          BillingEvent.create(
              7,
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
              20.0,
              ""));

  private static final ImmutableMap<String, ImmutableList<String>> EXPECTED_DETAILED_REPORT_MAP =
      ImmutableMap.of(
          "invoice_details_2017-10_theRegistrar_test.csv",
          ImmutableList.of(
              "2,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,theRegistrar,234,,"
                  + "test,RENEW,mydomain2.test,REPO-ID,3,USD,20.50,",
              "1,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,theRegistrar,234,,"
                  + "test,RENEW,mydomain.test,REPO-ID,3,USD,20.50,",
              "7,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,theRegistrar,234,,"
                  + "test,SERVER_STATUS,update-prohibited.test,REPO-ID,0,USD,20.00,",
              "6,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,theRegistrar,234,,"
                  + "test,SERVER_STATUS,locked.test,REPO-ID,0,USD,0.00,"),
          "invoice_details_2017-10_theRegistrar_hello.csv",
          ImmutableList.of(
              "3,2017-10-02 00:00:00 UTC,2017-09-29 00:00:00 UTC,theRegistrar,234,,"
                  + "hello,CREATE,mydomain3.hello,REPO-ID,5,JPY,70.00,"),
          "invoice_details_2017-10_bestdomains_test.csv",
          ImmutableList.of(
              "4,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,bestdomains,456,116688,"
                  + "test,RENEW,mydomain4.test,REPO-ID,1,USD,20.50,"),
          "invoice_details_2017-10_anotherRegistrar_test.csv",
          ImmutableList.of(
              "5,2017-10-04 00:00:00 UTC,2017-10-04 00:00:00 UTC,anotherRegistrar,789,,"
                  + "test,CREATE,mydomain5.test,REPO-ID,1,USD,0.00,SUNRISE ANCHOR_TENANT"));

  private static final ImmutableList<String> EXPECTED_INVOICE_OUTPUT =
      ImmutableList.of(
          "2017-10-01,2020-09-30,234,41.00,USD,10125,1,PURCHASE,theRegistrar - test,2,"
              + "RENEW | TLD: test | TERM: 3-year,20.50,USD,",
          "2017-10-01,2022-09-30,234,70.00,JPY,10125,1,PURCHASE,theRegistrar - hello,1,"
              + "CREATE | TLD: hello | TERM: 5-year,70.00,JPY,",
          "2017-10-01,,234,20.00,USD,10125,1,PURCHASE,theRegistrar - test,1,"
              + "SERVER_STATUS | TLD: test | TERM: 0-year,20.00,USD,",
          "2017-10-01,2018-09-30,456,20.50,USD,10125,1,PURCHASE,bestdomains - test,1,"
              + "RENEW | TLD: test | TERM: 1-year,20.50,USD,116688");

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore = new DatastoreEntityExtension();

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @RegisterExtension
  final JpaIntegrationTestExtension database =
      new JpaTestRules.Builder().withClock(new FakeClock()).buildIntegrationTestRule();

  @TempDir Path tmpDir;

  private final InvoicingPipelineOptions options =
      PipelineOptionsFactory.create().as(InvoicingPipelineOptions.class);

  private File billingBucketUrl;
  private PCollection<BillingEvent> billingEvents;
  TransactionManager tm;

  @BeforeEach
  void beforeEach() throws Exception {
    tm = tm();
    TransactionManagerFactory.setTm(jpaTm());
    billingBucketUrl = Files.createDirectory(tmpDir.resolve(BILLING_BUCKET_URL)).toFile();
    options.setBillingBucketUrl(billingBucketUrl.getAbsolutePath());
    options.setYearMonth(YEAR_MONTH);
    options.setInvoiceFilePrefix(INVOICE_FILE_PREFIX);
    billingEvents =
        pipeline.apply(Create.of(INPUT_EVENTS).withCoder(SerializableCoder.of(BillingEvent.class)));
  }

  @AfterEach
  void afterEach() {
    TransactionManagerFactory.setTm(tm);
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
  void testSuccess_readFromCloudSql() throws Exception {
    setupCloudSql();

    Read<Object[], BillingEvent> read =
        RegistryJpaIO.read(
            "select b, r from BillingEvent b join Registrar r on b.clientId = r.clientIdentifier"
                + " join Domain d on b.domainRepoId = d.repoId join Tld t on t.tldStrId = d.tld"
                + " and r.billingIdentifier != null and r.type = 'REAL' and t.invoicingEnabled ="
                + " true",
            false,
            (Object[] row) -> {
              google.registry.model.billing.BillingEvent.OneTime oneTime =
                  (google.registry.model.billing.BillingEvent.OneTime) row[0];
              Registrar registrar = (Registrar) row[1];
              return BillingEvent.create(
                  oneTime.getId(),
                  ZonedDateTime.ofInstant(
                      Instant.ofEpochMilli(oneTime.getBillingTime().getMillis()), ZoneId.of("UTC")),
                  ZonedDateTime.ofInstant(
                      Instant.ofEpochMilli(oneTime.getEventTime().getMillis()), ZoneId.of("UTC")),
                  registrar.getClientId(),
                  registrar.getBillingIdentifier().toString(),
                  registrar.getPoNumber().orElse(""),
                  oneTime.getTargetId().substring(oneTime.getTargetId().lastIndexOf('.') + 1),
                  oneTime.getReason().toString(),
                  oneTime.getTargetId(),
                  "REPO-ID",
                  Optional.ofNullable(oneTime.getPeriodYears()).orElse(0),
                  oneTime.getCost().getCurrencyUnit().toString(),
                  oneTime.getCost().getAmount().doubleValue(),
                  String.join(
                      " ",
                      oneTime.getFlags().stream().map(Flag::toString).collect(toImmutableSet())));
            });
    PCollection<BillingEvent> billingEvents = pipeline.apply(read);
    PAssert.that(billingEvents).containsInAnyOrder(INPUT_EVENTS);
    pipeline.run().waitUntilFinish();
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

  private void setupCloudSql() {
    // Populate billing events in Cloud SQL to match existing test data for Datastore
    persistNewRegistrar("NewRegistrar");
    persistNewRegistrar("TheRegistrar");
    Registrar registrar1 = persistNewRegistrar("theRegistrar");
    registrar1 = registrar1.asBuilder().setBillingIdentifier(234L).build();
    persistResource(registrar1);
    Registrar registrar2 = persistNewRegistrar("bestdomains");
    registrar2 =
        registrar2
            .asBuilder()
            .setBillingIdentifier(456L)
            .setPoNumber(Optional.of("116688"))
            .build();
    persistResource(registrar2);
    Registrar registrar3 = persistNewRegistrar("anotherRegistrar");
    registrar3 = registrar3.asBuilder().setBillingIdentifier(789L).build();
    persistResource(registrar3);

    Registry test =
        newRegistry("test", "_TEST", ImmutableSortedMap.of(START_OF_TIME, GENERAL_AVAILABILITY))
            .asBuilder()
            .setInvoicingEnabled(true)
            .build();
    persistResource(test);
    Registry hello =
        newRegistry("hello", "_HELLO", ImmutableSortedMap.of(START_OF_TIME, GENERAL_AVAILABILITY))
            .asBuilder()
            .setInvoicingEnabled(true)
            .build();
    persistResource(hello);

    DomainBase domain1 = persistActiveDomain("mydomain.test");
    DomainBase domain2 = persistActiveDomain("mydomain2.test");
    DomainBase domain3 = persistActiveDomain("mydomain3.hello");
    DomainBase domain4 = persistActiveDomain("mydomain4.test");
    DomainBase domain5 = persistActiveDomain("mydomain5.test");
    DomainBase domain6 = persistActiveDomain("locked.test");
    DomainBase domain7 = persistActiveDomain("update-prohibited.test");

    persistOneTimeBillingEvent(
        1, domain1, registrar1, Reason.RENEW, 3, Money.of(CurrencyUnit.USD, 20.5));
    persistOneTimeBillingEvent(
        2, domain2, registrar1, Reason.RENEW, 3, Money.of(CurrencyUnit.USD, 20.5));
    persistOneTimeBillingEvent(
        3,
        domain3,
        registrar1,
        Reason.CREATE,
        5,
        Money.ofMajor(CurrencyUnit.JPY, 70),
        DateTime.parse("2017-09-29T00:00:00.0Z"),
        DateTime.parse("2017-10-02T00:00:00.0Z"));
    DateTime.parse("2017-09-29T00:00:00.0Z");
    persistOneTimeBillingEvent(
        4, domain4, registrar2, Reason.RENEW, 1, Money.of(CurrencyUnit.USD, 20.5));
    persistOneTimeBillingEvent(
        5,
        domain5,
        registrar3,
        Reason.CREATE,
        1,
        Money.of(CurrencyUnit.USD, 0),
        DateTime.parse("2017-10-04T00:00:00.0Z"),
        DateTime.parse("2017-10-04T00:00:00.0Z"),
        Flag.SUNRISE,
        Flag.ANCHOR_TENANT);
    persistOneTimeBillingEvent(
        6, domain6, registrar1, Reason.SERVER_STATUS, 0, Money.of(CurrencyUnit.USD, 0));
    persistOneTimeBillingEvent(
        7, domain7, registrar1, Reason.SERVER_STATUS, 0, Money.of(CurrencyUnit.USD, 20));

    // Add billing event for a non-billable registrar
    Registrar registrar4 = persistNewRegistrar("noBillRegistrar");
    registrar4 = registrar4.asBuilder().setBillingIdentifier(null).build();
    persistResource(registrar4);
    DomainBase domain8 = persistActiveDomain("non-billable.test");
    persistOneTimeBillingEvent(
        8, domain8, registrar4, Reason.RENEW, 3, Money.of(CurrencyUnit.USD, 20.5));

    // Add billing event for a non-real registrar
    Registrar registrar5 = persistNewRegistrar("notRealRegistrar");
    registrar5 =
        registrar5
            .asBuilder()
            .setIanaIdentifier(null)
            .setBillingIdentifier(456L)
            .setType(Registrar.Type.OTE)
            .build();
    persistResource(registrar5);
    DomainBase domain9 = persistActiveDomain("not-real.test");
    persistOneTimeBillingEvent(
        9, domain9, registrar5, Reason.RENEW, 3, Money.of(CurrencyUnit.USD, 20.5));

    // Add billing event for a non-invoicing TLD
    createTld("nobill");
    DomainBase domain10 = persistActiveDomain("test.nobill");
    persistOneTimeBillingEvent(
        10, domain10, registrar1, Reason.RENEW, 3, Money.of(CurrencyUnit.USD, 20.5));
  }

  private DomainHistory persistDomainHistory(DomainBase domainBase, Registrar registrar) {
    DomainHistory domainHistory =
        new DomainHistory.Builder()
            .setType(HistoryEntry.Type.DOMAIN_RENEW)
            .setModificationTime(DateTime.parse("2017-10-04T00:00:00.0Z"))
            .setDomain(domainBase)
            .setClientId(registrar.getClientId())
            .build();
    return persistResource(domainHistory);
  }

  private void persistOneTimeBillingEvent(
      int id, DomainBase domainBase, Registrar registrar, Reason reason, int years, Money money) {
    persistOneTimeBillingEvent(
        id,
        domainBase,
        registrar,
        reason,
        years,
        money,
        DateTime.parse("2017-10-04T00:00:00.0Z"),
        DateTime.parse("2017-10-04T00:00:00.0Z"));
  }

  private void persistOneTimeBillingEvent(
      int id,
      DomainBase domainBase,
      Registrar registrar,
      Reason reason,
      int years,
      Money money,
      DateTime eventTime,
      DateTime billingTime,
      Flag... flags) {
    google.registry.model.billing.BillingEvent.OneTime.Builder billingEventBuilder =
        new OneTime()
            .asBuilder()
            .setId(id)
            .setBillingTime(billingTime)
            .setEventTime(eventTime)
            .setClientId(registrar.getClientId())
            .setReason(reason)
            .setTargetId(domainBase.getDomainName())
            .setDomainRepoId("REPO-ID")
            .setCost(money)
            .setFlags(Arrays.stream(flags).collect(toImmutableSet()))
            .setParent(persistDomainHistory(domainBase, registrar));

    if (years > 0) {
      billingEventBuilder.setPeriodYears(years);
    }

    persistResource(billingEventBuilder.build());
  }
}
