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

package google.registry.tools.javascrap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import google.registry.model.billing.BillingEvent.Cancellation;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import google.registry.tools.CommandTestCase;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link CreateCancellationsForOneTimesCommand}. */
public class CreateCancellationsForOneTimesCommandTest
    extends CommandTestCase<CreateCancellationsForOneTimesCommand> {

  private Domain domain;
  private OneTime oneTimeToCancel;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    Contact contact = persistActiveContact("contact1234");
    domain =
        persistDomainWithDependentResources(
            "example",
            "tld",
            contact,
            fakeClock.nowUtc(),
            fakeClock.nowUtc(),
            fakeClock.nowUtc().plusYears(2));
    oneTimeToCancel = createOneTime();
  }

  @Test
  void testSimpleDelete() throws Exception {
    assertThat(DatabaseHelper.loadAllOf(Cancellation.class)).isEmpty();
    runCommandForced(String.valueOf(oneTimeToCancel.getId()));
    assertBillingEventCancelled();
    assertInStdout("Added Cancellation for OneTime with ID 9");
    assertInStdout("Created 1 Cancellation event(s)");
  }

  @Test
  void testSuccess_oneExistsOneDoesnt() throws Exception {
    runCommandForced(String.valueOf(oneTimeToCancel.getId()), "9001");
    assertBillingEventCancelled();
    assertInStdout("Found 1 OneTime(s) to cancel");
    assertInStdout("Missing OneTime(s) for IDs [9001]");
    assertInStdout("Added Cancellation for OneTime with ID 9");
    assertInStdout("Created 1 Cancellation event(s)");
  }

  @Test
  void testSuccess_multipleCancellations() throws Exception {
    OneTime secondToCancel = createOneTime();
    assertThat(DatabaseHelper.loadAllOf(Cancellation.class)).isEmpty();
    runCommandForced(
        String.valueOf(oneTimeToCancel.getId()), String.valueOf(secondToCancel.getId()));
    assertBillingEventCancelled(oneTimeToCancel.getId());
    assertBillingEventCancelled(secondToCancel.getId());
    assertInStdout("Create cancellations for 2 OneTime(s)?");
    assertInStdout("Added Cancellation for OneTime with ID 9");
    assertInStdout("Added Cancellation for OneTime with ID 10");
    assertInStdout("Created 2 Cancellation event(s)");
  }

  @Test
  void testAlreadyCancelled() throws Exception {
    // multiple runs / cancellations should be a no-op
    runCommandForced(String.valueOf(oneTimeToCancel.getId()));
    assertBillingEventCancelled();
    runCommandForced(String.valueOf(oneTimeToCancel.getId()));
    assertBillingEventCancelled();
    assertThat(DatabaseHelper.loadAllOf(Cancellation.class)).hasSize(1);
    assertInStdout("Found 0 OneTime(s) to cancel");
    assertInStdout("The following OneTime IDs were already cancelled: [9]");
  }

  @Test
  void testFailure_doesntExist() throws Exception {
    runCommandForced("9001");
    assertThat(DatabaseHelper.loadAllOf(Cancellation.class)).isEmpty();
    assertInStdout("Found 0 OneTime(s) to cancel");
    assertInStdout("Missing OneTime(s) for IDs [9001]");
    assertInStdout("Created 0 Cancellation event(s)");
  }

  @Test
  void testFailure_noIds() {
    assertThrows(ParameterException.class, this::runCommandForced);
  }

  private OneTime createOneTime() {
    return persistResource(
        new OneTime.Builder()
            .setReason(Reason.CREATE)
            .setTargetId(domain.getDomainName())
            .setRegistrarId("TheRegistrar")
            .setCost(Money.of(CurrencyUnit.USD, 10))
            .setPeriodYears(2)
            .setEventTime(fakeClock.nowUtc())
            .setBillingTime(fakeClock.nowUtc())
            .setFlags(ImmutableSet.of())
            .setDomainHistory(
                Iterables.getOnlyElement(
                    HistoryEntryDao.loadHistoryObjectsForResource(
                        domain.createVKey(), DomainHistory.class)))
            .build());
  }

  private void assertBillingEventCancelled() {
    assertBillingEventCancelled(oneTimeToCancel.getId());
  }

  private void assertBillingEventCancelled(long oneTimeId) {
    assertThat(
            DatabaseHelper.loadAllOf(Cancellation.class).stream()
                .anyMatch(c -> c.getEventKey().equals(VKey.createSql(OneTime.class, oneTimeId))))
        .isTrue();
  }
}
