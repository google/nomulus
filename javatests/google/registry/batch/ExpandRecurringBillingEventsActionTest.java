// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.assertBillingEvents;
import static google.registry.testing.DatastoreHelper.assertBillingEventsForResource;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.VoidWork;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.common.Cursor;
import google.registry.model.domain.DomainResource;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.ExceptionRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.util.ArrayList;
import java.util.List;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


/** Unit tests for {@link ExpandRecurringBillingEventsAction}. */
@RunWith(JUnit4.class)
public class ExpandRecurringBillingEventsActionTest
    extends MapreduceTestCase<ExpandRecurringBillingEventsAction> {

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  final FakeClock clock = new FakeClock(DateTime.parse("2000-10-02T00:00:00Z"));

  DomainResource domain;
  HistoryEntry historyEntry;
  BillingEvent.Recurring recurring;

  @Before
  public void init() {
    action = new ExpandRecurringBillingEventsAction();
    action.mrRunner = makeDefaultRunner();
    action.clock = clock;
    action.cursorTimeParam = Optional.absent();
    createTld("tld");
    domain = persistActiveDomain("example.tld");
    historyEntry = persistResource(new HistoryEntry.Builder().setParent(domain).build());
    recurring = new BillingEvent.Recurring.Builder()
        .setParent(historyEntry)
        .setClientId(domain.getCreationClientId())
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setId(2L)
        .setReason(Reason.RENEW)
        .setRecurrenceEndTime(END_OF_TIME)
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
  }

  void saveCursor(final DateTime cursorTime) throws Exception {
    ofy().transact(new VoidWork() {
      @Override
      public void vrun() {
        ofy().save().entity(Cursor.createGlobal(RECURRING_BILLING, cursorTime));
      }});
  }

  void runMapreduce() throws Exception {
    action.response = new FakeResponse();
    action.run();
    executeTasksUntilEmpty("mapreduce");
    ofy().clearSessionCache();
  }

  void assertCursorAt(DateTime expectedCursorTime) throws Exception {
    Cursor cursor = ofy().load().key(Cursor.createGlobalKey(RECURRING_BILLING)).now();
    assertThat(cursor).isNotNull();
    assertThat(cursor.getCursorTime()).isEqualTo(expectedCursorTime);
  }

  @Test
  public void testSuccess_expandSingleEvent() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandSingleEvent_idempotentForDuplicateRuns() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertCursorAt(clock.nowUtc());
    action.response = new FakeResponse();
    runMapreduce();
    assertCursorAt(clock.nowUtc());
    assertBillingEventsForResource(domain, expected, recurring);
  }

  @Test
  public void testSuccess_expandSingleEvent_idempotentForExistingOneTime() throws Exception {
    persistResource(recurring);
    BillingEvent.OneTime persisted = persistResource(new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    assertCursorAt(clock.nowUtc());
    assertBillingEventsForResource(domain, persisted, recurring); // no additional billing events
  }

  @Test
  public void testSuccess_expandSingleEvent_notIdempotentForDifferentBillingTime()
      throws Exception {
    persistResource(recurring);
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    // Persist an otherwise identical billing event that differs only in billing time.
    BillingEvent.OneTime persisted = persistResource(expected.asBuilder()
        .setBillingTime(DateTime.parse("1999-02-19T00:00:00Z"))
        .setEventTime(DateTime.parse("1999-01-05T00:00:00Z"))
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    assertCursorAt(clock.nowUtc());
    assertBillingEventsForResource(domain, persisted, expected, recurring);
  }

  @Test
  public void testSuccess_expandSingleEvent_notIdempotentForDifferentRecurring()
      throws Exception {
    persistResource(recurring);
    BillingEvent.Recurring recurring2 = persistResource(recurring.asBuilder()
        .setId(3L)
        .build());
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    // Persist an otherwise identical billing event that differs only in recurring event key.
    BillingEvent.OneTime persisted = persistResource(expected.asBuilder()
        .setCancellationMatchingBillingEvent(Key.create(recurring2))
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    assertCursorAt(clock.nowUtc());
    assertBillingEventsForResource(domain, persisted, expected, recurring, recurring2);
  }

  @Test
  public void testSuccess_ignoreRecurringBeforeWindow() throws Exception {
    recurring = persistResource(recurring.asBuilder()
        .setEventTime(DateTime.parse("1997-01-05T00:00:00Z"))
        .setRecurrenceEndTime(DateTime.parse("1999-10-05T00:00:00Z"))
        .build());
    action.cursorTimeParam = Optional.of(DateTime.parse("2000-01-01T00:00:00Z"));
    runMapreduce();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_ignoreRecurringAfterWindow() throws Exception {
    recurring = persistResource(recurring.asBuilder()
        .setEventTime(clock.nowUtc().plusYears(2))
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    assertBillingEventsForResource(domain, recurring);
  }

  @Test
  public void testSuccess_expandSingleEvent_billingTimeAtCursorTime() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(DateTime.parse("2000-02-19T00:00:00Z"));
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandSingleEvent_cursorTimeBetweenEventAndBillingTime()
      throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(DateTime.parse("2000-01-12T00:00:00Z"));
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandSingleEvent_billingTimeAtExecutionTime() throws Exception {
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    // Clock is advanced one milli in runMapreduce()
    clock.setTo(DateTime.parse("2000-02-19T00:00:00Z").minusMillis(1));
    runMapreduce();
    // A candidate billing event is set to be billed exactly on 2/19/00 @ 00:00,
    // but these should not be generated as the interval is closed on cursorTime, open on
    // executeTime.
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandSingleEvent_multipleYearCreate() throws Exception {
    action.cursorTimeParam = Optional.of(recurring.getEventTime());
    recurring = persistResource(recurring.asBuilder()
        .setEventTime(recurring.getEventTime().plusYears(2))
        .build());
    clock.setTo(clock.nowUtc().plusYears(2));
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2002-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2002-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandSingleEvent_withCursor() throws Exception {
    persistResource(recurring);
    saveCursor(START_OF_TIME);
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandSingleEvent_withCursorPastExpected() throws Exception {
    persistResource(recurring);
    // Simulate a quick second run of the mapreduce (this should be a no-op).
    saveCursor(clock.nowUtc().minusSeconds(1));
    runMapreduce();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandSingleEvent_dryRun() throws Exception {
    persistResource(recurring);
    action.isDryRun = true;
    saveCursor(START_OF_TIME); // Need a saved cursor to verify that it didn't move.
    runMapreduce();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(START_OF_TIME); // Cursor doesn't move on a dry run.
  }

  @Test
  public void testSuccess_expandSingleEvent_multipleYears() throws Exception {
    clock.setTo(clock.nowUtc().plusYears(5));
    List<BillingEvent> expectedEvents = new ArrayList<>();
    expectedEvents.add(persistResource(recurring));
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    DateTime eventDate = DateTime.parse("2000-01-05T00:00:00Z");
    // Default renew grace period of 45 days.
    DateTime billingDate = DateTime.parse("2000-02-19T00:00:00Z");
    // Expecting events for '00, '01, '02, '03, '04, '05.
    for (int year = 0; year < 6; year++) {
      expectedEvents.add(new BillingEvent.OneTime.Builder()
          .setBillingTime(billingDate.plusYears(year))
          .setClientId("TheRegistrar")
          .setCost(Money.of(USD, 11))
          .setEventTime(eventDate.plusYears(year))
          .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
          .setParent(historyEntry)
          .setPeriodYears(1)
          .setReason(Reason.RENEW)
          .setSyntheticCreationTime(clock.nowUtc())
          .setCancellationMatchingBillingEvent(Key.create(recurring))
          .setTargetId(domain.getFullyQualifiedDomainName())
          .build());
    }
    assertBillingEventsForResource(domain, Iterables.toArray(expectedEvents, BillingEvent.class));
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandSingleEvent_multipleYears_cursorInBetweenYears() throws Exception {
    clock.setTo(clock.nowUtc().plusYears(5));
    List<BillingEvent> expectedEvents = new ArrayList<>();
    expectedEvents.add(persistResource(recurring));
    saveCursor(DateTime.parse("2003-10-02T00:00:00Z"));
    runMapreduce();
    DateTime eventDate = DateTime.parse("2004-01-05T00:00:00Z");
    // Default renew grace period of 45 days.
    DateTime billingDate = DateTime.parse("2004-02-19T00:00:00Z");
    // Only expect the last two years' worth of billing events.
    for (int year = 0; year < 2; year++) {
      expectedEvents.add(new BillingEvent.OneTime.Builder()
          .setBillingTime(billingDate.plusYears(year))
          .setClientId("TheRegistrar")
          .setCost(Money.of(USD, 11))
          .setEventTime(eventDate.plusYears(year))
          .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
          .setParent(historyEntry)
          .setPeriodYears(1)
          .setReason(Reason.RENEW)
          .setSyntheticCreationTime(clock.nowUtc())
          .setCancellationMatchingBillingEvent(Key.create(recurring))
          .setTargetId(domain.getFullyQualifiedDomainName())
          .build());
    }
    assertBillingEventsForResource(domain, Iterables.toArray(expectedEvents, BillingEvent.class));
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_singleEvent_beforeRenewal() throws Exception {
    clock.setTo(DateTime.parse("2000-01-04T00:00:00Z"));
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    assertBillingEventsForResource(domain, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_singleEvent_afterRecurrenceEnd() throws Exception {
    clock.setTo(clock.nowUtc().plusYears(2));
    recurring = persistResource(recurring.asBuilder()
        // Set between event time and billing time (i.e. before the grace period expires) for 2000.
        // We should still expect a billing event.
        .setRecurrenceEndTime(DateTime.parse("2000-01-29T00:00:00Z"))
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertBillingEventsForResource(domain, recurring, expected);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandSingleEvent_billingTimeOnLeapYear() throws Exception {
    recurring = persistResource(recurring.asBuilder()
        .setEventTime(DateTime.parse("2000-01-15T00:00:00Z"))
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2000-02-29T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-15T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandSingleEvent_billingTimeNotOnLeapYear() throws Exception {
    recurring = persistResource(recurring.asBuilder()
        .setEventTime(DateTime.parse("1999-01-15T00:00:00Z"))
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    clock.setTo(DateTime.parse("1999-12-01T00:00:00Z"));
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("1999-03-01T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("1999-01-15T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_expandMultipleEvents() throws Exception {
    persistResource(recurring);
    BillingEvent.Recurring recurring2 = persistResource(recurring.asBuilder()
        .setEventTime(recurring.getEventTime().plusMonths(3))
        .setId(3L)
        .build());
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    BillingEvent.OneTime expected2 = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2000-05-20T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 11))
        .setEventTime(DateTime.parse("2000-04-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring2))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertBillingEventsForResource(domain, expected, expected2, recurring, recurring2);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_premiumDomain() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", "example,USD 100"))
            .build());
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    BillingEvent.OneTime expected = new BillingEvent.OneTime.Builder()
        // Default renew grace period of 45 days.
        .setBillingTime(DateTime.parse("2000-02-19T00:00:00Z"))
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 100))
        .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    assertBillingEventsForResource(domain, expected, recurring);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testSuccess_varyingRenewPrices() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, Money.of(USD, 8),
                    DateTime.parse("2000-06-01T00:00:00Z"), Money.of(USD, 10)))
            .build());
    clock.setTo(clock.nowUtc().plusYears(1));
    persistResource(recurring);
    action.cursorTimeParam = Optional.of(START_OF_TIME);
    runMapreduce();
    DateTime eventDate = DateTime.parse("2000-01-05T00:00:00Z");
    // Default renew grace period of 45 days.
    DateTime billingDate = DateTime.parse("2000-02-19T00:00:00Z");
    BillingEvent.OneTime cheaper = new BillingEvent.OneTime.Builder()
        .setBillingTime(billingDate)
        .setClientId("TheRegistrar")
        .setCost(Money.of(USD, 8))
        .setEventTime(eventDate)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW, Flag.SYNTHETIC))
        .setParent(historyEntry)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .setSyntheticCreationTime(clock.nowUtc())
        .setCancellationMatchingBillingEvent(Key.create(recurring))
        .setTargetId(domain.getFullyQualifiedDomainName())
        .build();
    BillingEvent.OneTime expensive = cheaper.asBuilder()
        .setCost(Money.of(USD, 10))
        .setBillingTime(billingDate.plusYears(1))
        .setEventTime(eventDate.plusYears(1))
        .build();
    assertBillingEventsForResource(domain, recurring, cheaper, expensive);
    assertCursorAt(clock.nowUtc());
  }

  @Test
  public void testFailure_cursorAfterExecutionTime() throws Exception {
    action.cursorTimeParam = Optional.of(clock.nowUtc().plusYears(1));
    thrown.expect(
        IllegalArgumentException.class, "Cursor time must be earlier than execution time.");
    runMapreduce();
  }

  @Test
  public void testFailure_cursorAtExecutionTime() throws Exception {
    // The clock advances one milli on runMapreduce.
    action.cursorTimeParam = Optional.of(clock.nowUtc().plusMillis(1));
    thrown.expect(
        IllegalArgumentException.class, "Cursor time must be earlier than execution time.");
    runMapreduce();
  }

  @Test
  public void testFailure_mapperException_doesNotMoveCursor() throws Exception {
    saveCursor(START_OF_TIME); // Need a saved cursor to verify that it didn't move.
    // Set target to a TLD that doesn't exist.
    recurring = persistResource(recurring.asBuilder().setTargetId("domain.junk").build());
    runMapreduce();
    assertBillingEvents(recurring); // only the bogus one in datastore
    assertCursorAt(START_OF_TIME); // Cursor doesn't move on a failure.
  }
}
