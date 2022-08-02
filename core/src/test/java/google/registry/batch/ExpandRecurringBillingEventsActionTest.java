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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.billing.BillingEvent.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingEvent.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.model.domain.Period.Unit.YEARS;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_AUTORENEW;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.assertBillingEventsForResource;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getHistoryEntriesOfType;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import google.registry.flows.custom.DomainPricingCustomLogic;
import google.registry.flows.domain.DomainPricingLogic;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.common.Cursor;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ExpandRecurringBillingEventsAction}. */
public class ExpandRecurringBillingEventsActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withCloudSql().withLocalModules().withTaskQueue().build();

  private DateTime currentTestTime = DateTime.parse("1999-01-05T00:00:00Z");
  private final FakeClock clock = new FakeClock(currentTestTime);

  private ExpandRecurringBillingEventsAction action;
  private Domain domain;
  private DomainHistory historyEntry;
  private BillingEvent.Recurring recurring;

  @BeforeEach
  void beforeEach() {
    action = new ExpandRecurringBillingEventsAction();
    action.clock = clock;
    action.cursorTimeParam = Optional.empty();
    action.batchSize = 2;
    action.domainPricingLogic =
        new DomainPricingLogic(new DomainPricingCustomLogic(null, null, null));
    createTld("tld");
    domain =
        persistResource(
            DatabaseHelper.newDomain("example.tld")
                .asBuilder()
                .setCreationTimeForTest(DateTime.parse("1999-01-05T00:00:00Z"))
                .build());
    historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setRegistrarId(domain.getCreationRegistrarId())
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setModificationTime(DateTime.parse("1999-01-05T00:00:00Z"))
                .setDomain(domain)
                .build());
    recurring =
        new BillingEvent.Recurring.Builder()
            .setDomainHistory(historyEntry)
            .setRegistrarId(domain.getCreationRegistrarId())
            .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setId(2L)
            .setReason(Reason.RENEW)
            .setRecurrenceEndTime(END_OF_TIME)
            .setTargetId(domain.getDomainName())
            .build();
    currentTestTime = clock.nowUtc();
    clock.setTo(DateTime.parse("2000-10-02T00:00:00Z"));
  }

  private void saveCursor(final DateTime cursorTime) {
    tm().transact(() -> tm().put(Cursor.createGlobal(RECURRING_BILLING, cursorTime)));
  }

  private void runAction() throws Exception {
    action.response = new FakeResponse();
    action.run();
    // Need to save the current test time before running the action, which increments the clock.
    // The execution time (e. g. transaction time) is captured when the action starts running so
    // the passage of time afterward does not affect the timestamp stored in the billing events.
    currentTestTime = clock.nowUtc();
  }

  private void assertCursorAt(DateTime expectedCursorTime) {
    Cursor cursor = tm().transact(() -> tm().loadByKey(Cursor.createGlobalVKey(RECURRING_BILLING)));
    assertThat(cursor).isNotNull();
    assertThat(cursor.getCursorTime()).isEqualTo(expectedCursorTime);
  }

  private void assertHistoryEntryMatches(
      Domain domain,
      HistoryEntry actual,
      String registrarId,
      DateTime billingTime,
      boolean shouldHaveTxRecord) {
    assertThat(actual.getBySuperuser()).isFalse();
    assertThat(actual.getRegistrarId()).isEqualTo(registrarId);
    assertThat(actual.getParent()).isEqualTo(Key.create(domain));
    assertThat(actual.getPeriod()).isEqualTo(Period.create(1, YEARS));
    assertThat(actual.getReason())
        .isEqualTo("Domain autorenewal by ExpandRecurringBillingEventsAction");
    assertThat(actual.getRequestedByRegistrar()).isFalse();
    assertThat(actual.getType()).isEqualTo(DOMAIN_AUTORENEW);
    if (shouldHaveTxRecord) {
      assertThat(actual.getDomainTransactionRecords())
          .containsExactly(
              DomainTransactionRecord.create(
                  "tld", billingTime, TransactionReportField.NET_RENEWS_1_YR, 1));
    } else {
      assertThat(actual.getDomainTransactionRecords()).isEmpty();
    }
  }

  private OneTime.Builder defaultOneTimeBuilder() {
    return new BillingEvent.OneTime.Builder()
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setRegistrarId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(currentTestTime)
        .setCancellationMatchingBillingEvent(recurring)
        .setTargetId(domain.getDomainName());
  }

  @Test
  void testSuccess_expandSingleEvent() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntry).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_deletedDomain() throws Exception {
    DateTime deletionTime = DateTime.parse("2000-08-01T00:00:00Z");
    Domain deletedDomain = persistDeletedDomain("deleted.tld", deletionTime);
    historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(deletedDomain)
                .setRegistrarId(deletedDomain.getCreationRegistrarId())
                .setModificationTime(deletedDomain.getCreationTime())
                .setType(DOMAIN_CREATE)
                .build());
    recurring =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setDomainHistory(historyEntry)
                .setRegistrarId(deletedDomain.getCreationRegistrarId())
                .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setId(2L)
                .setReason(Reason.RENEW)
                .setRecurrenceEndTime(deletionTime)
                .setTargetId(deletedDomain.getDomainName())
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(deletedDomain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        deletedDomain,
        persistedEntry,
        "TheRegistrar",
        DateTime.parse("2000-02-19T00:00:00Z"),
        true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setDomainHistory(persistedEntry)
            .setTargetId(deletedDomain.getDomainName())
            .build();
    assertBillingEventsForResource(deletedDomain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_idempotentForDuplicateRuns() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntry).build();
    assertCursorAt(currentTestTime);
    DateTime beginningOfSecondRun = clock.nowUtc();
    action.response = new FakeResponse();
    runAction();
    assertCursorAt(beginningOfSecondRun);
    assertBillingEventsForResource(domain, expected, recurring);
  }

  @Test
  void testSuccess_expandSingleEvent_idempotentForExistingOneTime() throws Exception {
    persistResource(recurring);
    BillingEvent.OneTime persisted =
        persistResource(defaultOneTimeBuilder().setDomainHistory(historyEntry).build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertCursorAt(currentTestTime);
    // No additional billing events should be generated
    assertBillingEventsForResource(domain, persisted, recurring);
  }

  @Test
  void testSuccess_expandSingleEvent_notIdempotentForDifferentBillingTime() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntry).build();
    // Persist an otherwise identical billing event that differs only in billing time (and ID).
    BillingEvent.OneTime persisted =
        persistResource(
            expected
                .asBuilder()
                .setId(15891L)
                .setBillingTime(DateTime.parse("1999-02-19T00:00:00Z"))
                .setEventTime(DateTime.parse("1999-01-05T00:00:00Z"))
                .build());
    assertCursorAt(currentTestTime);
    assertBillingEventsForResource(domain, persisted, expected, recurring);
  }

  @Test
  void testSuccess_expandSingleEvent_notIdempotentForDifferentRecurring() throws Exception {
    persistResource(recurring);
    BillingEvent.Recurring recurring2 = persistResource(recurring.asBuilder().setId(3L).build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    List<DomainHistory> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    for (HistoryEntry persistedEntry : persistedEntries) {
      assertHistoryEntryMatches(
          domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    }
    assertThat(persistedEntries).hasSize(2);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntries.get(0)).build();
    // Persist an otherwise identical billing event that differs only in recurring event key.
    BillingEvent.OneTime persisted =
        expected
            .asBuilder()
            .setDomainHistory(persistedEntries.get(1))
            .setCancellationMatchingBillingEvent(recurring2)
            .build();
    assertCursorAt(currentTestTime);
    assertBillingEventsForResource(domain, persisted, expected, recurring, recurring2);
  }

  @Test
  void testSuccess_ignoreRecurringBeforeWindow() throws Exception {
    recurring =
        persistResource(
            recurring
                .asBuilder()
                .setEventTime(DateTime.parse("1997-01-05T00:00:00Z"))
                .setRecurrenceEndTime(DateTime.parse("1999-10-05T00:00:00Z"))
                .build());
    action.cursorTimeParam = Optional.of(DateTime.parse("2000-01-01T00:00:00Z"));
    runAction();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_ignoreRecurringAfterWindow() throws Exception {
    recurring =
        persistResource(recurring.asBuilder().setEventTime(clock.nowUtc().plusYears(2)).build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
  }

  @Test
  void testSuccess_expandSingleEvent_billingTimeAtCursorTime() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(DateTime.parse("2000-02-19T00:00:00Z"));
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntry).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_cursorTimeBetweenEventAndBillingTime() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(DateTime.parse("2000-01-12T00:00:00Z"));
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntry).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_billingTimeAtExecutionTime() throws Exception {
    clock.setTo(currentTestTime);
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    clock.setTo(DateTime.parse("2000-02-19T00:00:00Z"));
    runAction();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    // A candidate billing event is set to be billed exactly on 2/19/00 @ 00:00,
    // but these should not be generated as the interval is closed on cursorTime, open on
    // executeTime.
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_multipleYearCreate() throws Exception {
    action.cursorTimeParam = Optional.of(recurring.getEventTime());
    recurring =
        persistResource(
            recurring.asBuilder().setEventTime(recurring.getEventTime().plusYears(2)).build());
    clock.setTo(DateTime.parse("2002-10-02T00:00:00Z"));
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2002-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setBillingTime(DateTime.parse("2002-02-19T00:00:00Z"))
            .setEventTime(DateTime.parse("2002-01-05T00:00:00Z"))
            .setDomainHistory(persistedEntry)
            .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_withCursor() throws Exception {
    persistResource(recurring);
    saveCursor(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntry).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_withCursorPastExpected() throws Exception {
    persistResource(recurring);
    // Simulate a quick second run of the action (this should be a no-op).
    saveCursor(clock.nowUtc().minusSeconds(1));
    runAction();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_recurrenceEndBeforeEvent() throws Exception {
    // This can occur when a domain is transferred or deleted before a domain comes up for renewal.
    recurring =
        persistResource(
            recurring
                .asBuilder()
                .setRecurrenceEndTime(recurring.getEventTime().minusDays(5))
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_dryRun() throws Exception {
    persistResource(recurring);
    action.isDryRun = true;
    saveCursor(START_OF_TIME); // Need a saved cursor to verify that it didn't move.
    runAction();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(START_OF_TIME); // Cursor doesn't move on a dry run.
  }

  @Test
  void testSuccess_expandSingleEvent_multipleYears() throws Exception {
    clock.setTo(clock.nowUtc().plusYears(5));
    List<BillingEvent> expectedEvents = new ArrayList<>();
    expectedEvents.add(persistResource(recurring));
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    List<DomainHistory> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertThat(persistedEntries).hasSize(6);
    DateTime eventDate = DateTime.parse("2000-01-05T00:00:00Z");
    DateTime billingDate = DateTime.parse("2000-02-19T00:00:00Z");
    // Expecting events for '00, '01, '02, '03, '04, '05.
    for (int year = 0; year < 6; year++) {
      assertHistoryEntryMatches(
          domain, persistedEntries.get(year), "TheRegistrar", billingDate.plusYears(year), true);
      expectedEvents.add(
          defaultOneTimeBuilder()
              .setBillingTime(billingDate.plusYears(year))
              .setEventTime(eventDate.plusYears(year))
              .setDomainHistory(persistedEntries.get(year))
              .build());
    }
    assertBillingEventsForResource(domain, Iterables.toArray(expectedEvents, BillingEvent.class));
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_multipleYears_cursorInBetweenYears() throws Exception {
    clock.setTo(clock.nowUtc().plusYears(5));
    List<BillingEvent> expectedEvents = new ArrayList<>();
    expectedEvents.add(persistResource(recurring));
    saveCursor(DateTime.parse("2003-10-02T00:00:00Z"));
    runAction();
    List<DomainHistory> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertThat(persistedEntries).hasSize(2);
    DateTime eventDate = DateTime.parse("2004-01-05T00:00:00Z");
    DateTime billingDate = DateTime.parse("2004-02-19T00:00:00Z");
    // Only expect the last two years' worth of billing events.
    for (int year = 0; year < 2; year++) {
      assertHistoryEntryMatches(
          domain, persistedEntries.get(year), "TheRegistrar", billingDate.plusYears(year), true);
      expectedEvents.add(
          defaultOneTimeBuilder()
              .setBillingTime(billingDate.plusYears(year))
              .setDomainHistory(persistedEntries.get(year))
              .setEventTime(eventDate.plusYears(year))
              .build());
    }
    assertBillingEventsForResource(domain, Iterables.toArray(expectedEvents, BillingEvent.class));
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_singleEvent_beforeRenewal() throws Exception {
    // Need to restore to the time before the clock was advanced so that the commit log's timestamp
    // is not inverted when the clock is later reverted.
    clock.setTo(currentTestTime);
    persistResource(recurring);
    clock.setTo(DateTime.parse("2000-01-04T00:00:00Z"));
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_singleEvent_afterRecurrenceEnd_inAutorenewGracePeriod() throws Exception {
    // The domain creation date is 1999-01-05, and the first renewal date is thus 2000-01-05.
    clock.setTo(DateTime.parse("2001-02-06T00:00:00Z"));
    recurring =
        persistResource(
            recurring
                .asBuilder()
                // The domain deletion date is 2000-01-29, which is within the 45 day autorenew
                // grace period
                // from the renewal date.
                .setRecurrenceEndTime(DateTime.parse("2000-01-29T00:00:00Z"))
                .setEventTime(domain.getCreationTime().plusYears(1))
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), false);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
            .setDomainHistory(persistedEntry)
            .build();
    assertBillingEventsForResource(domain, recurring, expected);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_singleEvent_afterRecurrenceEnd_outsideAutorenewGracePeriod() throws Exception {
    // The domain creation date is 1999-01-05, and the first renewal date is thus 2000-01-05.
    clock.setTo(DateTime.parse("2001-02-06T00:00:00Z"));
    recurring =
        persistResource(
            recurring
                .asBuilder()
                // The domain deletion date is 2000-04-05, which is not within the 45 day autorenew
                // grace
                // period from the renewal date.
                .setRecurrenceEndTime(DateTime.parse("2000-04-05T00:00:00Z"))
                .setEventTime(domain.getCreationTime().plusYears(1))
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
            .setDomainHistory(persistedEntry)
            .build();
    assertBillingEventsForResource(domain, recurring, expected);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_billingTimeOnLeapYear() throws Exception {
    recurring =
        persistResource(
            recurring.asBuilder().setEventTime(DateTime.parse("2000-01-15T00:00:00Z")).build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-29T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setBillingTime(DateTime.parse("2000-02-29T00:00:00Z"))
            .setEventTime(DateTime.parse("2000-01-15T00:00:00Z"))
            .setDomainHistory(persistedEntry)
            .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandSingleEvent_billingTimeNotOnLeapYear() throws Exception {
    recurring =
        persistResource(
            recurring.asBuilder().setEventTime(DateTime.parse("2001-01-15T00:00:00Z")).build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    clock.setTo(DateTime.parse("2001-12-01T00:00:00Z"));
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2001-03-01T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setBillingTime(DateTime.parse("2001-03-01T00:00:00Z"))
            .setEventTime(DateTime.parse("2001-01-15T00:00:00Z"))
            .setDomainHistory(persistedEntry)
            .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandMultipleEvents() throws Exception {
    persistResource(recurring);
    Domain domain2 =
        persistResource(
            DatabaseHelper.newDomain("example2.tld")
                .asBuilder()
                .setCreationTimeForTest(DateTime.parse("1999-04-05T00:00:00Z"))
                .build());
    DomainHistory historyEntry2 =
        persistResource(
            new DomainHistory.Builder()
                .setRegistrarId(domain2.getCreationRegistrarId())
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setModificationTime(DateTime.parse("1999-04-05T00:00:00Z"))
                .setDomain(domain2)
                .build());
    BillingEvent.Recurring recurring2 =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setDomainHistory(historyEntry2)
                .setRegistrarId(domain2.getCreationRegistrarId())
                .setEventTime(DateTime.parse("2000-04-05T00:00:00Z"))
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setReason(Reason.RENEW)
                .setRecurrenceEndTime(END_OF_TIME)
                .setTargetId(domain2.getDomainName())
                .build());
    Domain domain3 =
        persistResource(
            DatabaseHelper.newDomain("example3.tld")
                .asBuilder()
                .setCreationTimeForTest(DateTime.parse("1999-06-05T00:00:00Z"))
                .build());
    DomainHistory historyEntry3 =
        persistResource(
            new DomainHistory.Builder()
                .setRegistrarId(domain3.getCreationRegistrarId())
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setModificationTime(DateTime.parse("1999-06-05T00:00:00Z"))
                .setDomain(domain3)
                .build());
    BillingEvent.Recurring recurring3 =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setDomainHistory(historyEntry3)
                .setRegistrarId(domain3.getCreationRegistrarId())
                .setEventTime(DateTime.parse("2000-06-05T00:00:00Z"))
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setReason(Reason.RENEW)
                .setRecurrenceEndTime(END_OF_TIME)
                .setTargetId(domain3.getDomainName())
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();

    DomainHistory persistedHistory1 =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedHistory1, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setDomainHistory(persistedHistory1)
            .setCancellationMatchingBillingEvent(recurring)
            .build();
    DomainHistory persistedHistory2 =
        getOnlyHistoryEntryOfType(domain2, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain2, persistedHistory2, "TheRegistrar", DateTime.parse("2000-05-20T00:00:00Z"), true);
    BillingEvent.OneTime expected2 =
        defaultOneTimeBuilder()
            .setBillingTime(DateTime.parse("2000-05-20T00:00:00Z"))
            .setEventTime(DateTime.parse("2000-04-05T00:00:00Z"))
            .setDomainHistory(persistedHistory2)
            .setTargetId(domain2.getDomainName())
            .setCancellationMatchingBillingEvent(recurring2)
            .build();
    DomainHistory persistedHistory3 =
        getOnlyHistoryEntryOfType(domain3, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain3, persistedHistory3, "TheRegistrar", DateTime.parse("2000-07-20T00:00:00Z"), true);
    BillingEvent.OneTime expected3 =
        defaultOneTimeBuilder()
            .setBillingTime(DateTime.parse("2000-07-20T00:00:00Z"))
            .setEventTime(DateTime.parse("2000-06-05T00:00:00Z"))
            .setTargetId(domain3.getDomainName())
            .setDomainHistory(persistedHistory3)
            .setCancellationMatchingBillingEvent(recurring3)
            .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertBillingEventsForResource(domain2, expected2, recurring2);
    assertBillingEventsForResource(domain3, expected3, recurring3);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandMultipleEvents_anchorTenant() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "example,USD 100"))
            .build());
    recurring = persistResource(recurring.asBuilder().setRenewalPriceBehavior(NONPREMIUM).build());
    BillingEvent.Recurring recurring2 =
        persistResource(
            recurring
                .asBuilder()
                .setEventTime(recurring.getEventTime().plusMonths(3))
                .setId(3L)
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    List<DomainHistory> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertThat(persistedEntries).hasSize(2);
    assertHistoryEntryMatches(
        domain,
        persistedEntries.get(0),
        "TheRegistrar",
        DateTime.parse("2000-02-19T00:00:00Z"),
        true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setDomainHistory(persistedEntries.get(0))
            .setCancellationMatchingBillingEvent(recurring)
            .build();
    assertHistoryEntryMatches(
        domain,
        persistedEntries.get(1),
        "TheRegistrar",
        DateTime.parse("2000-05-20T00:00:00Z"),
        true);
    BillingEvent.OneTime expected2 =
        defaultOneTimeBuilder()
            .setBillingTime(DateTime.parse("2000-05-20T00:00:00Z"))
            .setEventTime(DateTime.parse("2000-04-05T00:00:00Z"))
            .setDomainHistory(persistedEntries.get(1))
            .setCancellationMatchingBillingEvent(recurring2)
            .build();
    assertBillingEventsForResource(domain, expected, expected2, recurring, recurring2);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_expandMultipleEvents_premiumDomain_internalRegistration() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "example,USD 100"))
            .build());
    recurring =
        persistResource(
            recurring
                .asBuilder()
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 4))
                .build());
    BillingEvent.Recurring recurring2 =
        persistResource(
            recurring
                .asBuilder()
                .setEventTime(recurring.getEventTime().plusMonths(3))
                .setId(3L)
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 4))
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    List<DomainHistory> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertThat(persistedEntries).hasSize(2);
    assertHistoryEntryMatches(
        domain,
        persistedEntries.get(0),
        "TheRegistrar",
        DateTime.parse("2000-02-19T00:00:00Z"),
        true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setCost(Money.of(USD, 4))
            .setDomainHistory(persistedEntries.get(0))
            .setCancellationMatchingBillingEvent(recurring)
            .build();
    assertHistoryEntryMatches(
        domain,
        persistedEntries.get(1),
        "TheRegistrar",
        DateTime.parse("2000-05-20T00:00:00Z"),
        true);
    BillingEvent.OneTime expected2 =
        defaultOneTimeBuilder()
            .setCost(Money.of(USD, 4))
            .setBillingTime(DateTime.parse("2000-05-20T00:00:00Z"))
            .setEventTime(DateTime.parse("2000-04-05T00:00:00Z"))
            .setDomainHistory(persistedEntries.get(1))
            .setCancellationMatchingBillingEvent(recurring2)
            .build();
    assertBillingEventsForResource(domain, expected, expected2, recurring, recurring2);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_premiumDomain() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "example,USD 100"))
            .build());
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setDomainHistory(persistedEntry)
            .setCost(Money.of(USD, 100))
            .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_premiumDomain_forAnchorTenant() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "example,USD 100"))
            .build());
    recurring = persistResource(recurring.asBuilder().setRenewalPriceBehavior(NONPREMIUM).build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntry).setCost(Money.of(USD, 11)).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_standardDomain_forAnchorTenant() throws Exception {
    recurring = persistResource(recurring.asBuilder().setRenewalPriceBehavior(NONPREMIUM).build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntry).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_premiumDomain_forInternalRegistration() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "example,USD 100"))
            .build());
    recurring =
        persistResource(
            recurring
                .asBuilder()
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 20))
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntry).setCost(Money.of(USD, 20)).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_standardDomain_forInternalRegistration() throws Exception {
    recurring =
        persistResource(
            recurring
                .asBuilder()
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 2))
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    DomainHistory persistedEntry =
        getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder().setDomainHistory(persistedEntry).setCost(Money.of(USD, 2)).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_varyingRenewPrices() throws Exception {
    clock.setTo(currentTestTime);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 8),
                    DateTime.parse("2000-06-01T00:00:00Z"),
                    Money.of(USD, 10)))
            .build());
    clock.setTo(DateTime.parse("2001-10-02T00:00:00Z"));
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    List<DomainHistory> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertThat(persistedEntries).hasSize(2);
    DateTime eventDate = DateTime.parse("2000-01-05T00:00:00Z");
    DateTime billingDate = DateTime.parse("2000-02-19T00:00:00Z");
    assertHistoryEntryMatches(domain, persistedEntries.get(0), "TheRegistrar", billingDate, true);
    BillingEvent.OneTime cheaper =
        defaultOneTimeBuilder()
            .setBillingTime(billingDate)
            .setEventTime(eventDate)
            .setDomainHistory(persistedEntries.get(0))
            .setCost(Money.of(USD, 8))
            .build();
    assertHistoryEntryMatches(
        domain, persistedEntries.get(1), "TheRegistrar", billingDate.plusYears(1), true);
    BillingEvent.OneTime expensive =
        cheaper
            .asBuilder()
            .setCost(Money.of(USD, 10))
            .setBillingTime(billingDate.plusYears(1))
            .setEventTime(eventDate.plusYears(1))
            .setDomainHistory(persistedEntries.get(1))
            .build();
    assertBillingEventsForResource(domain, recurring, cheaper, expensive);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_varyingRenewPrices_anchorTenant() throws Exception {
    clock.setTo(currentTestTime);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "example,USD 100"))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 8),
                    DateTime.parse("2000-06-01T00:00:00Z"),
                    Money.of(USD, 10)))
            .build());
    clock.setTo(DateTime.parse("2001-10-02T00:00:00Z"));
    recurring = persistResource(recurring.asBuilder().setRenewalPriceBehavior(NONPREMIUM).build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    List<DomainHistory> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertThat(persistedEntries).hasSize(2);
    DateTime eventDate = DateTime.parse("2000-01-05T00:00:00Z");
    DateTime billingDate = DateTime.parse("2000-02-19T00:00:00Z");
    assertHistoryEntryMatches(domain, persistedEntries.get(0), "TheRegistrar", billingDate, true);
    BillingEvent.OneTime cheaper =
        defaultOneTimeBuilder()
            .setBillingTime(billingDate)
            .setEventTime(eventDate)
            .setDomainHistory(persistedEntries.get(0))
            .setCost(Money.of(USD, 8))
            .build();
    assertHistoryEntryMatches(
        domain, persistedEntries.get(1), "TheRegistrar", billingDate.plusYears(1), true);
    BillingEvent.OneTime expensive =
        cheaper
            .asBuilder()
            .setCost(Money.of(USD, 10))
            .setBillingTime(billingDate.plusYears(1))
            .setEventTime(eventDate.plusYears(1))
            .setDomainHistory(persistedEntries.get(1))
            .build();
    assertBillingEventsForResource(domain, recurring, cheaper, expensive);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testSuccess_varyingRenewPrices_internalRegistration() throws Exception {
    clock.setTo(currentTestTime);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "example,USD 100"))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 8),
                    DateTime.parse("2000-06-01T00:00:00Z"),
                    Money.of(USD, 10)))
            .build());
    clock.setTo(DateTime.parse("2001-10-02T00:00:00Z"));
    recurring =
        persistResource(
            recurring
                .asBuilder()
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 5))
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runAction();
    List<DomainHistory> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW, DomainHistory.class);
    assertThat(persistedEntries).hasSize(2);
    DateTime eventDate = DateTime.parse("2000-01-05T00:00:00Z");
    DateTime billingDate = DateTime.parse("2000-02-19T00:00:00Z");
    assertHistoryEntryMatches(domain, persistedEntries.get(0), "TheRegistrar", billingDate, true);
    BillingEvent.OneTime cheaper =
        defaultOneTimeBuilder()
            .setBillingTime(billingDate)
            .setEventTime(eventDate)
            .setDomainHistory(persistedEntries.get(0))
            .setCost(Money.of(USD, 5))
            .build();
    assertHistoryEntryMatches(
        domain, persistedEntries.get(1), "TheRegistrar", billingDate.plusYears(1), true);
    BillingEvent.OneTime expensive =
        cheaper
            .asBuilder()
            .setCost(Money.of(USD, 5))
            .setBillingTime(billingDate.plusYears(1))
            .setEventTime(eventDate.plusYears(1))
            .setDomainHistory(persistedEntries.get(1))
            .build();
    assertBillingEventsForResource(domain, recurring, cheaper, expensive);
    assertCursorAt(currentTestTime);
  }

  @Test
  void testFailure_cursorAfterExecutionTime() {
    action.cursorTimeParam = Optional.of(clock.nowUtc().plusYears(1));
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, this::runAction);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cursor time must be earlier than execution time.");
  }

  @Test
  void testFailure_cursorAtExecutionTime() {
    // The clock advances one milli on run.
    action.cursorTimeParam = Optional.of(clock.nowUtc().plusMillis(1));
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, this::runAction);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cursor time must be earlier than execution time.");
  }
}
