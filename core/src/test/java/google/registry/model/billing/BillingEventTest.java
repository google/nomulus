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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.SerializeUtils.serializeDeserialize;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.EntityTestCase;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.util.DateTimeUtils;
import java.math.BigDecimal;
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

  private DomainHistory domainHistory;
  private DomainHistory domainHistory2;
  private Domain domain;
  private BillingEvent.OneTime oneTime;
  private BillingEvent.OneTime oneTimeSynthetic;
  private BillingEvent.Recurring recurring;
  private BillingEvent.Cancellation cancellationOneTime;
  private BillingEvent.Cancellation cancellationRecurring;

  @BeforeEach
  void setUp() {
    createTld("tld");
    domain = persistActiveDomain("foo.tld");
    domainHistory =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(domain)
                .setModificationTime(now)
                .setRequestedByRegistrar(false)
                .setRegistrarId("TheRegistrar")
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setXmlBytes(new byte[0])
                .build());
    domainHistory2 =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(domain)
                .setModificationTime(now.plusDays(1))
                .setRequestedByRegistrar(false)
                .setRegistrarId("TheRegistrar")
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setXmlBytes(new byte[0])
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
                    .setDomainHistory(domainHistory)
                    .setReason(Reason.CREATE)
                    .setFlags(ImmutableSet.of(BillingEvent.Flag.ANCHOR_TENANT))
                    .setPeriodYears(2)
                    .setCost(Money.of(USD, 1))
                    .setEventTime(now)
                    .setBillingTime(now.plusDays(5))
                    .setAllocationToken(allocationToken.createVKey())));

    recurring =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRecurrenceEndTime(END_OF_TIME)));
    oneTimeSynthetic =
        persistResource(
            commonInit(
                new BillingEvent.OneTime.Builder()
                    .setDomainHistory(domainHistory)
                    .setReason(Reason.CREATE)
                    .setFlags(
                        ImmutableSet.of(
                            BillingEvent.Flag.ANCHOR_TENANT, BillingEvent.Flag.SYNTHETIC))
                    .setSyntheticCreationTime(now.plusDays(10))
                    .setCancellationMatchingBillingEvent(recurring)
                    .setPeriodYears(2)
                    .setCost(Money.of(USD, 1))
                    .setEventTime(now)
                    .setBillingTime(now.plusDays(5))));

    cancellationOneTime =
        persistResource(
            commonInit(
                new BillingEvent.Cancellation.Builder()
                    .setDomainHistory(domainHistory2)
                    .setReason(Reason.CREATE)
                    .setEventTime(now.plusDays(1))
                    .setBillingTime(now.plusDays(5))
                    .setOneTimeEventKey(oneTime.createVKey())));

    cancellationRecurring =
        persistResource(
            commonInit(
                new BillingEvent.Cancellation.Builder()
                    .setDomainHistory(domainHistory2)
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusDays(1))
                    .setBillingTime(now.plusYears(1).plusDays(45))
                    .setRecurringEventKey(recurring.createVKey())));
  }

  private static <E extends BillingEvent, B extends BillingEvent.Builder<E, B>> E commonInit(
      B builder) {
    return builder.setRegistrarId("TheRegistrar").setTargetId("foo.tld").build();
  }

  @Test
  void testPersistence() {
    assertThat(loadByEntity(oneTime)).isEqualTo(oneTime);
    assertThat(loadByEntity(oneTimeSynthetic)).isEqualTo(oneTimeSynthetic);
    assertThat(loadByEntity(recurring)).isEqualTo(recurring);
    assertThat(loadByEntity(cancellationOneTime)).isEqualTo(cancellationOneTime);
    assertThat(loadByEntity(cancellationRecurring)).isEqualTo(cancellationRecurring);
  }

  @Test
  void testSerializable() {
    BillingEvent persisted = loadByEntity(oneTime);
    assertThat(serializeDeserialize(persisted)).isEqualTo(persisted);
    persisted = loadByEntity(oneTimeSynthetic);
    assertThat(serializeDeserialize(persisted)).isEqualTo(persisted);
    persisted = loadByEntity(recurring);
    assertThat(serializeDeserialize(persisted)).isEqualTo(persisted);
    persisted = loadByEntity(cancellationOneTime);
    assertThat(serializeDeserialize(persisted)).isEqualTo(persisted);
    persisted = loadByEntity(cancellationRecurring);
    assertThat(serializeDeserialize(persisted)).isEqualTo(persisted);
  }

  @Test
  void testCancellationMatching() {
    VKey<?> recurringKey = loadByEntity(oneTimeSynthetic).getCancellationMatchingBillingEvent();
    assertThat(loadByKey(recurringKey)).isEqualTo(recurring);
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
                    .setCancellationMatchingBillingEvent(recurring)
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
            () -> oneTime.asBuilder().setCancellationMatchingBillingEvent(recurring).build());
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Cancellation matching billing event must be set "
                + "if and only if the SYNTHETIC flag is set");
  }

  @Test
  void testSuccess_cancellation_forGracePeriod_withOneTime() {
    BillingEvent.Cancellation newCancellation =
        BillingEvent.Cancellation.forGracePeriod(
            GracePeriod.forBillingEvent(GracePeriodStatus.ADD, domain.getRepoId(), oneTime),
            domainHistory2.getModificationTime(),
            domainHistory2.getDomainHistoryId(),
            "foo.tld");
    // Set ID to be the same to ignore for the purposes of comparison.
    assertThat(newCancellation.asBuilder().setId(cancellationOneTime.getId()).build())
        .isEqualTo(cancellationOneTime);
  }

  @Test
  void testSuccess_cancellation_forGracePeriod_withRecurring() {
    BillingEvent.Cancellation newCancellation =
        BillingEvent.Cancellation.forGracePeriod(
            GracePeriod.createForRecurring(
                GracePeriodStatus.AUTO_RENEW,
                domain.getRepoId(),
                now.plusYears(1).plusDays(45),
                "TheRegistrar",
                recurring.createVKey()),
            domainHistory2.getModificationTime(),
            domainHistory2.getDomainHistoryId(),
            "foo.tld");
    // Set ID to be the same to ignore for the purposes of comparison.
    assertThat(newCancellation.asBuilder().setId(cancellationRecurring.getId()).build())
        .isEqualTo(cancellationRecurring);
  }

  @Test
  void testFailure_cancellation_forGracePeriodWithoutBillingEvent() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                BillingEvent.Cancellation.forGracePeriod(
                    GracePeriod.createWithoutBillingEvent(
                        GracePeriodStatus.REDEMPTION,
                        domain.getRepoId(),
                        now.plusDays(1),
                        "a registrar"),
                    domainHistory.getModificationTime(),
                    domainHistory.getDomainHistoryId(),
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
  void testReasonRequiringPeriodYears_missingPeriodYears_throwsException() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                new BillingEvent.OneTime.Builder()
                    .setBillingTime(DateTime.parse("2020-02-05T15:33:11Z"))
                    .setEventTime(DateTime.parse("2020-01-05T15:33:11Z"))
                    .setCost(Money.of(USD, 10))
                    .setReason(Reason.RENEW)
                    .setCost(Money.of(USD, 10))
                    .setRegistrarId("TheRegistrar")
                    .setTargetId("example.tld")
                    .setDomainHistory(domainHistory)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("Period years must be set if and only if reason is");
  }

  @Test
  void testReasonNotRequiringPeriodYears_havingPeriodYears_throwsException() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                new BillingEvent.OneTime.Builder()
                    .setBillingTime(DateTime.parse("2020-02-05T15:33:11Z"))
                    .setEventTime(DateTime.parse("2020-01-05T15:33:11Z"))
                    .setCost(Money.of(USD, 10))
                    .setPeriodYears(2)
                    .setReason(Reason.SERVER_STATUS)
                    .setCost(Money.of(USD, 10))
                    .setRegistrarId("TheRegistrar")
                    .setTargetId("example.tld")
                    .setDomainHistory(domainHistory)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("Period years must be set if and only if reason is");
  }

  @Test
  void testReasonRequiringPeriodYears_missingPeriodYears_isAllowedOnOldData() {
    // This won't throw even though periodYears is missing on a RESTORE because the event time
    // is before 2019.
    new BillingEvent.OneTime.Builder()
        .setBillingTime(DateTime.parse("2018-02-05T15:33:11Z"))
        .setEventTime(DateTime.parse("2018-01-05T15:33:11Z"))
        .setReason(Reason.RESTORE)
        .setCost(Money.of(USD, 10))
        .setRegistrarId("TheRegistrar")
        .setTargetId("example.tld")
        .setDomainHistory(domainHistory)
        .build();
  }

  @Test
  void testSuccess_defaultRenewalPriceBehavior_assertsIsDefault() {
    assertThat(recurring.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.DEFAULT);
    assertThat(recurring.getRenewalPrice()).isEmpty();
  }

  @Test
  void testSuccess_getRenewalPriceBehavior_returnsRightBehavior() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.NONPREMIUM);
    assertThat(recurringEvent.getRenewalPrice()).isEmpty();
  }

  @Test
  void testSuccess_setRenewalPriceBehaviorThenBuild_defaultToSpecified() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.DEFAULT);
    assertThat(recurringEvent.getRenewalPrice()).isEmpty();
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    persistResource(
        loadedEntity
            .asBuilder()
            .setRenewalPrice(Money.of(USD, 100))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .build());
    assertThat(loadByEntity(recurringEvent).getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.SPECIFIED);
    assertThat(loadByEntity(recurringEvent).getRenewalPrice()).hasValue(Money.of(USD, 100));
  }

  @Test
  void testSuccess_setRenewalPriceBehaviorThenBuild_defaultToNonPremium() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.DEFAULT);
    assertThat(recurringEvent.getRenewalPrice()).isEmpty();
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    persistResource(
        loadedEntity.asBuilder().setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM).build());
    assertThat(loadByEntity(recurringEvent).getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.NONPREMIUM);
    assertThat(loadByEntity(recurringEvent).getRenewalPrice()).isEmpty();
  }

  @Test
  void testSuccess_setRenewalPriceBehaviorThenBuild_nonPremiumToSpecified() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.NONPREMIUM);
    assertThat(recurringEvent.getRenewalPrice()).isEmpty();
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    persistResource(
        loadedEntity
            .asBuilder()
            .setRenewalPrice(Money.of(USD, 100))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .build());
    assertThat(loadByEntity(recurringEvent).getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.SPECIFIED);
    assertThat(loadByEntity(recurringEvent).getRenewalPrice()).hasValue(Money.of(USD, 100));
  }

  @Test
  void testSuccess_setRenewalPriceBehaviorThenBuild_nonPremiumToDefault() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.NONPREMIUM);
    assertThat(recurringEvent.getRenewalPrice()).isEmpty();
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    persistResource(
        loadedEntity.asBuilder().setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT).build());
    assertThat(loadByEntity(recurringEvent).getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.DEFAULT);
    assertThat(loadByEntity(recurringEvent).getRenewalPrice()).isEmpty();
  }

  @Test
  void testSuccess_setRenewalPriceBehaviorThenBuild_specifiedToDefault() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                    .setRenewalPrice(Money.of(USD, 100))
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.SPECIFIED);
    assertThat(recurringEvent.getRenewalPrice()).hasValue(Money.of(USD, 100));
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    persistResource(
        loadedEntity
            .asBuilder()
            .setRenewalPrice(null)
            .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
            .build());
    assertThat(loadByEntity(recurringEvent).getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.DEFAULT);
    assertThat(loadByEntity(recurringEvent).getRenewalPrice()).isEmpty();
  }

  @Test
  void testSuccess_setRenewalPriceBehaviorThenBuild_specifiedToNonPremium() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                    .setRenewalPrice(Money.of(USD, 100))
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.SPECIFIED);
    assertThat(recurringEvent.getRenewalPrice()).hasValue(Money.of(USD, 100));
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    persistResource(
        loadedEntity
            .asBuilder()
            .setRenewalPrice(null)
            .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
            .build());
    assertThat(loadByEntity(recurringEvent).getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.NONPREMIUM);
    assertThat(loadByEntity(recurringEvent).getRenewalPrice()).isEmpty();
  }

  @Test
  void testFailure_setRenewalPriceBehaviorThenBuild_defaultToSpecified_needRenewalPrice() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.DEFAULT);
    assertThat(recurringEvent.getRenewalPrice()).isEmpty();
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                loadedEntity
                    .asBuilder()
                    .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Renewal price can have a value if and only if the "
                + "renewal price behavior is SPECIFIED");
  }

  @Test
  void testFailure_setRenewalPriceBehaviorThenBuild_defaultToPremium_noNeedToAddRenewalPrice() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.DEFAULT);
    assertThat(recurringEvent.getRenewalPrice()).isEmpty();
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                loadedEntity
                    .asBuilder()
                    .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
                    .setRenewalPrice(Money.of(USD, 100))
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Renewal price can have a value if and only if the "
                + "renewal price behavior is SPECIFIED");
  }

  @Test
  void testFailure_setRenewalPriceBehaviorThenBuild_nonPremiumToDefault_noNeedToAddRenewalPrice() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.NONPREMIUM);
    assertThat(recurringEvent.getRenewalPrice()).isEmpty();
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                loadedEntity
                    .asBuilder()
                    .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
                    .setRenewalPrice(Money.of(USD, 100))
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Renewal price can have a value if and only if the "
                + "renewal price behavior is SPECIFIED");
  }

  @Test
  void testFailure_setRenewalPriceBehaviorThenBuild_nonPremiumToSpecified_needRenewalPrice() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.NONPREMIUM);
    assertThat(recurringEvent.getRenewalPrice()).isEmpty();
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                loadedEntity
                    .asBuilder()
                    .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Renewal price can have a value if and only if the "
                + "renewal price behavior is SPECIFIED");
  }

  @Test
  void testFailure_setRenewalPriceBehaviorThenBuild_specifiedToNonPremium_removeRenewalPrice() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                    .setRenewalPrice(Money.of(USD, 100))
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.SPECIFIED);
    assertThat(recurringEvent.getRenewalPrice()).hasValue(Money.of(USD, 100));
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                loadedEntity
                    .asBuilder()
                    .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Renewal price can have a value if and only if the "
                + "renewal price behavior is SPECIFIED");
  }

  @Test
  void testFailure_setRenewalPriceBehaviorThenBuild_specifiedToDefault_removeRenewalPrice() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                    .setRenewalPrice(Money.of(USD, 100))
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.SPECIFIED);
    assertThat(recurringEvent.getRenewalPrice()).hasValue(Money.of(USD, 100));
    BillingEvent.Recurring loadedEntity = loadByEntity(recurringEvent);
    assertThat(loadedEntity).isEqualTo(recurringEvent);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                loadedEntity
                    .asBuilder()
                    .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Renewal price can have a value if and only if the "
                + "renewal price behavior is SPECIFIED");
  }

  @Test
  void testSuccess_buildWithDefaultRenewalBehavior() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                    .setRenewalPrice(Money.of(USD, BigDecimal.valueOf(100)))
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.SPECIFIED);
    assertThat(recurringEvent.getRenewalPrice()).hasValue(Money.of(USD, 100));
  }

  @Test
  void testSuccess_buildWithNonPremiumRenewalBehavior() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.NONPREMIUM);
    assertThat(loadByEntity(recurringEvent).getRenewalPrice()).isEmpty();
  }

  @Test
  void testSuccess_buildWithSpecifiedRenewalBehavior() {
    BillingEvent.Recurring recurringEvent =
        persistResource(
            commonInit(
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                    .setRenewalPrice(Money.of(USD, BigDecimal.valueOf(100)))
                    .setRecurrenceEndTime(END_OF_TIME)));
    assertThat(recurringEvent.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.SPECIFIED);
    assertThat(recurringEvent.getRenewalPrice()).hasValue(Money.of(USD, 100));
  }

  @Test
  void testFailure_buildWithSpecifiedRenewalBehavior_requiresNonNullRenewalPrice() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                    .setRecurrenceEndTime(END_OF_TIME)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Renewal price can have a value if and only if the "
                + "renewal price behavior is SPECIFIED");
  }

  @Test
  void testFailure_buildWithNonPremiumRenewalBehavior_requiresNullRenewalPrice() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
                    .setRenewalPrice(Money.of(USD, BigDecimal.valueOf(100)))
                    .setRecurrenceEndTime(END_OF_TIME)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Renewal price can have a value if and only if the "
                + "renewal price behavior is SPECIFIED");
  }

  @Test
  void testFailure_buildWithDefaultRenewalBehavior_requiresNullRenewalPrice() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new BillingEvent.Recurring.Builder()
                    .setDomainHistory(domainHistory)
                    .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                    .setReason(Reason.RENEW)
                    .setEventTime(now.plusYears(1))
                    .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
                    .setRenewalPrice(Money.of(USD, BigDecimal.valueOf(100)))
                    .setRecurrenceEndTime(END_OF_TIME)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Renewal price can have a value if and only if the "
                + "renewal price behavior is SPECIFIED");
  }
}
