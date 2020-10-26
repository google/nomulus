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
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.model.domain.Period.Unit.YEARS;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_AUTORENEW;
import static google.registry.testing.DatastoreHelper.assertBillingEvents;
import static google.registry.testing.DatastoreHelper.assertBillingEventsForResource;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getHistoryEntriesOfType;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.common.Cursor;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.Period;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.schema.cursor.CursorDao;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ExpandRecurringBillingEventsAction}. */
public class ExpandRecurringBillingEventsActionTest
    extends MapreduceTestCase<ExpandRecurringBillingEventsAction> {

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private final DateTime beginningOfTest = DateTime.parse("2000-10-02T00:00:00Z");
  private final FakeClock clock = new FakeClock(beginningOfTest);

  private DomainBase domain;
  private HistoryEntry historyEntry;
  private BillingEvent.Recurring recurring;

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);
    action = new ExpandRecurringBillingEventsAction();
    action.mrRunner = makeDefaultRunner();
    action.clock = clock;
    action.cursorTimeParam = Optional.empty();
    createTld("tld");
    domain = persistResource(newDomainBase("example.tld").asBuilder()
        .setCreationTimeForTest(DateTime.parse("1999-01-05T00:00:00Z")).build());
    historyEntry = persistResource(new HistoryEntry.Builder().setParent(domain).build());
    recurring =
        new BillingEvent.Recurring.Builder()
            .setParent(historyEntry)
            .setClientId(domain.getCreationClientId())
            .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setId(2L)
            .setReason(Reason.RENEW)
            .setRecurrenceEndTime(END_OF_TIME)
            .setTargetId(domain.getDomainName())
            .build();
  }

  private void saveCursor(final DateTime cursorTime) {
    CursorDao.saveCursor(
        Cursor.createGlobal(RECURRING_BILLING, cursorTime),
        google.registry.schema.cursor.Cursor.GLOBAL);
  }

  private void runMapreduce() throws Exception {
    action.response = new FakeResponse();
    action.run();
    executeTasksUntilEmpty("mapreduce", clock);
    ofy().clearSessionCache();
  }

  private void assertCursorAt(DateTime expectedCursorTime) {
    Cursor cursor = ofy().load().key(Cursor.createGlobalKey(RECURRING_BILLING)).now();
    assertThat(cursor).isNotNull();
    assertThat(cursor.getCursorTime()).isEqualTo(expectedCursorTime);
  }

  private void assertHistoryEntryMatches(
      DomainBase domain, HistoryEntry actual, String clientId, DateTime billingTime,
      boolean shouldHaveTxRecord) {
    assertThat(actual.getBySuperuser()).isFalse();
    assertThat(actual.getClientId()).isEqualTo(clientId);
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
                  "tld",
                  billingTime,
                  TransactionReportField.NET_RENEWS_1_YR,
                  1));
    } else {
      assertThat(actual.getDomainTransactionRecords()).isEmpty();
    }
  }

  private OneTime.Builder defaultOneTimeBuilder() {
    return new BillingEvent.OneTime.Builder()
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(beginningOfTest)
        .setCancellationMatchingBillingEvent(recurring.createVKey())
        .setTargetId(domain.getDomainName());
  }

  @Test
  void testSuccess_expandSingleEvent() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder()
        .setParent(persistedEntry)
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_expandSingleEvent_deletedDomain() throws Exception {
    DateTime deletionTime = DateTime.parse("2000-08-01T00:00:00Z");
    DomainBase deletedDomain = persistDeletedDomain("deleted.tld", deletionTime);
    historyEntry = persistResource(new HistoryEntry.Builder().setParent(deletedDomain).build());
    recurring =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setParent(historyEntry)
                .setClientId(deletedDomain.getCreationClientId())
                .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setId(2L)
                .setReason(Reason.RENEW)
                .setRecurrenceEndTime(deletionTime)
                .setTargetId(deletedDomain.getDomainName())
                .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(deletedDomain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        deletedDomain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"),
        true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setParent(persistedEntry)
            .setTargetId(deletedDomain.getDomainName())
            .build();
    assertBillingEventsForResource(deletedDomain, expected, recurring);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_expandSingleEvent_idempotentForDuplicateRuns() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder().setParent(persistedEntry).build();
    assertCursorAt(beginningOfTest);
    DateTime beginningOfSecondRun = clock.nowUtc();
    action.response = new FakeResponse();
    runMapreduce();
    assertCursorAt(beginningOfSecondRun);
    assertBillingEventsForResource(domain, expected, recurring);
  }

  @Test
  void testSuccess_expandSingleEvent_idempotentForExistingOneTime() throws Exception {
    persistResource(recurring);
    BillingEvent.OneTime persisted = persistResource(defaultOneTimeBuilder()
        .setParent(historyEntry)
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertCursorAt(beginningOfTest);
    // No additional billing events should be generated
    assertBillingEventsForResource(domain, persisted, recurring);
  }

  @Test
  void testSuccess_expandSingleEvent_notIdempotentForDifferentBillingTime() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder().setParent(persistedEntry).build();
    // Persist an otherwise identical billing event that differs only in billing time.
    BillingEvent.OneTime persisted = persistResource(expected.asBuilder()
        .setBillingTime(DateTime.parse("1999-02-19T00:00:00Z"))
        .setEventTime(DateTime.parse("1999-01-05T00:00:00Z"))
        .build());
    assertCursorAt(beginningOfTest);
    assertBillingEventsForResource(domain, persisted, expected, recurring);
  }

  @Test
  void testSuccess_expandSingleEvent_notIdempotentForDifferentRecurring() throws Exception {
    persistResource(recurring);
    BillingEvent.Recurring recurring2 = persistResource(recurring.asBuilder()
        .setId(3L)
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    List<HistoryEntry> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW);
    for (HistoryEntry persistedEntry : persistedEntries) {
      assertHistoryEntryMatches(
          domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    }
    assertThat(persistedEntries).hasSize(2);
    BillingEvent.OneTime expected = defaultOneTimeBuilder()
        .setParent(persistedEntries.get(0))
        .build();
    // Persist an otherwise identical billing event that differs only in recurring event key.
    BillingEvent.OneTime persisted =
        expected
            .asBuilder()
            .setParent(persistedEntries.get(1))
            .setCancellationMatchingBillingEvent(recurring2.createVKey())
            .build();
    assertCursorAt(beginningOfTest);
    assertBillingEventsForResource(domain, persisted, expected, recurring, recurring2);
  }

  @Test
  void testSuccess_ignoreRecurringBeforeWindow() throws Exception {
    recurring = persistResource(recurring.asBuilder()
        .setEventTime(DateTime.parse("1997-01-05T00:00:00Z"))
        .setRecurrenceEndTime(DateTime.parse("1999-10-05T00:00:00Z"))
        .build());
    action.cursorTimeParam = Optional.of(DateTime.parse("2000-01-01T00:00:00Z"));
    runMapreduce();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_ignoreRecurringAfterWindow() throws Exception {
    recurring = persistResource(recurring.asBuilder()
        .setEventTime(clock.nowUtc().plusYears(2))
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
  }

  @Test
  void testSuccess_expandSingleEvent_billingTimeAtCursorTime() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(DateTime.parse("2000-02-19T00:00:00Z"));
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder().setParent(persistedEntry).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_expandSingleEvent_cursorTimeBetweenEventAndBillingTime() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(DateTime.parse("2000-01-12T00:00:00Z"));
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder().setParent(persistedEntry).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_expandSingleEvent_billingTimeAtExecutionTime() throws Exception {
    DateTime testTime = DateTime.parse("2000-02-19T00:00:00Z").minusMillis(1);
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    // Clock is advanced one milli in runMapreduce()
    clock.setTo(testTime);
    runMapreduce();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    // A candidate billing event is set to be billed exactly on 2/19/00 @ 00:00,
    // but these should not be generated as the interval is closed on cursorTime, open on
    // executeTime.
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(testTime);
  }

  @Test
  void testSuccess_expandSingleEvent_multipleYearCreate() throws Exception {
    DateTime testTime = beginningOfTest.plusYears(2);
    action.cursorTimeParam = Optional.of(recurring.getEventTime());
    recurring =
        persistResource(
            recurring.asBuilder().setEventTime(recurring.getEventTime().plusYears(2)).build());
    clock.setTo(testTime);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2002-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder()
        .setBillingTime(DateTime.parse("2002-02-19T00:00:00Z"))
        .setEventTime(DateTime.parse("2002-01-05T00:00:00Z"))
        .setParent(persistedEntry)
        .setSyntheticCreationTime(testTime)
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(testTime);
  }

  @Test
  void testSuccess_expandSingleEvent_withCursor() throws Exception {
    persistResource(recurring);
    saveCursor(START_OF_TIME);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder().setParent(persistedEntry).build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_expandSingleEvent_withCursorPastExpected() throws Exception {
    persistResource(recurring);
    // Simulate a quick second run of the mapreduce (this should be a no-op).
    saveCursor(clock.nowUtc().minusSeconds(1));
    runMapreduce();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_expandSingleEvent_recurrenceEndBeforeEvent() throws Exception {
    // This can occur when a domain is transferred or deleted before a domain comes up for renewal.
    recurring = persistResource(recurring.asBuilder()
        .setRecurrenceEndTime(recurring.getEventTime().minusDays(5))
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_expandSingleEvent_dryRun() throws Exception {
    persistResource(recurring);
    action.isDryRun = true;
    saveCursor(START_OF_TIME); // Need a saved cursor to verify that it didn't move.
    runMapreduce();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(START_OF_TIME); // Cursor doesn't move on a dry run.
  }

  @Test
  void testSuccess_expandSingleEvent_multipleYears() throws Exception {
    DateTime testTime = clock.nowUtc().plusYears(5);
    clock.setTo(testTime);
    List<BillingEvent> expectedEvents = new ArrayList<>();
    expectedEvents.add(persistResource(recurring));
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    List<HistoryEntry> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW);
    assertThat(persistedEntries).hasSize(6);
    DateTime eventDate = DateTime.parse("2000-01-05T00:00:00Z");
    DateTime billingDate = DateTime.parse("2000-02-19T00:00:00Z");
    // Expecting events for '00, '01, '02, '03, '04, '05.
    for (int year = 0; year < 6; year++) {
      assertHistoryEntryMatches(
          domain,
          persistedEntries.get(year),
          "TheRegistrar",
          billingDate.plusYears(year), true);
      expectedEvents.add(defaultOneTimeBuilder()
          .setBillingTime(billingDate.plusYears(year))
          .setEventTime(eventDate.plusYears(year))
          .setParent(persistedEntries.get(year))
          .setSyntheticCreationTime(testTime)
          .build());
    }
    assertBillingEventsForResource(domain, Iterables.toArray(expectedEvents, BillingEvent.class));
    assertCursorAt(testTime);
  }

  @Test
  void testSuccess_expandSingleEvent_multipleYears_cursorInBetweenYears() throws Exception {
    DateTime testTime = clock.nowUtc().plusYears(5);
    clock.setTo(testTime);
    List<BillingEvent> expectedEvents = new ArrayList<>();
    expectedEvents.add(persistResource(recurring));
    saveCursor(DateTime.parse("2003-10-02T00:00:00Z"));
    runMapreduce();
    List<HistoryEntry> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW);
    assertThat(persistedEntries).hasSize(2);
    DateTime eventDate = DateTime.parse("2004-01-05T00:00:00Z");
    DateTime billingDate = DateTime.parse("2004-02-19T00:00:00Z");
    // Only expect the last two years' worth of billing events.
    for (int year = 0; year < 2; year++) {
      assertHistoryEntryMatches(
          domain, persistedEntries.get(year), "TheRegistrar", billingDate.plusYears(year), true);
      expectedEvents.add(defaultOneTimeBuilder()
          .setBillingTime(billingDate.plusYears(year))
          .setParent(persistedEntries.get(year))
          .setEventTime(eventDate.plusYears(year))
          .setSyntheticCreationTime(testTime)
          .build());
    }
    assertBillingEventsForResource(domain, Iterables.toArray(expectedEvents, BillingEvent.class));
    assertCursorAt(testTime);
  }

  @Test
  void testSuccess_singleEvent_beforeRenewal() throws Exception {
    DateTime testTime = DateTime.parse("2000-01-04T00:00:00Z");
    clock.setTo(testTime);
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(testTime);
  }

  @Test
  void testSuccess_singleEvent_afterRecurrenceEnd_inAutorenewGracePeriod() throws Exception {
    // The domain creation date is 1999-01-05, and the first renewal date is thus 2000-01-05.
    DateTime testTime = DateTime.parse("2001-02-06T00:00:00Z");
    clock.setTo(testTime);
    recurring = persistResource(recurring.asBuilder()
        // The domain deletion date is 2000-01-29, which is within the 45 day autorenew grace period
        // from the renewal date.
        .setRecurrenceEndTime(DateTime.parse("2000-01-29T00:00:00Z"))
        .setEventTime(domain.getCreationTime().plusYears(1))
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), false);
    BillingEvent.OneTime expected = defaultOneTimeBuilder()
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setParent(persistedEntry)
        .setSyntheticCreationTime(testTime)
        .build();
    assertBillingEventsForResource(domain, recurring, expected);
    assertCursorAt(testTime);
  }

  @Test
  void testSuccess_singleEvent_afterRecurrenceEnd_outsideAutorenewGracePeriod() throws Exception {
    // The domain creation date is 1999-01-05, and the first renewal date is thus 2000-01-05.
    DateTime testTime = DateTime.parse("2001-02-06T00:00:00Z");
    clock.setTo(testTime);
    recurring = persistResource(recurring.asBuilder()
        // The domain deletion date is 2000-04-05, which is not within the 45 day autorenew grace
        // period from the renewal date.
        .setRecurrenceEndTime(DateTime.parse("2000-04-05T00:00:00Z"))
        .setEventTime(domain.getCreationTime().plusYears(1))
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder()
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setParent(persistedEntry)
        .setSyntheticCreationTime(testTime)
        .build();
    assertBillingEventsForResource(domain, recurring, expected);
    assertCursorAt(testTime);
  }

  @Test
  void testSuccess_expandSingleEvent_billingTimeOnLeapYear() throws Exception {
    recurring =
        persistResource(
            recurring.asBuilder().setEventTime(DateTime.parse("2000-01-15T00:00:00Z")).build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-29T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder()
        .setBillingTime(DateTime.parse("2000-02-29T00:00:00Z"))
        .setEventTime(DateTime.parse("2000-01-15T00:00:00Z"))
        .setParent(persistedEntry)
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_expandSingleEvent_billingTimeNotOnLeapYear() throws Exception {
    DateTime testTime = DateTime.parse("2001-12-01T00:00:00Z");
    recurring =
        persistResource(
            recurring.asBuilder().setEventTime(DateTime.parse("2001-01-15T00:00:00Z")).build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    clock.setTo(testTime);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2001-03-01T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder()
        .setBillingTime(DateTime.parse("2001-03-01T00:00:00Z"))
        .setEventTime(DateTime.parse("2001-01-15T00:00:00Z"))
        .setParent(persistedEntry)
        .setSyntheticCreationTime(testTime)
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(testTime);
  }

  @Test
  void testSuccess_expandMultipleEvents() throws Exception {
    persistResource(recurring);
    BillingEvent.Recurring recurring2 = persistResource(recurring.asBuilder()
        .setEventTime(recurring.getEventTime().plusMonths(3))
        .setId(3L)
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    List<HistoryEntry> persistedEntries = getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW);
    assertThat(persistedEntries).hasSize(2);
    assertHistoryEntryMatches(
        domain, persistedEntries.get(0), "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"),
        true);
    BillingEvent.OneTime expected =
        defaultOneTimeBuilder()
            .setParent(persistedEntries.get(0))
            .setCancellationMatchingBillingEvent(recurring.createVKey())
            .build();
    assertHistoryEntryMatches(
        domain, persistedEntries.get(1), "TheRegistrar", DateTime.parse("2000-05-20T00:00:00Z"),
        true);
    BillingEvent.OneTime expected2 =
        defaultOneTimeBuilder()
            .setBillingTime(DateTime.parse("2000-05-20T00:00:00Z"))
            .setEventTime(DateTime.parse("2000-04-05T00:00:00Z"))
            .setParent(persistedEntries.get(1))
            .setCancellationMatchingBillingEvent(recurring2.createVKey())
            .build();
    assertBillingEventsForResource(domain, expected, expected2, recurring, recurring2);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_premiumDomain() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", "example,USD 100"))
            .build());
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_AUTORENEW);
    assertHistoryEntryMatches(
        domain, persistedEntry, "TheRegistrar", DateTime.parse("2000-02-19T00:00:00Z"), true);
    BillingEvent.OneTime expected = defaultOneTimeBuilder()
        .setParent(persistedEntry)
        .setCost(Money.of(USD, 100))
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(beginningOfTest);
  }

  @Test
  void testSuccess_varyingRenewPrices() throws Exception {
    DateTime testTime = beginningOfTest.plusYears(1);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, Money.of(USD, 8),
                    DateTime.parse("2000-06-01T00:00:00Z"), Money.of(USD, 10)))
            .build());
    clock.setTo(testTime);
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    List<HistoryEntry> persistedEntries =
        getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW);
    assertThat(persistedEntries).hasSize(2);
    DateTime eventDate = DateTime.parse("2000-01-05T00:00:00Z");
    DateTime billingDate = DateTime.parse("2000-02-19T00:00:00Z");
    assertHistoryEntryMatches(domain, persistedEntries.get(0), "TheRegistrar", billingDate, true);
    BillingEvent.OneTime cheaper = defaultOneTimeBuilder()
        .setBillingTime(billingDate)
        .setEventTime(eventDate)
        .setParent(persistedEntries.get(0))
        .setCost(Money.of(USD, 8))
        .setSyntheticCreationTime(testTime)
        .build();
    assertHistoryEntryMatches(
        domain, persistedEntries.get(1), "TheRegistrar", billingDate.plusYears(1), true);
    BillingEvent.OneTime expensive = cheaper.asBuilder()
        .setCost(Money.of(USD, 10))
        .setBillingTime(billingDate.plusYears(1))
        .setEventTime(eventDate.plusYears(1))
        .setParent(persistedEntries.get(1))
        .build();
    assertBillingEventsForResource(domain, recurring, cheaper, expensive);
    assertCursorAt(testTime);
  }

  @Test
  void testFailure_cursorAfterExecutionTime() {
    action.cursorTimeParam = Optional.of(clock.nowUtc().plusYears(1));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, this::runMapreduce);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cursor time must be earlier than execution time.");
  }

  @Test
  void testFailure_cursorAtExecutionTime() {
    // The clock advances one milli on runMapreduce.
    action.cursorTimeParam = Optional.of(clock.nowUtc().plusMillis(1));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, this::runMapreduce);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cursor time must be earlier than execution time.");
  }

  @Test
  void testFailure_mapperException_doesNotMoveCursor() throws Exception {
    saveCursor(START_OF_TIME); // Need a saved cursor to verify that it didn't move.
    // Set target to a TLD that doesn't exist.
    recurring = persistResource(recurring.asBuilder().setTargetId("domain.junk").build());
    runMapreduce();
    // No new history entries should be generated
    assertThat(getHistoryEntriesOfType(domain, DOMAIN_AUTORENEW)).isEmpty();
    assertBillingEvents(recurring); // only the bogus one in Datastore
    assertCursorAt(START_OF_TIME); // Cursor doesn't move on a failure.
  }
}
