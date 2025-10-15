// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.ForeignKeyUtils.loadResource;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.eppcommon.StatusValue.PENDING_TRANSFER;
import static google.registry.model.reporting.HistoryEntry.Type.SYNTHETIC;
import static google.registry.testing.DatabaseHelper.assertBillingEventsEqual;
import static google.registry.testing.DatabaseHelper.assertPollMessagesEqual;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PollMessage;
import google.registry.testing.DatabaseHelper;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UnrenewDomainCommand}. */
public class UnrenewDomainCommandTest extends CommandTestCase<UnrenewDomainCommand> {

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    fakeClock.setTo(DateTime.parse("2016-12-06T13:55:01Z"));
    command.clock = fakeClock;
    command.printStream = System.out;
  }

  @Test
  void test_unrenewTwoDomains_worksSuccessfully() throws Exception {
    Contact contact = persistActiveContact("jd1234");
    fakeClock.advanceOneMilli();
    persistDomainWithDependentResources(
        "foo",
        "tld",
        contact,
        fakeClock.nowUtc(),
        fakeClock.nowUtc(),
        fakeClock.nowUtc().plusYears(5));
    fakeClock.advanceOneMilli();
    persistDomainWithDependentResources(
        "bar",
        "tld",
        contact,
        fakeClock.nowUtc(),
        fakeClock.nowUtc(),
        fakeClock.nowUtc().plusYears(4));
    fakeClock.setAutoIncrementByOneMilli();
    runCommandForced("-p", "2", "foo.tld", "bar.tld");
    fakeClock.disableAutoIncrement();
    assertThat(
            loadResource(Domain.class, "foo.tld", fakeClock.nowUtc())
                .get()
                .getRegistrationExpirationTime())
        .isEqualTo(DateTime.parse("2019-12-06T13:55:01.001Z"));
    assertThat(
            loadResource(Domain.class, "bar.tld", fakeClock.nowUtc())
                .get()
                .getRegistrationExpirationTime())
        .isEqualTo(DateTime.parse("2018-12-06T13:55:01.002Z"));
    assertInStdout("Successfully unrenewed all domains.");
  }

  @Test
  void test_unrenewDomain_savesDependentEntitiesCorrectly() throws Exception {
    Contact contact = persistActiveContact("jd1234");
    fakeClock.advanceOneMilli();
    persistDomainWithDependentResources(
        "foo",
        "tld",
        contact,
        fakeClock.nowUtc(),
        fakeClock.nowUtc(),
        fakeClock.nowUtc().plusYears(5));
    DateTime newExpirationTime = fakeClock.nowUtc().plusYears(3);
    fakeClock.advanceOneMilli();
    runCommandForced("-p", "2", "foo.tld");
    DateTime unrenewTime = fakeClock.nowUtc();
    fakeClock.advanceOneMilli();
    Domain domain = loadResource(Domain.class, "foo.tld", fakeClock.nowUtc()).get();

    assertAboutHistoryEntries()
        .that(getOnlyHistoryEntryOfType(domain, SYNTHETIC))
        .hasModificationTime(unrenewTime)
        .and()
        .hasMetadataReason("Domain unrenewal")
        .and()
        .hasPeriodYears(2)
        .and()
        .hasRegistrarId("TheRegistrar")
        .and()
        .bySuperuser(true)
        .and()
        .hasMetadataRequestedByRegistrar(false);
    DomainHistory synthetic = getOnlyHistoryEntryOfType(domain, SYNTHETIC, DomainHistory.class);

    assertBillingEventsEqual(
        loadByKey(domain.getAutorenewBillingEvent()),
        new BillingRecurrence.Builder()
            .setDomainHistory(synthetic)
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(domain.getDomainName())
            .setRegistrarId("TheRegistrar")
            .setEventTime(newExpirationTime)
            .build());
    assertPollMessagesEqual(
        getPollMessages(domain),
        ImmutableSet.of(
            new PollMessage.OneTime.Builder()
                .setHistoryEntry(synthetic)
                .setRegistrarId("TheRegistrar")
                .setMsg(
                    "Domain foo.tld was unrenewed by 2 years; "
                        + "now expires at 2019-12-06T13:55:01.001Z.")
                .setEventTime(unrenewTime)
                .build(),
            new PollMessage.Autorenew.Builder()
                .setHistoryEntry(synthetic)
                .setTargetId("foo.tld")
                .setRegistrarId("TheRegistrar")
                .setEventTime(newExpirationTime)
                .setMsg("Domain was auto-renewed.")
                .build()));

    // Check that fields on domain were updated correctly.
    assertThat(domain.getRegistrationExpirationTime()).isEqualTo(newExpirationTime);
    assertThat(domain.getLastEppUpdateTime()).isEqualTo(unrenewTime);
    assertThat(domain.getLastEppUpdateRegistrarId()).isEqualTo("TheRegistrar");
  }

  @Test
  void test_periodTooLow_fails() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> runCommandForced("--period", "0", "domain.tld"));
    assertThat(thrown).hasMessageThat().isEqualTo("Period must be in the range 1-9");
  }

  @Test
  void test_periodTooHigh_fails() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> runCommandForced("--period", "10", "domain.tld"));
    assertThat(thrown).hasMessageThat().isEqualTo("Period must be in the range 1-9");
  }

  @Test
  void test_varietyOfInvalidDomains_displaysErrors() {
    command.errorPrintStream = System.err;
    DateTime now = fakeClock.nowUtc();
    persistResource(
        DatabaseHelper.newDomain("deleting.tld")
            .asBuilder()
            .setDeletionTime(now.plusHours(1))
            .setStatusValues(ImmutableSet.of(PENDING_DELETE))
            .build());
    persistDeletedDomain("deleted.tld", now.minusHours(1));
    persistResource(
        DatabaseHelper.newDomain("transferring.tld")
            .asBuilder()
            .setStatusValues(ImmutableSet.of(PENDING_TRANSFER))
            .build());
    persistResource(
        DatabaseHelper.newDomain("locked.tld")
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.SERVER_UPDATE_PROHIBITED))
            .build());
    persistActiveDomain("expiring.tld", now.minusDays(4), now.plusMonths(11));
    persistActiveDomain("valid.tld", now.minusDays(4), now.plusYears(3));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "nonexistent.tld",
                    "deleting.tld",
                    "deleted.tld",
                    "transferring.tld",
                    "locked.tld",
                    "expiring.tld",
                    "valid.tld"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Aborting because some domains cannot be unrenewed");
    assertInStderr(
        "Found domains that cannot be unrenewed for the following reasons:",
        "Domains that don't exist: [nonexistent.tld]",
        "Domains that are deleted or pending delete: [deleting.tld, deleted.tld]",
        "Domains with disallowed statuses: "
            + "{transferring.tld=[PENDING_TRANSFER], locked.tld=[SERVER_UPDATE_PROHIBITED]}",
        "Domains expiring too soon: {expiring.tld=2017-11-06T13:55:01.000Z}");
    assertNotInStderr("valid.tld");
  }
}
