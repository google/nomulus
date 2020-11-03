// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import java.util.Arrays;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DedupeRecurringBillingEventIdsCommand}. */
class DedupeRecurringBillingEventIdsCommandTest
    extends CommandTestCase<DedupeRecurringBillingEventIdsCommand> {

  private final DateTime now = DateTime.now(UTC);
  private DomainBase domain1;
  private DomainBase domain2;
  private HistoryEntry historyEntry1;
  private HistoryEntry historyEntry2;
  private BillingEvent.Recurring recurring1;
  private BillingEvent.Recurring recurring2;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    domain1 = persistActiveDomain("foo.tld");
    domain2 = persistActiveDomain("bar.tld");
    historyEntry1 =
        persistResource(
            new HistoryEntry.Builder().setParent(domain1).setModificationTime(now).build());
    historyEntry2 =
        persistResource(
            new HistoryEntry.Builder()
                .setParent(domain2)
                .setModificationTime(now.plusDays(1))
                .build());
    recurring1 =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setParent(historyEntry1)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setReason(Reason.RENEW)
                .setEventTime(now.plusYears(1))
                .setRecurrenceEndTime(END_OF_TIME)
                .setClientId("a registrar")
                .setTargetId("foo.tld")
                .build());
    recurring2 =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setId(recurring1.getId())
                .setParent(historyEntry2)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setReason(Reason.RENEW)
                .setEventTime(now.plusYears(1))
                .setRecurrenceEndTime(END_OF_TIME)
                .setClientId("a registrar")
                .setTargetId("bar.tld")
                .build());
  }

  @Test
  void testOnlyResaveBillingEventsCorrectly() throws Exception {
    assertThat(recurring1.getId()).isEqualTo(recurring2.getId());

    runCommand(
        "--force",
        "--key_paths_file",
        writeToNamedTmpFile("keypath.txt", getKeyPathLiteral(recurring1, recurring2)));

    assertNotChangeExceptUpdateTime(domain1, domain2, historyEntry1, historyEntry2);
    assertNotInDatastore(recurring1, recurring2);

    ImmutableList<BillingEvent.Recurring> recurrings = loadAllRecurrings();
    assertThat(recurrings.size()).isEqualTo(2);

    recurrings.forEach(
        newRecurring -> {
          if (newRecurring.getTargetId().equals("foo.tld")) {
            assertSameRecurringEntityExceptId(newRecurring, recurring1);
          } else if (newRecurring.getTargetId().equals("bar.tld")) {
            assertSameRecurringEntityExceptId(newRecurring, recurring2);
          } else {
            fail("Unknown BillingEvent.Recurring entity: " + newRecurring.createVKey());
          }
        });
  }

  @Test
  void testResaveAssociatedDomainAndOneTimeBillingEventCorrectly() throws Exception {
    assertThat(recurring1.getId()).isEqualTo(recurring2.getId());
    domain1 =
        persistResource(
            domain1
                .asBuilder()
                .setAutorenewBillingEvent(recurring1.createVKey())
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.createForRecurring(
                            GracePeriodStatus.AUTO_RENEW,
                            domain1.getRepoId(),
                            now.plusDays(45),
                            "a registrar",
                            recurring1.createVKey())))
                .setTransferData(
                    new DomainTransferData.Builder()
                        .setServerApproveAutorenewEvent(recurring1.createVKey())
                        .setServerApproveEntities(ImmutableSet.of(recurring1.createVKey()))
                        .build())
                .build());

    BillingEvent.OneTime oneTime =
        persistResource(
            new BillingEvent.OneTime.Builder()
                .setClientId("a registrar")
                .setTargetId("foo.tld")
                .setParent(historyEntry1)
                .setReason(Reason.CREATE)
                .setFlags(ImmutableSet.of(Flag.SYNTHETIC))
                .setSyntheticCreationTime(now)
                .setPeriodYears(2)
                .setCost(Money.of(USD, 1))
                .setEventTime(now)
                .setBillingTime(now.plusDays(5))
                .setCancellationMatchingBillingEvent(recurring1.createVKey())
                .build());

    runCommand(
        "--force",
        "--key_paths_file",
        writeToNamedTmpFile("keypath.txt", getKeyPathLiteral(recurring1, recurring2)));

    assertNotChangeExceptUpdateTime(domain2, historyEntry1, historyEntry2);
    assertNotInDatastore(recurring1, recurring2);
    ImmutableList<BillingEvent.Recurring> recurrings = loadAllRecurrings();
    assertThat(recurrings.size()).isEqualTo(2);

    recurrings.forEach(
        newRecurring -> {
          if (newRecurring.getTargetId().equals("foo.tld")) {
            assertSameRecurringEntityExceptId(newRecurring, recurring1);

            BillingEvent.OneTime persistedOneTime = ofy().load().entity(oneTime).now();
            assertAboutImmutableObjects()
                .that(persistedOneTime)
                .isEqualExceptFields(oneTime, "cancellationMatchingBillingEvent");
            assertThat(persistedOneTime.getCancellationMatchingBillingEvent())
                .isEqualTo(newRecurring.createVKey());

            DomainBase persistedDomain = ofy().load().entity(domain1).now();
            assertAboutImmutableObjects()
                .that(persistedDomain)
                .isEqualExceptFields(
                    domain1,
                    "updateTimestamp",
                    "revisions",
                    "gracePeriods",
                    "transferData",
                    "autorenewBillingEvent");
            assertThat(persistedDomain.getAutorenewBillingEvent())
                .isEqualTo(newRecurring.createVKey());
            assertThat(persistedDomain.getGracePeriods())
                .containsExactly(
                    GracePeriod.createForRecurring(
                        GracePeriodStatus.AUTO_RENEW,
                        domain1.getRepoId(),
                        now.plusDays(45),
                        "a registrar",
                        newRecurring.createVKey()));
            assertThat(persistedDomain.getTransferData().getServerApproveAutorenewEvent())
                .isEqualTo(newRecurring.createVKey());
            assertThat(persistedDomain.getTransferData().getServerApproveEntities())
                .containsExactly(newRecurring.createVKey());

          } else if (newRecurring.getTargetId().equals("bar.tld")) {
            assertSameRecurringEntityExceptId(newRecurring, recurring2);
          } else {
            fail("Unknown BillingEvent.Recurring entity: " + newRecurring.createVKey());
          }
        });
  }

  private static void assertNotInDatastore(ImmutableObject... entities) {
    for (ImmutableObject entity : entities) {
      assertThat(ofy().load().entity(entity).now()).isNull();
    }
  }

  private static void assertNotChangeInDatastore(ImmutableObject... entities) {
    for (ImmutableObject entity : entities) {
      assertThat(ofy().load().entity(entity).now()).isEqualTo(entity);
    }
  }

  private static void assertNotChangeExceptUpdateTime(ImmutableObject... entities) {
    for (ImmutableObject entity : entities) {
      assertAboutImmutableObjects()
          .that(ofy().load().entity(entity).now())
          .isEqualExceptFields(entity, "updateTimestamp", "revisions");
    }
  }

  private static void assertSameRecurringEntityExceptId(
      BillingEvent.Recurring recurring1, BillingEvent.Recurring recurring2) {
    assertAboutImmutableObjects().that(recurring1).isEqualExceptFields(recurring2, "id");
  }

  private static ImmutableList<BillingEvent.Recurring> loadAllRecurrings() {
    return ImmutableList.copyOf(ofy().load().type(BillingEvent.Recurring.class));
  }

  private static String getKeyPathLiteral(Object... entities) {
    return Arrays.stream(entities)
        .map(
            entity -> {
              Key<?> key = Key.create(entity);
              return String.format(
                  "\"DomainBase\", \"%s\", \"HistoryEntry\", %s, \"%s\", %s",
                  key.getParent().getParent().getName(),
                  key.getParent().getId(),
                  key.getKind(),
                  key.getId());
            })
        .reduce((k1, k2) -> k1 + "\n" + k2)
        .get();
  }
}
