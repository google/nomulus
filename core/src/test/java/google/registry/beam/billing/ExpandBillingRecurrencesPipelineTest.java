// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.billing;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.model.domain.Period.Unit.YEARS;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_AUTORENEW;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.model.reporting.HistoryEntryDao.loadHistoryObjectsForResource;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.assertBillingEventsForResource;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.common.Cursor;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.tld.Tld;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.apache.beam.runners.direct.DirectOptions;
import org.apache.beam.sdk.Pipeline.PipelineExecutionException;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.hibernate.cfg.AvailableSettings;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link ExpandBillingRecurrencesPipeline}. */
public class ExpandBillingRecurrencesPipelineTest {

  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

  private final FakeClock clock = new FakeClock(DateTime.parse("2021-02-02T00:00:05Z"));

  private final DateTime startTime = DateTime.parse("2021-02-01TZ");

  private DateTime endTime = DateTime.parse("2021-02-02TZ");

  private final Cursor cursor = Cursor.createGlobal(RECURRING_BILLING, startTime);

  private Domain domain;

  private BillingRecurrence billingRecurrence;

  private final TestOptions options = PipelineOptionsFactory.create().as(TestOptions.class);

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder()
          .withClock(clock)
          .withProperty(
              AvailableSettings.ISOLATION,
              TransactionIsolationLevel.TRANSACTION_SERIALIZABLE.name())
          .buildIntegrationTestExtension();

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @BeforeEach
  void beforeEach() {
    // Set up the pipeline.
    options.setStartTime(DATE_TIME_FORMATTER.print(startTime));
    options.setEndTime(DATE_TIME_FORMATTER.print(endTime));
    options.setIsDryRun(false);
    options.setAdvanceCursor(true);
    tm().transact(() -> tm().put(cursor));

    // Set up the database.
    createTld("tld");
    billingRecurrence = createDomainAtTime("example.tld", startTime.minusYears(1).plusHours(12));
    domain = ForeignKeyUtils.loadResource(Domain.class, "example.tld", clock.nowUtc()).get();
  }

  @Test
  void testFailure_endTimeAfterNow() {
    options.setEndTime(DATE_TIME_FORMATTER.print(clock.nowUtc().plusMillis(1)));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, this::runPipeline);
    assertThat(thrown)
        .hasMessageThat()
        .contains("End time 2021-02-02T00:00:05.001Z must be at or before now");
  }

  @Test
  void testFailure_endTimeBeforeStartTime() {
    options.setEndTime(DATE_TIME_FORMATTER.print(startTime.minusMillis(1)));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, this::runPipeline);
    assertThat(thrown)
        .hasMessageThat()
        .contains("[2021-02-01T00:00:00.000Z, 2021-01-31T23:59:59.999Z)");
  }

  @Test
  void testSuccess_expandSingleEvent() {
    runPipeline();

    // Assert about DomainHistory.
    assertAutoRenewDomainHistories(defaultDomainHistory());

    // Assert about BillingEvents.
    assertBillingEventsForResource(
        domain,
        defaultOneTime(getOnlyAutoRenewHistory()),
        billingRecurrence
            .asBuilder()
            .setRecurrenceLastExpansion(domain.getCreationTime().plusYears(1))
            .build());

    // Assert about Cursor.
    assertCursorAt(endTime);
  }

  @Test
  void testSuccess_expandSingleEvent_deletedDuringGracePeriod() {
    domain = persistResource(domain.asBuilder().setDeletionTime(endTime.minusHours(2)).build());
    billingRecurrence =
        persistResource(
            billingRecurrence.asBuilder().setRecurrenceEndTime(endTime.minusHours(2)).build());
    runPipeline();

    // Assert about DomainHistory, no transaction record should have been written.
    assertAutoRenewDomainHistories(
        defaultDomainHistory().asBuilder().setDomainTransactionRecords(ImmutableSet.of()).build());

    // Assert about BillingEvents.
    assertBillingEventsForResource(
        domain,
        defaultOneTime(getOnlyAutoRenewHistory()),
        billingRecurrence
            .asBuilder()
            .setRecurrenceLastExpansion(domain.getCreationTime().plusYears(1))
            .build());

    // Assert about Cursor.
    assertCursorAt(endTime);
  }

  @Test
  void testFailure_expandSingleEvent_cursorNotAtStartTime() {
    tm().transact(() -> tm().put(Cursor.createGlobal(RECURRING_BILLING, startTime.plusMillis(1))));

    PipelineExecutionException thrown =
        assertThrows(PipelineExecutionException.class, this::runPipeline);

    assertThat(thrown).hasCauseThat().hasMessageThat().contains("Current cursor position");

    // Assert about DomainHistory.
    assertAutoRenewDomainHistories(defaultDomainHistory());

    // Assert about BillingEvents.
    assertBillingEventsForResource(
        domain,
        defaultOneTime(getOnlyAutoRenewHistory()),
        billingRecurrence
            .asBuilder()
            .setRecurrenceLastExpansion(domain.getCreationTime().plusYears(1))
            .build());

    // Assert that the cursor did not change.
    assertCursorAt(startTime.plusMillis(1));
  }

  @Test
  void testSuccess_noExpansion_recurrenceClosedBeforeEventTime() {
    billingRecurrence =
        persistResource(
            billingRecurrence
                .asBuilder()
                .setRecurrenceEndTime(billingRecurrence.getEventTime().minusDays(1))
                .build());
    runPipeline();
    assertNoExpansionsHappened();
  }

  @Test
  void testSuccess_noExpansion_recurrenceClosedBeforeStartTime() {
    billingRecurrence =
        persistResource(
            billingRecurrence.asBuilder().setRecurrenceEndTime(startTime.minusDays(1)).build());
    runPipeline();
    assertNoExpansionsHappened();
  }

  @Test
  void testSuccess_noExpansion_recurrenceClosedBeforeNextExpansion() {
    billingRecurrence =
        persistResource(
            billingRecurrence
                .asBuilder()
                .setEventTime(billingRecurrence.getEventTime().minusYears(1))
                .setRecurrenceEndTime(startTime.plusHours(6))
                .build());
    runPipeline();
    assertNoExpansionsHappened();
  }

  @Test
  void testSuccess_noExpansion_eventTimeAfterEndTime() {
    billingRecurrence =
        persistResource(billingRecurrence.asBuilder().setEventTime(endTime.plusDays(1)).build());
    runPipeline();
    assertNoExpansionsHappened();
  }

  @Test
  void testSuccess_noExpansion_LastExpansionLessThanAYearAgo() {
    billingRecurrence =
        persistResource(
            billingRecurrence
                .asBuilder()
                .setRecurrenceLastExpansion(startTime.minusYears(1).plusDays(1))
                .build());
    runPipeline();
    assertNoExpansionsHappened();
  }

  @Test
  void testSuccess_noExpansion_oneTimeAlreadyExists() {
    DomainHistory history = persistResource(defaultDomainHistory());
    BillingEvent billingEvent = persistResource(defaultOneTime(history));
    runPipeline();

    // Assert about DomainHistory.
    assertAutoRenewDomainHistories(history);

    // Assert about BillingEvents. No expansion happened, so last recurrence expansion time is
    // unchanged.
    assertBillingEventsForResource(domain, billingEvent, billingRecurrence);

    // Assert about Cursor.
    assertCursorAt(endTime);
  }

  @Test
  void testSuccess_expandSingleEvent_dryRun() {
    options.setIsDryRun(true);
    runPipeline();
    assertNoExpansionsHappened(true);
  }

  @Test
  void testSuccess_expandSingleEvent_doesNotAdvanceCursor() {
    options.setAdvanceCursor(false);

    runPipeline();
    // Assert about DomainHistory.
    assertAutoRenewDomainHistories(defaultDomainHistory());

    // Assert about BillingEvents.
    assertBillingEventsForResource(
        domain,
        defaultOneTime(getOnlyAutoRenewHistory()),
        billingRecurrence
            .asBuilder()
            .setRecurrenceLastExpansion(domain.getCreationTime().plusYears(1))
            .build());

    // Assert that the cursor did not move.
    assertCursorAt(startTime);
  }

  // We control the number of threads used in the pipeline to test if the batching behavior works
  // properly. When two threads are used, the two recurrences are processed in different workers and
  // should be processed in parallel.
  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void testSuccess_expandMultipleEvents_multipleDomains(int numOfThreads) {
    createTld("test");
    persistResource(
        Tld.get("test")
            .asBuilder()
            .setPremiumList(persistPremiumList("premium", USD, "other,USD 100"))
            .build());
    DateTime otherCreateTime = startTime.minusYears(1).plusHours(5);
    BillingRecurrence otherBillingRecurrence = createDomainAtTime("other.test", otherCreateTime);
    Domain otherDomain =
        ForeignKeyUtils.loadResource(Domain.class, "other.test", clock.nowUtc()).get();

    options.setTargetParallelism(numOfThreads);
    runPipeline();

    // Assert about DomainHistory.
    DomainHistory history = defaultDomainHistory();
    DomainHistory otherHistory = defaultDomainHistory(otherDomain);
    assertAutoRenewDomainHistories(domain, history);
    assertAutoRenewDomainHistories(otherDomain, otherHistory);

    // Assert about BillingEvents.
    assertBillingEventsForResource(
        domain,
        defaultOneTime(getOnlyAutoRenewHistory()),
        billingRecurrence
            .asBuilder()
            .setRecurrenceLastExpansion(domain.getCreationTime().plusYears(1))
            .build());
    assertBillingEventsForResource(
        otherDomain,
        defaultOneTime(
            otherDomain, getOnlyAutoRenewHistory(otherDomain), otherBillingRecurrence, 100),
        otherBillingRecurrence
            .asBuilder()
            .setRecurrenceLastExpansion(otherDomain.getCreationTime().plusYears(1))
            .build());

    // Assert about Cursor.
    assertCursorAt(endTime);
  }

  @Test
  void testSuccess_expandMultipleEvents_multipleEventTime() {
    clock.advanceBy(Duration.standardDays(365));
    endTime = endTime.plusYears(1);
    options.setEndTime(DATE_TIME_FORMATTER.print(endTime));

    runPipeline();
    // Assert about DomainHistory.
    assertAutoRenewDomainHistories(
        defaultDomainHistory(),
        defaultDomainHistory()
            .asBuilder()
            .setDomainTransactionRecords(
                ImmutableSet.of(
                    DomainTransactionRecord.create(
                        domain.getTld(),
                        // We report this when the autorenew grace period ends.
                        domain
                            .getCreationTime()
                            .plusYears(2)
                            .plus(Tld.DEFAULT_AUTO_RENEW_GRACE_PERIOD),
                        TransactionReportField.netRenewsFieldFromYears(1),
                        1)))
            .build());

    // Assert about BillingEvents.
    ImmutableList<DomainHistory> histories =
        loadHistoryObjectsForResource(domain.createVKey(), DomainHistory.class).stream()
            .filter(domainHistory -> DOMAIN_AUTORENEW.equals(domainHistory.getType()))
            .sorted(
                Comparator.comparing(
                    h ->
                        h.getDomainTransactionRecords().stream()
                            .findFirst()
                            .get()
                            .getReportingTime()))
            .collect(toImmutableList());
    assertBillingEventsForResource(
        domain,
        defaultOneTime(histories.get(0)),
        defaultOneTime(histories.get(1))
            .asBuilder()
            .setEventTime(domain.getCreationTime().plusYears(2))
            .setBillingTime(
                domain.getCreationTime().plusYears(2).plus(Tld.DEFAULT_AUTO_RENEW_GRACE_PERIOD))
            .build(),
        billingRecurrence
            .asBuilder()
            .setRecurrenceLastExpansion(domain.getCreationTime().plusYears(2))
            .build());

    // Assert about Cursor.
    assertCursorAt(endTime);
  }

  private void runPipeline() {
    ExpandBillingRecurrencesPipeline expandBillingRecurrencesPipeline =
        new ExpandBillingRecurrencesPipeline(options, clock);
    expandBillingRecurrencesPipeline.setupPipeline(pipeline);
    pipeline.run(options).waitUntilFinish();
  }

  void assertNoExpansionsHappened() {
    assertNoExpansionsHappened(false);
  }

  void assertNoExpansionsHappened(boolean dryRun) {
    // Only the original domain create history entry is present.
    List<DomainHistory> persistedHistory =
        loadHistoryObjectsForResource(domain.createVKey(), DomainHistory.class);
    assertThat(persistedHistory.size()).isEqualTo(1);
    assertThat(persistedHistory.get(0).getType()).isEqualTo(DOMAIN_CREATE);

    // Only the original recurrence is present.
    assertBillingEventsForResource(domain, billingRecurrence);

    // If this is not a dry run, the cursor should still be moved even though expansions happened,
    // because we still successfully processed all the needed expansions (none in this case) in the
    // window. Therefore,
    // the cursor should be up-to-date as of end time.
    assertCursorAt(dryRun ? startTime : endTime);
  }

  private DomainHistory defaultDomainHistory() {
    return defaultDomainHistory(domain);
  }

  private DomainHistory defaultDomainHistory(Domain domain) {
    return new DomainHistory.Builder()
        .setBySuperuser(false)
        .setRegistrarId("TheRegistrar")
        .setModificationTime(clock.nowUtc())
        .setDomain(domain)
        .setPeriod(Period.create(1, YEARS))
        .setReason("Domain autorenewal by ExpandRecurringBillingEventsPipeline")
        .setRequestedByRegistrar(false)
        .setType(DOMAIN_AUTORENEW)
        .setDomainTransactionRecords(
            ImmutableSet.of(
                DomainTransactionRecord.create(
                    domain.getTld(),
                    // We report this when the autorenew grace period ends.
                    domain.getCreationTime().plusYears(1).plus(Tld.DEFAULT_AUTO_RENEW_GRACE_PERIOD),
                    TransactionReportField.netRenewsFieldFromYears(1),
                    1)))
        .build();
  }

  private BillingEvent defaultOneTime(DomainHistory history) {
    return defaultOneTime(domain, history, billingRecurrence, 11);
  }

  private BillingEvent defaultOneTime(
      Domain domain, DomainHistory history, BillingRecurrence billingRecurrence, int cost) {
    return new BillingEvent.Builder()
        .setBillingTime(
            domain.getCreationTime().plusYears(1).plus(Tld.DEFAULT_AUTO_RENEW_GRACE_PERIOD))
        .setRegistrarId("TheRegistrar")
        .setCost(Money.of(USD, cost))
        .setEventTime(domain.getCreationTime().plusYears(1))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(endTime)
        .setCancellationMatchingBillingEvent(billingRecurrence)
        .setTargetId(domain.getDomainName())
        .setDomainHistory(history)
        .build();
  }

  private void assertAutoRenewDomainHistories(DomainHistory... expected) {
    assertAutoRenewDomainHistories(domain, expected);
  }

  private static void assertAutoRenewDomainHistories(Domain domain, DomainHistory... expected) {
    ImmutableList<DomainHistory> actuals =
        loadHistoryObjectsForResource(domain.createVKey(), DomainHistory.class).stream()
            .filter(domainHistory -> DOMAIN_AUTORENEW.equals(domainHistory.getType()))
            .collect(toImmutableList());
    assertThat(actuals)
        .comparingElementsUsing(immutableObjectCorrespondence("resource", "revisionId"))
        .containsExactlyElementsIn(Arrays.asList(expected));
    assertThat(
            actuals.stream()
                .map(history -> history.getDomainBase().get())
                .collect(toImmutableList()))
        .comparingElementsUsing(immutableObjectCorrespondence("nsHosts", "updateTimestamp"))
        .containsExactlyElementsIn(
            Arrays.stream(expected)
                .map(history -> history.getDomainBase().get())
                .collect(toImmutableList()));
  }

  private static DomainHistory getOnlyAutoRenewHistory(Domain domain) {
    return getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
  }

  private DomainHistory getOnlyAutoRenewHistory() {
    return getOnlyAutoRenewHistory(domain);
  }

  private static void assertCursorAt(DateTime expectedCursorTime) {
    Cursor cursor = tm().transact(() -> tm().loadByKey(Cursor.createGlobalVKey(RECURRING_BILLING)));
    assertThat(cursor).isNotNull();
    assertThat(cursor.getCursorTime()).isEqualTo(expectedCursorTime);
  }

  private static BillingRecurrence createDomainAtTime(String domainName, DateTime createTime) {
    Domain domain = persistActiveDomain(domainName, createTime);
    DomainHistory domainHistory =
        persistResource(
            new DomainHistory.Builder()
                .setRegistrarId(domain.getCreationRegistrarId())
                .setType(DOMAIN_CREATE)
                .setModificationTime(createTime)
                .setDomain(domain)
                .build());
    return persistResource(
        new BillingRecurrence.Builder()
            .setDomainHistory(domainHistory)
            .setRegistrarId(domain.getCreationRegistrarId())
            .setEventTime(createTime.plusYears(1))
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setReason(Reason.RENEW)
            .setRecurrenceEndTime(END_OF_TIME)
            .setTargetId(domain.getDomainName())
            .build());
  }

  public interface TestOptions extends ExpandBillingRecurrencesPipelineOptions, DirectOptions {}
}
