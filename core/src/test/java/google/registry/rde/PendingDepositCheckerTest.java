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

package google.registry.rde;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.Cursor.CursorType.BRDA;
import static google.registry.model.common.Cursor.CursorType.RDE_STAGING;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.model.rde.RdeMode.THIN;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.joda.time.DateTimeConstants.TUESDAY;
import static org.joda.time.Duration.standardDays;

import com.google.common.collect.ImmutableSetMultimap;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PendingDepositChecker}. */
@RunWith(JUnit4.class)
public class PendingDepositCheckerTest {

  @Rule
  public final InjectRule inject = new InjectRule();

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  private final FakeClock clock = new FakeClock();
  private final PendingDepositChecker checker = new PendingDepositChecker();

  @Before
  public void before() {
    inject.setStaticField(Ofy.class, "clock", clock);
    checker.brdaDayOfWeek = TUESDAY;
    checker.brdaInterval = standardDays(7);
    checker.clock = clock;
    checker.rdeInterval = standardDays(1);
  }

  @Test
  public void testMethod_noTldsWithEscrowEnabled_returnsEmpty() {
    createTld("pal");
    createTld("fun");
    assertThat(checker.getTldsAndWatermarksPendingDepositForRdeAndBrda()).isEmpty();
  }

  @Test
  public void testMethod_firstDeposit_depositsRdeTodayAtMidnight() {
    clock.setTo(DateTime.parse("2000-01-01T08:00Z"));  // Saturday
    createTldWithEscrowEnabled("lol");
    clock.advanceOneMilli();
    assertThat(checker.getTldsAndWatermarksPendingDepositForRdeAndBrda()).isEqualTo(
        ImmutableSetMultimap.of(
            "lol", PendingDeposit.create(
                "lol", DateTime.parse("2000-01-01TZ"), FULL, RDE_STAGING, standardDays(1))));
  }

  @Test
  public void testMethod_firstDepositOnBrdaDay_depositsBothRdeAndBrda() {
    clock.setTo(DateTime.parse("2000-01-04T08:00Z"));  // Tuesday
    createTldWithEscrowEnabled("lol");
    clock.advanceOneMilli();
    assertThat(checker.getTldsAndWatermarksPendingDepositForRdeAndBrda()).isEqualTo(
        ImmutableSetMultimap.of(
            "lol", PendingDeposit.create(
                "lol", DateTime.parse("2000-01-04TZ"), FULL, RDE_STAGING, standardDays(1)),
            "lol", PendingDeposit.create(
                "lol", DateTime.parse("2000-01-04TZ"), THIN, BRDA, standardDays(7))));
  }

  @Test
  public void testMethod_firstRdeDeposit_initializesCursorToMidnightToday() {
    clock.setTo(DateTime.parse("2000-01-01TZ"));  // Saturday
    createTldWithEscrowEnabled("lol");
    clock.advanceOneMilli();
    Registry registry = Registry.get("lol");
    assertThat(ofy().load().key(Cursor.createKey(RDE_STAGING, registry)).now()).isNull();
    checker.getTldsAndWatermarksPendingDepositForRdeAndBrda();
    assertThat(ofy().load().key(Cursor.createKey(RDE_STAGING, registry)).now().getCursorTime())
        .isEqualTo(DateTime.parse("2000-01-01TZ"));
  }

  @Test
  public void testMethod_subsequentRdeDeposit_doesntMutateCursor() {
    clock.setTo(DateTime.parse("2000-01-01TZ"));  // Saturday
    createTldWithEscrowEnabled("lol");
    clock.advanceOneMilli();
    DateTime yesterday = DateTime.parse("1999-12-31TZ");
    setCursor(Registry.get("lol"), RDE_STAGING, yesterday);
    clock.advanceOneMilli();
    checker.getTldsAndWatermarksPendingDepositForRdeAndBrda();
    Cursor cursor = ofy().load().key(Cursor.createKey(RDE_STAGING, Registry.get("lol"))).now();
    assertThat(cursor.getCursorTime()).isEqualTo(yesterday);
  }

  @Test
  public void testMethod_firstBrdaDepositButNotOnBrdaDay_doesntInitializeCursor() {
    clock.setTo(DateTime.parse("2000-01-01TZ"));  // Saturday
    createTldWithEscrowEnabled("lol");
    Registry registry = Registry.get("lol");
    clock.advanceOneMilli();
    setCursor(registry, RDE_STAGING, DateTime.parse("2000-01-02TZ")); // assume rde is already done
    clock.advanceOneMilli();
    assertThat(ofy().load().key(Cursor.createKey(BRDA, registry)).now()).isNull();
    assertThat(checker.getTldsAndWatermarksPendingDepositForRdeAndBrda()).isEmpty();
    assertThat(ofy().load().key(Cursor.createKey(BRDA, registry)).now()).isNull();
  }

  @Test
  public void testMethod_backloggedTwoDays_onlyWantsLeastRecentDay() {
    clock.setTo(DateTime.parse("2000-01-01TZ"));
    createTldWithEscrowEnabled("lol");
    clock.advanceOneMilli();
    setCursor(Registry.get("lol"), RDE_STAGING, DateTime.parse("1999-12-30TZ"));
    clock.advanceOneMilli();
    assertThat(checker.getTldsAndWatermarksPendingDepositForRdeAndBrda()).isEqualTo(
        ImmutableSetMultimap.of(
            "lol", PendingDeposit.create(
                "lol", DateTime.parse("1999-12-30TZ"), FULL, RDE_STAGING, standardDays(1))));
  }

  @Test
  public void testMethod_multipleTldsWithEscrowEnabled_depositsBoth() {
    clock.setTo(DateTime.parse("2000-01-01TZ"));  // Saturday
    createTldWithEscrowEnabled("pal");
    clock.advanceOneMilli();
    createTldWithEscrowEnabled("fun");
    clock.advanceOneMilli();
    assertThat(checker.getTldsAndWatermarksPendingDepositForRdeAndBrda()).isEqualTo(
        ImmutableSetMultimap.of(
            "pal", PendingDeposit.create(
                "pal", DateTime.parse("2000-01-01TZ"), FULL, RDE_STAGING, standardDays(1)),
            "fun", PendingDeposit.create(
                "fun", DateTime.parse("2000-01-01TZ"), FULL, RDE_STAGING, standardDays(1))));
  }

  private static void setCursor(
      final Registry registry, final CursorType cursorType, final DateTime value) {
    tm().transact(() -> ofy().save().entity(Cursor.create(cursorType, value, registry)));
  }

  private static void createTldWithEscrowEnabled(final String tld) {
    createTld(tld);
    persistResource(Registry.get(tld).asBuilder().setEscrowEnabled(true).build());
  }
}

