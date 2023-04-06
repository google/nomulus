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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.Iterables;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import java.util.NoSuchElementException;
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
    Recurring existingRecurring = Iterables.getOnlyElement(loadAllOf(Recurring.class));
    assertThat(existingRecurring.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    assertThat(existingRecurring.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.DEFAULT);
    runCommandForced(
        Long.toString(existingRecurring.getId()),
        "--renewal_price_behavior",
        "SPECIFIED",
        "--specified_renewal_price",
        "USD 9001");
    assertThat(loadByEntity(existingRecurring).getRecurrenceEndTime())
        .isEqualTo(fakeClock.nowUtc());
    Recurring newRecurring =
        loadAllOf(Recurring.class).stream()
            .filter(r -> r.getId() != existingRecurring.getId())
            .findFirst()
            .get();
    assertThat(newRecurring.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    assertThat(newRecurring.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.SPECIFIED);
    assertThat(newRecurring.getRenewalPrice()).hasValue(Money.of(CurrencyUnit.USD, 9001));
  }

  @Test
  void testSuccess_setsNonPremium() throws Exception {
    persistDomain();
    Recurring existingRecurring = Iterables.getOnlyElement(loadAllOf(Recurring.class));
    assertThat(existingRecurring.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    assertThat(existingRecurring.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.DEFAULT);
    runCommandForced(
        Long.toString(existingRecurring.getId()), "--renewal_price_behavior", "NONPREMIUM");
    assertThat(loadByEntity(existingRecurring).getRecurrenceEndTime())
        .isEqualTo(fakeClock.nowUtc());
    Recurring newRecurring =
        loadAllOf(Recurring.class).stream()
            .filter(r -> r.getId() != existingRecurring.getId())
            .findFirst()
            .get();
    assertThat(newRecurring.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    assertThat(newRecurring.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.NONPREMIUM);
    assertThat(newRecurring.getRenewalPrice()).isEmpty();
  }

  @Test
  void testSuccess_setsDefault() throws Exception {
    persistDomain();
    Recurring existingRecurring = Iterables.getOnlyElement(loadAllOf(Recurring.class));
    persistResource(
        existingRecurring
            .asBuilder()
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(CurrencyUnit.USD, 100))
            .build());
    assertThat(existingRecurring.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    runCommandForced(
        Long.toString(existingRecurring.getId()), "--renewal_price_behavior", "DEFAULT");
    assertThat(loadByEntity(existingRecurring).getRecurrenceEndTime())
        .isEqualTo(fakeClock.nowUtc());
    Recurring newRecurring =
        loadAllOf(Recurring.class).stream()
            .filter(r -> r.getId() != existingRecurring.getId())
            .findFirst()
            .get();
    assertThat(newRecurring.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    assertThat(newRecurring.getRenewalPriceBehavior()).isEqualTo(RenewalPriceBehavior.DEFAULT);
    assertThat(newRecurring.getRenewalPrice()).isEmpty();
  }

  @Test
  void testFailure_nonexistentRecurring() {
    assertThrows(
        NoSuchElementException.class,
        () -> runCommandForced("1234", "--renewal_price_behavior", "NONPREMIUM"));
  }

  @Test
  void testFailure_invalidInputs() throws Exception {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "1234",
                        "--renewal_price_behavior",
                        "DEFAULT",
                        "--specified_renewal_price",
                        "USD 50")))
        .hasMessageThat()
        .isEqualTo("Renewal price can only (and must) be set when using SPECIFIED behavior");
    command = newCommandInstance();
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("1234", "--renewal_price_behavior", "SPECIFIED")))
        .hasMessageThat()
        .isEqualTo("Renewal price can only (and must) be set when using SPECIFIED behavior");
  }

  private void persistDomain() {
    persistDomainWithDependentResources(
        "domain",
        "tld",
        persistActiveContact("contact1234"),
        fakeClock.nowUtc(),
        fakeClock.nowUtc(),
        END_OF_TIME);
  }
}
