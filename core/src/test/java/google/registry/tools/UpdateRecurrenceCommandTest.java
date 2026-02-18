// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistDomainWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.Iterables;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link UpdateRecurrenceCommand}. */
public class UpdateRecurrenceCommandTest extends CommandTestCase<UpdateRecurrenceCommand> {

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void testSuccess_setsSpecified() throws Exception {
    persistDomain();
    BillingRecurrence existingBillingRecurrence =
        Iterables.getOnlyElement(loadAllOf(BillingRecurrence.class));
    assertThat(existingBillingRecurrence.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    assertThat(existingBillingRecurrence.getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.DEFAULT);
    runCommandForced(
        "domain.tld",
        "--renewal_price_behavior",
        "SPECIFIED",
        "--specified_renewal_price",
        "USD 9001");
    assertThat(loadByEntity(existingBillingRecurrence).getRecurrenceEndTime())
        .isEqualTo(fakeClock.nowUtc());
    assertNewBillingEventAndHistory(
        existingBillingRecurrence.getId(),
        RenewalPriceBehavior.SPECIFIED,
        Money.of(CurrencyUnit.USD, 9001));
  }

  @Test
  void testSuccess_setsNonPremium() throws Exception {
    persistDomain();
    BillingRecurrence existingBillingRecurrence =
        Iterables.getOnlyElement(loadAllOf(BillingRecurrence.class));
    assertThat(existingBillingRecurrence.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    assertThat(existingBillingRecurrence.getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.DEFAULT);
    runCommandForced("domain.tld", "--renewal_price_behavior", "NONPREMIUM");
    assertThat(loadByEntity(existingBillingRecurrence).getRecurrenceEndTime())
        .isEqualTo(fakeClock.nowUtc());
    assertNewBillingEventAndHistory(
        existingBillingRecurrence.getId(), RenewalPriceBehavior.NONPREMIUM, null);
  }

  @Test
  void testSuccess_setsDefault() throws Exception {
    persistDomain();
    BillingRecurrence existingBillingRecurrence =
        Iterables.getOnlyElement(loadAllOf(BillingRecurrence.class));
    persistResource(
        existingBillingRecurrence
            .asBuilder()
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(CurrencyUnit.USD, 100))
            .build());
    assertThat(existingBillingRecurrence.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    runCommandForced("domain.tld", "--renewal_price_behavior", "DEFAULT");
    assertThat(loadByEntity(existingBillingRecurrence).getRecurrenceEndTime())
        .isEqualTo(fakeClock.nowUtc());
    assertNewBillingEventAndHistory(
        existingBillingRecurrence.getId(), RenewalPriceBehavior.DEFAULT, null);
  }

  @Test
  void testSuccess_setsPrice_whenSpecifiedAlready() throws Exception {
    Domain domain = persistDomain();
    BillingRecurrence billingRecurrence = loadByKey(domain.getAutorenewBillingEvent());
    persistResource(
        billingRecurrence
            .asBuilder()
            .setRenewalPrice(Money.of(CurrencyUnit.USD, 20))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .build());
    runCommandForced("domain.tld", "--specified_renewal_price", "USD 9001");
    assertNewBillingEventAndHistory(
        billingRecurrence.getId(),
        RenewalPriceBehavior.SPECIFIED,
        Money.of(CurrencyUnit.USD, 9001));
  }

  @Test
  void testSuccess_completedTransfer() throws Exception {
    Domain domain = persistDomain();
    domain =
        persistResource(
            domain
                .asBuilder()
                .setTransferData(
                    new DomainTransferData.Builder()
                        .setTransferStatus(TransferStatus.CLIENT_APPROVED)
                        .setPendingTransferExpirationTime(fakeClock.nowUtc().minusDays(8))
                        .build())
                .build());
    BillingRecurrence billingRecurrence = loadByKey(domain.getAutorenewBillingEvent());
    runCommandForced("domain.tld", "--renewal_price_behavior", "NONPREMIUM");
    assertNewBillingEventAndHistory(
        billingRecurrence.getId(), RenewalPriceBehavior.NONPREMIUM, null);
  }

  @Test
  void testFailure_nonexistentDomain() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommandForced("nonexistent.tld", "--renewal_price_behavior", "NONPREMIUM"));
  }

  @Test
  void testFailure_invalidInputs() throws Exception {
    persistDomain();
    assertThat(assertThrows(IllegalArgumentException.class, () -> runCommandForced("domain.tld")))
        .hasMessageThat()
        .isEqualTo("Must specify a behavior and/or a price");
    command = newCommandInstance();
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "domain.tld",
                        "--renewal_price_behavior",
                        "DEFAULT",
                        "--specified_renewal_price",
                        "USD 50")))
        .hasMessageThat()
        .isEqualTo(
            "Renewal price can have a value if and only if the renewal price behavior is"
                + " SPECIFIED");
    command = newCommandInstance();
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("domain.tld", "--renewal_price_behavior", "SPECIFIED")))
        .hasMessageThat()
        .isEqualTo("Renewal price must be set when using SPECIFIED behavior");
    command = newCommandInstance();
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("domain.tld", "--specified_renewal_price", "USD 50")))
        .hasMessageThat()
        .isEqualTo(
            "When specifying only a price, all domains must have SPECIFIED behavior. Domain"
                + " domain.tld does not");
  }

  @Test
  void testFailure_billingAlreadyClosed() {
    Domain domain = persistDomain();
    BillingRecurrence billingRecurrence = loadByKey(domain.getAutorenewBillingEvent());
    persistResource(billingRecurrence.asBuilder().setRecurrenceEndTime(fakeClock.nowUtc()).build());
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("domain.tld", "--renewal_price_behavior", "NONPREMIUM")))
        .hasMessageThat()
        .isEqualTo("Domain domain.tld's recurrence's end date is not END_OF_TIME");
  }

  @Test
  void testFailure_pendingTransfer() {
    persistDomainWithPendingTransfer(
        persistDomain(),
        fakeClock.nowUtc().minusMillis(1),
        fakeClock.nowUtc().plusMonths(1),
        END_OF_TIME);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("domain.tld", "--renewal_price_behavior", "NONPREMIUM")))
        .hasMessageThat()
        .startsWith("Domain domain.tld has a pending transfer: DomainTransferData: {");
  }

  private void assertNewBillingEventAndHistory(
      long previousId, RenewalPriceBehavior expectedBehavior, @Nullable Money expectedPrice) {
    BillingRecurrence newBillingRecurrence =
        loadAllOf(BillingRecurrence.class).stream()
            .filter(r -> r.getId() != previousId)
            .findFirst()
            .get();
    assertThat(newBillingRecurrence.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    assertThat(newBillingRecurrence.getRenewalPriceBehavior()).isEqualTo(expectedBehavior);
    assertThat(newBillingRecurrence.getRenewalPrice())
        .isEqualTo(Optional.ofNullable(expectedPrice));
    assertThat(newBillingRecurrence.getReason()).isEqualTo(Reason.RENEW);

    DomainHistory newHistory =
        loadAllOf(DomainHistory.class).stream()
            .filter(dh -> !dh.getType().equals(HistoryEntry.Type.DOMAIN_CREATE))
            .findFirst()
            .get();
    assertThat(newHistory.getType()).isEqualTo(HistoryEntry.Type.SYNTHETIC);
    assertThat(newHistory.getReason())
        .isEqualTo("Administrative update of billing recurrence behavior");
  }

  private Domain persistDomain() {
    Domain domain =
        persistDomainWithDependentResources(
            "domain",
            "tld",
            fakeClock.nowUtc(),
            fakeClock.nowUtc(),
            END_OF_TIME);
    fakeClock.advanceOneMilli();
    return domain;
  }
}
