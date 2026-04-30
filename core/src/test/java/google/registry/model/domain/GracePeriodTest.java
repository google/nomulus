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

package google.registry.model.domain;

import static com.google.common.truth.Truth.assertThat;
import static java.time.temporal.ChronoUnit.DAYS;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import java.time.Instant;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link GracePeriod}. */
public class GracePeriodTest {

  private final FakeClock fakeClock = new FakeClock(Instant.parse("2024-01-01T00:00:00Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  private final Instant now = fakeClock.now();
  private BillingEvent onetime;
  private VKey<BillingRecurrence> recurrenceKey;

  @BeforeEach
  void before() {
    onetime =
        new BillingEvent.Builder()
            .setEventTime(now)
            .setBillingTime(now.plus(1, DAYS))
            .setRegistrarId("TheRegistrar")
            .setCost(Money.of(CurrencyUnit.USD, 42))
            .setDomainHistoryId(new HistoryEntryId("domain", 12345))
            .setReason(Reason.CREATE)
            .setPeriodYears(1)
            .setTargetId("foo.google")
            .build();
    recurrenceKey = BillingRecurrence.createVKey(12345L);
  }

  @Test
  void testSuccess_forBillingEvent() {
    GracePeriod gracePeriod = GracePeriod.forBillingEvent(GracePeriodStatus.ADD, "1-TEST", onetime);
    assertThat(gracePeriod.getType()).isEqualTo(GracePeriodStatus.ADD);
    assertThat(gracePeriod.getDomainRepoId()).isEqualTo("1-TEST");
    assertThat(gracePeriod.getBillingEvent()).isEqualTo(onetime.createVKey());
    assertThat(gracePeriod.getBillingRecurrence()).isNull();
    assertThat(gracePeriod.getRegistrarId()).isEqualTo("TheRegistrar");
    assertThat(gracePeriod.getExpirationTime()).isEqualTo(now.plus(1, DAYS));
    assertThat(gracePeriod.hasBillingEvent()).isTrue();
  }

  @Test
  void testSuccess_forRecurrence() {
    GracePeriod gracePeriod =
        GracePeriod.createForRecurrence(
            GracePeriodStatus.AUTO_RENEW,
            "1-TEST",
            now.plus(1, DAYS),
            "TheRegistrar",
            recurrenceKey);
    assertThat(gracePeriod.getType()).isEqualTo(GracePeriodStatus.AUTO_RENEW);
    assertThat(gracePeriod.getDomainRepoId()).isEqualTo("1-TEST");
    assertThat(gracePeriod.getBillingEvent()).isNull();
    assertThat(gracePeriod.getBillingRecurrence()).isEqualTo(recurrenceKey);
    assertThat(gracePeriod.getRegistrarId()).isEqualTo("TheRegistrar");
    assertThat(gracePeriod.getExpirationTime()).isEqualTo(now.plus(1, DAYS));
    assertThat(gracePeriod.hasBillingEvent()).isTrue();
  }

  @Test
  void testSuccess_createWithoutBillingEvent() {
    GracePeriod gracePeriod =
        GracePeriod.createWithoutBillingEvent(
            GracePeriodStatus.REDEMPTION, "1-TEST", now, "TheRegistrar");
    assertThat(gracePeriod.getType()).isEqualTo(GracePeriodStatus.REDEMPTION);
    assertThat(gracePeriod.getDomainRepoId()).isEqualTo("1-TEST");
    assertThat(gracePeriod.getBillingEvent()).isNull();
    assertThat(gracePeriod.getBillingRecurrence()).isNull();
    assertThat(gracePeriod.getRegistrarId()).isEqualTo("TheRegistrar");
    assertThat(gracePeriod.getExpirationTime()).isEqualTo(now);
    assertThat(gracePeriod.hasBillingEvent()).isFalse();
  }

  @Test
  void testFailure_forBillingEvent_autoRenew() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> GracePeriod.forBillingEvent(GracePeriodStatus.AUTO_RENEW, "1-TEST", onetime));
    assertThat(thrown).hasMessageThat().contains("autorenew");
  }

  @Test
  void testFailure_createForRecurrence_notAutoRenew() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                GracePeriod.createForRecurrence(
                    GracePeriodStatus.RENEW,
                    "1-TEST",
                    now.plus(1, DAYS),
                    "TheRegistrar",
                    recurrenceKey));
    assertThat(thrown).hasMessageThat().contains("autorenew");
  }
}
