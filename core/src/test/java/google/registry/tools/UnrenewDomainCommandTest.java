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
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.eppcommon.StatusValue.PENDING_TRANSFER;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.reporting.HistoryEntry.Type.SYNTHETIC;
import static google.registry.testing.DatastoreHelper.assertBillingEventsEqual;
import static google.registry.testing.DatastoreHelper.assertPollMessagesEqual;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link UnrenewDomainCommand}. */
public class UnrenewDomainCommandTest extends CommandTestCase<UnrenewDomainCommand> {

  @Rule public final InjectRule inject = new InjectRule();

  private final FakeClock clock = new FakeClock(DateTime.parse("2016-12-06T13:55:01Z"));

  @Before
  public void before() {
    createTld("tld");
    inject.setStaticField(Ofy.class, "clock", clock);
    command.clock = clock;
  }

  @Test
  public void test_unrenewTwoDomains_worksSuccessfully() throws Exception {
    ContactResource contact = persistActiveContact("jd1234");
    clock.advanceOneMilli();
    persistDomainWithDependentResources(
        "foo", "tld", contact, clock.nowUtc(), clock.nowUtc(), clock.nowUtc().plusYears(5));
    clock.advanceOneMilli();
    persistDomainWithDependentResources(
        "bar", "tld", contact, clock.nowUtc(), clock.nowUtc(), clock.nowUtc().plusYears(4));
    clock.advanceOneMilli();
    runCommandForced("-p", "2", "foo.tld", "bar.tld");
    clock.advanceOneMilli();
    assertThat(
            loadByForeignKey(DomainBase.class, "foo.tld", clock.nowUtc())
                .get()
                .getRegistrationExpirationTime())
        .isEqualTo(DateTime.parse("2019-12-06T13:55:01.001Z"));
    assertThat(
            loadByForeignKey(DomainBase.class, "bar.tld", clock.nowUtc())
                .get()
                .getRegistrationExpirationTime())
        .isEqualTo(DateTime.parse("2018-12-06T13:55:01.002Z"));
    assertInStdout("Successfully unrenewed all domains.");
  }

  @Test
  public void test_unrenewDomain_savesDependentEntitiesCorrectly() throws Exception {
    ContactResource contact = persistActiveContact("jd1234");
    clock.advanceOneMilli();
    persistDomainWithDependentResources(
        "foo", "tld", contact, clock.nowUtc(), clock.nowUtc(), clock.nowUtc().plusYears(5));
    DateTime newExpirationTime = clock.nowUtc().plusYears(3);
    clock.advanceOneMilli();
    runCommandForced("-p", "2", "foo.tld");
    DateTime unrenewTime = clock.nowUtc();
    clock.advanceOneMilli();
    DomainBase domain = loadByForeignKey(DomainBase.class, "foo.tld", clock.nowUtc()).get();

    assertAboutHistoryEntries()
        .that(getOnlyHistoryEntryOfType(domain, SYNTHETIC))
        .hasModificationTime(unrenewTime)
        .and()
        .hasMetadataReason("Domain unrenewal")
        .and()
        .hasPeriodYears(2)
        .and()
        .hasClientId("TheRegistrar")
        .and()
        .bySuperuser(true)
        .and()
        .hasMetadataRequestedByRegistrar(false);
    HistoryEntry synthetic = getOnlyHistoryEntryOfType(domain, SYNTHETIC);

    assertBillingEventsEqual(
        ofy().load().key(domain.getAutorenewBillingEvent()).now(),
        new BillingEvent.Recurring.Builder()
            .setParent(synthetic)
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(domain.getDomainName())
            .setClientId("TheRegistrar")
            .setEventTime(newExpirationTime)
            .build());
    assertPollMessagesEqual(
        ofy().load().type(PollMessage.class).ancestor(synthetic).list(),
        ImmutableSet.of(
            new PollMessage.OneTime.Builder()
                .setParent(synthetic)
                .setClientId("TheRegistrar")
                .setMsg(
                    "Domain foo.tld was unrenewed by 2 years; "
                        + "now expires at 2019-12-06T13:55:01.001Z.")
                .setEventTime(unrenewTime)
                .build(),
            new PollMessage.Autorenew.Builder()
                .setParent(synthetic)
                .setTargetId("foo.tld")
                .setClientId("TheRegistrar")
                .setEventTime(newExpirationTime)
                .setMsg("Domain was auto-renewed.")
                .build()));

    // Check that fields on domain were updated correctly.
    assertThat(domain.getAutorenewPollMessage().getParent()).isEqualTo(Key.create(synthetic));
    assertThat(domain.getRegistrationExpirationTime()).isEqualTo(newExpirationTime);
    assertThat(domain.getLastEppUpdateTime()).isEqualTo(unrenewTime);
    assertThat(domain.getLastEppUpdateClientId()).isEqualTo("TheRegistrar");
  }

  @Test
  public void test_periodTooLow_fails() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> runCommandForced("--period", "0", "domain.tld"));
    assertThat(thrown).hasMessageThat().isEqualTo("Period must be in the range 1-9");
  }

  @Test
  public void test_periodTooHigh_fails() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> runCommandForced("--period", "10", "domain.tld"));
    assertThat(thrown).hasMessageThat().isEqualTo("Period must be in the range 1-9");
  }

  @Test
  public void test_varietyOfInvalidDomains_displaysErrors() {
    DateTime now = clock.nowUtc();
    persistResource(
        newDomainBase("deleting.tld")
            .asBuilder()
            .setDeletionTime(now.plusHours(1))
            .setStatusValues(ImmutableSet.of(PENDING_DELETE))
            .build());
    persistDeletedDomain("deleted.tld", now.minusHours(1));
    persistResource(
        newDomainBase("transferring.tld")
            .asBuilder()
            .setStatusValues(ImmutableSet.of(PENDING_TRANSFER))
            .build());
    persistResource(
        newDomainBase("locked.tld")
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
        .isEqualTo("Aborting because some domains cannot be unrewed");
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
