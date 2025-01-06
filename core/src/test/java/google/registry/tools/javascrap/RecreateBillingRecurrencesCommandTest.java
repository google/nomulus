// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.Assert.assertThrows;

import google.registry.model.ImmutableObjectSubject;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.tools.CommandTestCase;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link RecreateBillingRecurrencesCommand}. */
public class RecreateBillingRecurrencesCommandTest
    extends CommandTestCase<RecreateBillingRecurrencesCommand> {

  private Contact contact;
  private Domain domain;
  private BillingRecurrence oldRecurrence;

  @BeforeEach
  void beforeEach() {
    fakeClock.setTo(DateTime.parse("2022-09-05TZ"));
    createTld("tld");
    contact = persistActiveContact("contact1234");
    domain =
        persistDomainWithDependentResources(
            "example",
            "tld",
            contact,
            fakeClock.nowUtc(),
            fakeClock.nowUtc(),
            fakeClock.nowUtc().plusYears(1));
    oldRecurrence = loadByKey(domain.getAutorenewBillingEvent());
    oldRecurrence =
        persistResource(
            oldRecurrence.asBuilder().setRecurrenceEndTime(fakeClock.nowUtc().plusDays(1)).build());
    fakeClock.setTo(DateTime.parse("2023-07-11TZ"));
  }

  @Test
  void testSuccess_simpleRecreation() throws Exception {
    runCommandForced("example.tld");
    // The domain should now be linked to the new recurrence
    BillingRecurrence newRecurrence = loadByKey(loadByEntity(domain).getAutorenewBillingEvent());
    assertThat(newRecurrence.getId()).isNotEqualTo(oldRecurrence.getId());
    // The new recurrence should not end and have last year's event time and last expansion.
    assertThat(newRecurrence.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    assertThat(newRecurrence.getEventTime()).isEqualTo(DateTime.parse("2023-09-05TZ"));
    assertThat(newRecurrence.getRecurrenceLastExpansion())
        .isEqualTo(DateTime.parse("2022-09-05TZ"));
    assertThat(loadAllOf(BillingRecurrence.class)).containsExactly(oldRecurrence, newRecurrence);
  }

  @Test
  void testSuccess_multipleDomains() throws Exception {
    Domain otherDomain =
        persistDomainWithDependentResources(
            "other",
            "tld",
            contact,
            DateTime.parse("2022-09-07TZ"),
            DateTime.parse("2022-09-07TZ"),
            DateTime.parse("2023-09-07TZ"));
    BillingRecurrence otherRecurrence = loadByKey(otherDomain.getAutorenewBillingEvent());
    otherRecurrence =
        persistResource(
            otherRecurrence
                .asBuilder()
                .setRecurrenceEndTime(DateTime.parse("2022-09-08TZ"))
                .build());
    runCommandForced("example.tld", "other.tld");
    // Both domains should have new recurrences with END_OF_TIME expirations
    BillingRecurrence otherNewRecurrence =
        loadByKey(loadByEntity(otherDomain).getAutorenewBillingEvent());
    assertThat(otherNewRecurrence.getId()).isNotEqualTo(otherRecurrence.getId());
    assertThat(otherNewRecurrence.getRecurrenceEndTime()).isEqualTo(END_OF_TIME);
    assertThat(otherNewRecurrence.getEventTime()).isEqualTo(DateTime.parse("2023-09-07TZ"));
    assertThat(otherNewRecurrence.getRecurrenceLastExpansion())
        .isEqualTo(DateTime.parse("2022-09-07TZ"));
    assertThat(loadAllOf(BillingRecurrence.class))
        .comparingElementsUsing(ImmutableObjectSubject.immutableObjectCorrespondence("id"))
        .containsExactly(
            oldRecurrence,
            oldRecurrence
                .asBuilder()
                .setRecurrenceEndTime(END_OF_TIME)
                .setEventTime(DateTime.parse("2023-09-05TZ"))
                .setRecurrenceLastExpansion(DateTime.parse("2022-09-05TZ"))
                .build(),
            otherRecurrence,
            otherNewRecurrence);
  }

  @Test
  void testFailure_badDomain() {
    assertThat(assertThrows(IllegalArgumentException.class, () -> runCommandForced("foo.tld")))
        .hasMessageThat()
        .isEqualTo("Domain foo.tld does not exist or has been deleted");
  }

  @Test
  void testFailure_alreadyEndOfTime() {
    persistResource(oldRecurrence.asBuilder().setRecurrenceEndTime(END_OF_TIME).build());
    assertThat(assertThrows(IllegalArgumentException.class, () -> runCommandForced("example.tld")))
        .hasMessageThat()
        .isEqualTo("Domain example.tld's recurrence's end date is already END_OF_TIME");
  }

  @Test
  void testFailure_nonLinkedRecurrenceIsEndOfTime() {
    persistResource(oldRecurrence.asBuilder().setRecurrenceEndTime(END_OF_TIME).setId(0).build());
    assertThat(assertThrows(IllegalArgumentException.class, () -> runCommandForced("example.tld")))
        .hasMessageThat()
        .isEqualTo(
            "There exists a recurrence with id 9 for domain example.tld with an end date of"
                + " END_OF_TIME");
  }
}
