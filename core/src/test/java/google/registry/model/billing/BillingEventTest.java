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

package google.registry.model.billing;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.util.DateTimeUtils;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BillingEvent}. */
public class BillingEventTest extends EntityTestCase {
  private final DateTime now = DateTime.now(UTC);

  BillingEventTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  private HistoryEntry historyEntry;
  private HistoryEntry historyEntry2;
  private DomainBase domain;
  private BillingEvent.OneTime sqlOneTime;
  private BillingEvent.OneTime oneTime;
  private BillingEvent.OneTime oneTimeSynthetic;
  private BillingEvent.Recurring recurring;
  private BillingEvent.Cancellation cancellationOneTime;
  private BillingEvent.Cancellation cancellationRecurring;
  private BillingEvent.Modification modification;

  @BeforeEach
  void setUp() {
    createTld("tld");
    domain = persistActiveDomain("foo.tld");
    historyEntry = persistResource(
        new HistoryEntry.Builder()
            .setParent(domain)
            .setModificationTime(now)
            .build());
    historyEntry2 = persistResource(
        new HistoryEntry.Builder()
        .setParent(domain)
        .setModificationTime(now.plusDays(1))
        .build());

    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(UNLIMITED_USE)
                .setDiscountFraction(0.5)
                .setTokenStatusTransitions(
                    ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                        .put(DateTimeUtils.START_OF_TIME, TokenStatus.NOT_STARTED)
                        .put(DateTime.now(UTC), TokenStatus.VALID)
                        .put(DateTime.now(UTC).plusWeeks(8), TokenStatus.ENDED)
                        .build())
                .build());

    oneTime =
        persistResource(
            commonInit(
                new BillingEvent.OneTime.Builder()
                    .setParent(historyEntry)
                    .setReason(Reason.CREATE)
                    .setFlags(ImmutableSet.of(BillingEvent.Flag.ANCHOR_TENANT))
                    .setPeriodYears(2)
                    .setCost(Money.of(USD, 1))
                    .setEventTime(now)
                    .setBillingTime(now.plusDays(5))
                    .setAllocationToken(allocationToken.createVKey())));

    sqlOneTime =
        oneTime
            .asBuilder()
            .setDomainRepoId(domain.getRepoId())
            .setDomainHistoryRevisionId(1L)
            .setAllocationToken(
                VKey.create(
                    AllocationToken.class, allocationToken.getToken(), Key.create(allocationToken)))
            .build();

    recurring =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setParent(historyEntry)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRecurrenceEndTime(END_OF_TIME)));
    oneTimeSynthetic =
        persistResource(
            commonInit(
                new BillingEvent.OneTime.Builder()
                    .setParent(historyEntry)
                    .setReason(Reason.CREATE)
                    .setFlags(
                        ImmutableSet.of(
                            BillingEvent.Flag.ANCHOR_TENANT, BillingEvent.Flag.SYNTHETIC))
                    .setSyntheticCreationTime(now.plusDays(10))
                    .setCancellationMatchingBillingEvent(recurring.createVKey())
                    .setPeriodYears(2)
                    .setCost(Money.of(USD, 1))
                    .setEventTime(now)
                    .setBillingTime(now.plusDays(5))));

    cancellationOneTime =
        persistResource(
            commonInit(
                new BillingEvent.Cancellation.Builder()
                    .setParent(historyEntry2)
                    .setReason(Reason.CREATE)
                    .setEventTime(now.plusDays(1))
                    .setBillingTime(now.plusDays(5))
                    .setOneTimeEventKey(oneTime.createVKey())));

    cancellationRecurring =
        persistResource(
            commonInit(
                new BillingEvent.Cancellation.Builder()
                    .setParent(historyEntry2)
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusDays(1))
                    .setBillingTime(now.plusYears(1).plusDays(45))
                    .setRecurringEventKey(recurring.createVKey())));
    modification = persistResource(commonInit(
        new BillingEvent.Modification.Builder()
            .setParent(historyEntry2)
            .setReason(Reason.CREATE)
            .setCost(Money.of(USD, 1))
            .setDescription("Something happened")
            .setEventTime(now.plusDays(1))
            .setEventKey(Key.create(oneTime))));
  }

  private <E extends BillingEvent, B extends BillingEvent.Builder<E, B>> E commonInit(B builder) {
    return builder
        .setClientId("a registrar")
        .setTargetId("foo.tld")
        .build();
  }

  private void saveNewBillingEvent(BillingEvent billingEvent) {
    billingEvent.id = null;
    jpaTm().transact(() -> jpaTm().saveNew(billingEvent));
  }

  @Test
  void testCloudSqlPersistence_OneTime() {
    saveRegistrar("a registrar");
    saveNewBillingEvent(sqlOneTime);

    BillingEvent.OneTime persisted =
        jpaTm()
            .transact(
                () -> jpaTm().load(VKey.createSql(BillingEvent.OneTime.class, sqlOneTime.id)));
    // TODO(shicong): Remove these fixes after the entities are fully compatible
    BillingEvent.OneTime fixed =
        persisted
            .asBuilder()
            .setParent(sqlOneTime.getParentKey())
            .setAllocationToken(sqlOneTime.getAllocationToken().get())
            .build();
    assertThat(fixed).isEqualTo(sqlOneTime);
  }

  @Test
  void testCloudSqlPersistence_Cancellation() {
    saveRegistrar("a registrar");
    saveNewBillingEvent(sqlOneTime);
    VKey<BillingEvent.OneTime> sqlVKey = VKey.createSql(BillingEvent.OneTime.class, sqlOneTime.id);
    BillingEvent sqlCancellationOneTime =
        cancellationOneTime
            .asBuilder()
            .setOneTimeEventKey(sqlVKey)
            .setDomainRepoId(domain.getRepoId())
            .setDomainHistoryRevisionId(1L)
            .build();
    saveNewBillingEvent(sqlCancellationOneTime);

    BillingEvent.Cancellation persisted =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .load(
                            VKey.createSql(
                                BillingEvent.Cancellation.class, sqlCancellationOneTime.id)));
    // TODO(shicong): Remove these fixes after the entities are fully compatible
    BillingEvent.Cancellation fixed =
        persisted
            .asBuilder()
            .setParent(sqlCancellationOneTime.getParentKey())
            .setOneTimeEventKey(sqlVKey)
            .build();
    assertThat(fixed).isEqualTo(sqlCancellationOneTime);
  }

  @Test
  void testCloudSqlPersistence_Recurring() {
    saveRegistrar("a registrar");
    BillingEvent.Recurring sqlRecurring =
        recurring
            .asBuilder()
            .setDomainRepoId(domain.getRepoId())
            .setDomainHistoryRevisionId(1L)
            .build();
    saveNewBillingEvent(sqlRecurring);

    BillingEvent.Recurring persisted =
        jpaTm()
            .transact(
                () -> jpaTm().load(VKey.createSql(BillingEvent.Recurring.class, sqlRecurring.id)));
    // TODO(shicong): Remove these fixes after the entities are fully compatible
    BillingEvent.Recurring fixed =
        persisted.asBuilder().setParent(sqlRecurring.getParentKey()).build();
    assertThat(fixed).isEqualTo(sqlRecurring);
  }

  @Test
  void testPersistence() {
    assertThat(ofy().load().entity(oneTime).now()).isEqualTo(oneTime);
    assertThat(ofy().load().entity(oneTimeSynthetic).now()).isEqualTo(oneTimeSynthetic);
    assertThat(ofy().load().entity(recurring).now()).isEqualTo(recurring);
    assertThat(ofy().load().entity(cancellationOneTime).now()).isEqualTo(cancellationOneTime);
    assertThat(ofy().load().entity(cancellationRecurring).now()).isEqualTo(cancellationRecurring);
    assertThat(ofy().load().entity(modification).now()).isEqualTo(modification);
  }

  @Test
  void testParenting() {
    // Note that these are all tested separately because BillingEvent is an abstract base class that
    // lacks the @Entity annotation, and thus we cannot call .type(BillingEvent.class)
    assertThat(ofy().load().type(BillingEvent.OneTime.class).ancestor(domain).list())
        .containsExactly(oneTime, oneTimeSynthetic);
    assertThat(ofy().load().type(BillingEvent.Recurring.class).ancestor(domain).list())
        .containsExactly(recurring);
    assertThat(ofy().load().type(BillingEvent.Cancellation.class).ancestor(domain).list())
        .containsExactly(cancellationOneTime, cancellationRecurring);
    assertThat(ofy().load().type(BillingEvent.Modification.class).ancestor(domain).list())
        .containsExactly(modification);
    assertThat(ofy().load().type(BillingEvent.OneTime.class).ancestor(historyEntry).list())
        .containsExactly(oneTime, oneTimeSynthetic);
    assertThat(ofy().load().type(BillingEvent.Recurring.class).ancestor(historyEntry).list())
        .containsExactly(recurring);
    assertThat(ofy().load().type(BillingEvent.Cancellation.class).ancestor(historyEntry2).list())
        .containsExactly(cancellationOneTime, cancellationRecurring);
    assertThat(ofy().load().type(BillingEvent.Modification.class).ancestor(historyEntry2).list())
        .containsExactly(modification);
  }

  @Test
  void testCancellationMatching() {
    Key<?> recurringKey =
        ofy()
            .load()
            .entity(oneTimeSynthetic)
            .now()
            .getCancellationMatchingBillingEvent()
            .getOfyKey();
    assertThat(ofy().load().key(recurringKey).now()).isEqualTo(recurring);
  }

  @Test
  void testIndexing() throws Exception {
    verifyIndexing(
        oneTime,
        "clientId",
        "eventTime",
        "billingTime",
        "syntheticCreationTime",
        "allocationToken");
    verifyIndexing(
        oneTimeSynthetic,
        "clientId",
        "eventTime",
        "billingTime",
        "syntheticCreationTime",
        "allocationToken");
    verifyIndexing(
        recurring, "clientId", "eventTime", "recurrenceEndTime", "recurrenceTimeOfYear.timeString");
    verifyIndexing(cancellationOneTime, "clientId", "eventTime", "billingTime");
    verifyIndexing(modification, "clientId", "eventTime");
  }

  @Test
  void testFailure_syntheticFlagWithoutCreationTime() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                oneTime
                    .asBuilder()
                    .setFlags(ImmutableSet.of(BillingEvent.Flag.SYNTHETIC))
                    .setCancellationMatchingBillingEvent(recurring.createVKey())
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("Synthetic creation time must be set if and only if the SYNTHETIC flag is set.");
  }

  @Test
  void testFailure_syntheticCreationTimeWithoutFlag() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> oneTime.asBuilder().setSyntheticCreationTime(now.plusDays(10)).build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("Synthetic creation time must be set if and only if the SYNTHETIC flag is set");
  }

  @Test
  void testFailure_syntheticFlagWithoutCancellationMatchingKey() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                oneTime
                    .asBuilder()
                    .setFlags(ImmutableSet.of(BillingEvent.Flag.SYNTHETIC))
                    .setSyntheticCreationTime(END_OF_TIME)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Cancellation matching billing event must be set "
                + "if and only if the SYNTHETIC flag is set");
  }

  @Test
  void testFailure_cancellationMatchingKeyWithoutFlag() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                oneTime
                    .asBuilder()
                    .setCancellationMatchingBillingEvent(recurring.createVKey())
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Cancellation matching billing event must be set "
                + "if and only if the SYNTHETIC flag is set");
  }

  @Test
  void testSuccess_cancellation_forGracePeriod_withOneTime() {
    BillingEvent.Cancellation newCancellation = BillingEvent.Cancellation.forGracePeriod(
        GracePeriod.forBillingEvent(GracePeriodStatus.ADD, oneTime),
        historyEntry2,
        "foo.tld");
    // Set ID to be the same to ignore for the purposes of comparison.
    newCancellation = newCancellation.asBuilder().setId(cancellationOneTime.getId()).build();
    assertThat(newCancellation).isEqualTo(cancellationOneTime);
  }

  @Test
  void testSuccess_cancellation_forGracePeriod_withRecurring() {
    BillingEvent.Cancellation newCancellation = BillingEvent.Cancellation.forGracePeriod(
        GracePeriod.createForRecurring(
            GracePeriodStatus.AUTO_RENEW,
            now.plusYears(1).plusDays(45),
            "a registrar",
            Key.create(recurring)),
        historyEntry2,
        "foo.tld");
    // Set ID to be the same to ignore for the purposes of comparison.
    newCancellation = newCancellation.asBuilder().setId(cancellationRecurring.getId()).build();
    assertThat(newCancellation).isEqualTo(cancellationRecurring);
  }

  @Test
  void testFailure_cancellation_forGracePeriodWithoutBillingEvent() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                BillingEvent.Cancellation.forGracePeriod(
                    GracePeriod.createWithoutBillingEvent(
                        GracePeriodStatus.REDEMPTION, now.plusDays(1), "a registrar"),
                    historyEntry,
                    "foo.tld"));
    assertThat(thrown).hasMessageThat().contains("grace period without billing event");
  }

  @Test
  void testFailure_cancellationWithNoBillingEvent() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                cancellationOneTime
                    .asBuilder()
                    .setOneTimeEventKey(null)
                    .setRecurringEventKey(null)
                    .build());
    assertThat(thrown).hasMessageThat().contains("exactly one billing event");
  }

  @Test
  void testFailure_cancellationWithBothBillingEvents() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                cancellationOneTime
                    .asBuilder()
                    .setOneTimeEventKey(oneTime.createVKey())
                    .setRecurringEventKey(recurring.createVKey())
                    .build());
    assertThat(thrown).hasMessageThat().contains("exactly one billing event");
  }

  @Test
  void testDeadCodeThatDeletedScrapCommandsReference() {
    assertThat(recurring.getParentKey()).isEqualTo(Key.create(historyEntry));
    new BillingEvent.OneTime.Builder().setParent(Key.create(historyEntry));
  }
}
