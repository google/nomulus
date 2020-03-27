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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.testing.DatastoreHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DomainBaseSubject.assertAboutDomains;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DomainBase}. */
public class DomainBaseTest extends EntityTestCase {

  private DomainBase domain;
  private Key<BillingEvent.OneTime> oneTimeBillKey;
  private Key<BillingEvent.Recurring> recurringBillKey;
  private Key<DomainBase> domainKey;

  @Before
  public void setUp() {
    createTld("com");
    domainKey = Key.create(null, DomainBase.class, "4-COM");
    VKey<HostResource> hostKey =
        persistResource(
                new HostResource.Builder()
                    .setFullyQualifiedHostName("ns1.example.com")
                    .setSuperordinateDomain(domainKey)
                    .setRepoId("1-COM")
                    .build())
            .createKey();
    Key<ContactResource> contact1Key =
        Key.create(
            persistResource(
                new ContactResource.Builder()
                    .setContactId("contact_id1")
                    .setRepoId("2-COM")
                    .build()));
    Key<ContactResource> contact2Key =
        Key.create(
            persistResource(
                new ContactResource.Builder()
                    .setContactId("contact_id2")
                    .setRepoId("3-COM")
                    .build()));
    Key<HistoryEntry> historyEntryKey =
        Key.create(persistResource(new HistoryEntry.Builder().setParent(domainKey).build()));
    oneTimeBillKey = Key.create(historyEntryKey, BillingEvent.OneTime.class, 1);
    recurringBillKey = Key.create(historyEntryKey, BillingEvent.Recurring.class, 2);
    Key<PollMessage.Autorenew> autorenewPollKey =
        Key.create(historyEntryKey, PollMessage.Autorenew.class, 3);
    Key<PollMessage.OneTime> onetimePollKey =
        Key.create(historyEntryKey, PollMessage.OneTime.class, 1);
    // Set up a new persisted domain entity.
    domain =
        persistResource(
            cloneAndSetAutoTimestamps(
                new DomainBase.Builder()
                    .setFullyQualifiedDomainName("example.com")
                    .setRepoId("4-COM")
                    .setCreationClientId("a registrar")
                    .setLastEppUpdateTime(fakeClock.nowUtc())
                    .setLastEppUpdateClientId("AnotherRegistrar")
                    .setLastTransferTime(fakeClock.nowUtc())
                    .setStatusValues(
                        ImmutableSet.of(
                            StatusValue.CLIENT_DELETE_PROHIBITED,
                            StatusValue.SERVER_DELETE_PROHIBITED,
                            StatusValue.SERVER_TRANSFER_PROHIBITED,
                            StatusValue.SERVER_UPDATE_PROHIBITED,
                            StatusValue.SERVER_RENEW_PROHIBITED,
                            StatusValue.SERVER_HOLD))
                    .setRegistrant(contact1Key)
                    .setContacts(ImmutableSet.of(DesignatedContact.create(Type.ADMIN, contact2Key)))
                    .setNameservers(ImmutableSet.of(hostKey))
                    .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
                    .setPersistedCurrentSponsorClientId("losing")
                    .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
                    .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
                    .setDsData(
                        ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
                    .setLaunchNotice(
                        LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
                    .setTransferData(
                        new TransferData.Builder()
                            .setGainingClientId("gaining")
                            .setLosingClientId("losing")
                            .setPendingTransferExpirationTime(fakeClock.nowUtc())
                            .setServerApproveEntities(
                                ImmutableSet.of(oneTimeBillKey, recurringBillKey, autorenewPollKey))
                            .setServerApproveBillingEvent(oneTimeBillKey)
                            .setServerApproveAutorenewEvent(recurringBillKey)
                            .setServerApproveAutorenewPollMessage(autorenewPollKey)
                            .setTransferRequestTime(fakeClock.nowUtc().plusDays(1))
                            .setTransferStatus(TransferStatus.SERVER_APPROVED)
                            .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                            .build())
                    .setDeletePollMessage(onetimePollKey)
                    .setAutorenewBillingEvent(recurringBillKey)
                    .setAutorenewPollMessage(autorenewPollKey)
                    .setSmdId("smdid")
                    .addGracePeriod(
                        GracePeriod.create(
                            GracePeriodStatus.ADD,
                            fakeClock.nowUtc().plusDays(1),
                            "registrar",
                            null))
                    .build()));
  }

  @Test
  public void testPersistence() {
    assertThat(loadByForeignKey(DomainBase.class, domain.getForeignKey(), fakeClock.nowUtc()))
        .hasValue(domain);
  }

  @Test
  public void testIndexing() throws Exception {
    verifyIndexing(
        domain,
        "allContacts.contact",
        "fullyQualifiedDomainName",
        "nsHosts",
        "currentSponsorClientId",
        "deletionTime",
        "tld");
  }

  @Test
  public void testEmptyStringsBecomeNull() {
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setPersistedCurrentSponsorClientId(null)
                .build()
                .getCurrentSponsorClientId())
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setPersistedCurrentSponsorClientId("")
                .build()
                .getCurrentSponsorClientId())
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setPersistedCurrentSponsorClientId(" ")
                .build()
                .getCurrentSponsorClientId())
        .isNotNull();
  }

  @Test
  public void testEmptySetsAndArraysBecomeNull() {
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(ImmutableSet.of())
                .build()
                .nsHosts)
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(ImmutableSet.of())
                .build()
                .nsHosts)
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(ImmutableSet.of(newHostResource("foo.example.tld").createKey()))
                .build()
                .nsHosts)
        .isNotNull();
    // This behavior should also hold true for ImmutableObjects nested in collections.
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 1, 1, (byte[]) null)))
                .build()
                .getDsData()
                .asList()
                .get(0)
                .getDigest())
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 1, 1, new byte[] {})))
                .build()
                .getDsData()
                .asList()
                .get(0)
                .getDigest())
        .isNull();
    assertThat(
            newDomainBase("example.com")
                .asBuilder()
                .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 1, 1, new byte[] {1})))
                .build()
                .getDsData()
                .asList()
                .get(0)
                .getDigest())
        .isNotNull();
  }

  @Test
  public void testEmptyTransferDataBecomesNull() {
    DomainBase withNull = newDomainBase("example.com").asBuilder().setTransferData(null).build();
    DomainBase withEmpty = withNull.asBuilder().setTransferData(TransferData.EMPTY).build();
    assertThat(withNull).isEqualTo(withEmpty);
    assertThat(withEmpty.transferData).isNull();
  }

  @Test
  public void testImplicitStatusValues() {
    ImmutableSet<VKey<HostResource>> nameservers =
        ImmutableSet.of(newHostResource("foo.example.tld").createKey());
    StatusValue[] statuses = {StatusValue.OK};
    // OK is implicit if there's no other statuses but there are nameservers.
    assertAboutDomains()
        .that(newDomainBase("example.com").asBuilder().setNameservers(nameservers).build())
        .hasExactlyStatusValues(statuses);
    StatusValue[] statuses1 = {StatusValue.CLIENT_HOLD};
    // If there are other status values, OK should be suppressed. (Domains can't be LINKED.)
    assertAboutDomains()
        .that(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(nameservers)
                .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses1);
    StatusValue[] statuses2 = {StatusValue.CLIENT_HOLD};
    // When OK is suppressed, it should be removed even if it was originally there.
    assertAboutDomains()
        .that(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(nameservers)
                .setStatusValues(ImmutableSet.of(StatusValue.OK, StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses2);
    StatusValue[] statuses3 = {StatusValue.INACTIVE};
    // If there are no nameservers, INACTIVE should be added, which suppresses OK.
    assertAboutDomains()
        .that(newDomainBase("example.com").asBuilder().build())
        .hasExactlyStatusValues(statuses3);
    StatusValue[] statuses4 = {StatusValue.CLIENT_HOLD, StatusValue.INACTIVE};
    // If there are no nameservers but there are status values, INACTIVE should still be added.
    assertAboutDomains()
        .that(
            newDomainBase("example.com")
                .asBuilder()
                .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses4);
    StatusValue[] statuses5 = {StatusValue.CLIENT_HOLD};
    // If there are nameservers, INACTIVE should be removed even if it was originally there.
    assertAboutDomains()
        .that(
            newDomainBase("example.com")
                .asBuilder()
                .setNameservers(nameservers)
                .setStatusValues(ImmutableSet.of(StatusValue.INACTIVE, StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses5);
  }

  private void assertTransferred(
      DomainBase domain,
      DateTime newExpirationTime,
      Key<BillingEvent.Recurring> newAutorenewEvent) {
    assertThat(domain.getTransferData().getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
    assertThat(domain.getCurrentSponsorClientId()).isEqualTo("winner");
    assertThat(domain.getLastTransferTime()).isEqualTo(fakeClock.nowUtc().plusDays(1));
    assertThat(domain.getRegistrationExpirationTime()).isEqualTo(newExpirationTime);
    assertThat(domain.getAutorenewBillingEvent()).isEqualTo(newAutorenewEvent);
  }

  private void doExpiredTransferTest(DateTime oldExpirationTime) {
    HistoryEntry historyEntry = new HistoryEntry.Builder().setParent(domain).build();
    BillingEvent.OneTime transferBillingEvent =
        persistResource(
            new BillingEvent.OneTime.Builder()
                .setReason(Reason.TRANSFER)
                .setClientId("winner")
                .setTargetId("example.com")
                .setEventTime(fakeClock.nowUtc())
                .setBillingTime(
                    fakeClock
                        .nowUtc()
                        .plusDays(1)
                        .plus(Registry.get("com").getTransferGracePeriodLength()))
                .setCost(Money.of(USD, 11))
                .setPeriodYears(1)
                .setParent(historyEntry)
                .build());
    domain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(oldExpirationTime)
            .setTransferData(
                domain
                    .getTransferData()
                    .asBuilder()
                    .setTransferStatus(TransferStatus.PENDING)
                    .setTransferRequestTime(fakeClock.nowUtc().minusDays(4))
                    .setPendingTransferExpirationTime(fakeClock.nowUtc().plusDays(1))
                    .setGainingClientId("winner")
                    .setServerApproveBillingEvent(Key.create(transferBillingEvent))
                    .setServerApproveEntities(ImmutableSet.of(Key.create(transferBillingEvent)))
                    .build())
            .addGracePeriod(
                // Okay for billing event to be null since the point of this grace period is just
                // to check that the transfer will clear all existing grace periods.
                GracePeriod.create(
                    GracePeriodStatus.ADD, fakeClock.nowUtc().plusDays(100), "foo", null))
            .build();
    DomainBase afterTransfer = domain.cloneProjectedAtTime(fakeClock.nowUtc().plusDays(1));
    DateTime newExpirationTime = oldExpirationTime.plusYears(1);
    Key<BillingEvent.Recurring> serverApproveAutorenewEvent =
        domain.getTransferData().getServerApproveAutorenewEvent();
    assertTransferred(afterTransfer, newExpirationTime, serverApproveAutorenewEvent);
    assertThat(afterTransfer.getGracePeriods())
        .containsExactly(
            GracePeriod.create(
                GracePeriodStatus.TRANSFER,
                fakeClock
                    .nowUtc()
                    .plusDays(1)
                    .plus(Registry.get("com").getTransferGracePeriodLength()),
                "winner",
                Key.create(transferBillingEvent)));
    // If we project after the grace period expires all should be the same except the grace period.
    DomainBase afterGracePeriod =
        domain.cloneProjectedAtTime(
            fakeClock
                .nowUtc()
                .plusDays(2)
                .plus(Registry.get("com").getTransferGracePeriodLength()));
    assertTransferred(afterGracePeriod, newExpirationTime, serverApproveAutorenewEvent);
    assertThat(afterGracePeriod.getGracePeriods()).isEmpty();
  }

  @Test
  public void testExpiredTransfer() {
    doExpiredTransferTest(fakeClock.nowUtc().plusMonths(1));
  }

  @Test
  public void testExpiredTransfer_autoRenewBeforeTransfer() {
    // Since transfer swallows a preceding autorenew, this should be identical to the regular
    // transfer case (and specifically, the new expiration and grace periods will be the same as if
    // there was no autorenew).
    doExpiredTransferTest(fakeClock.nowUtc().minusDays(1));
  }

  private void setupPendingTransferDomain(
      DateTime oldExpirationTime, DateTime transferRequestTime, DateTime transferSuccessTime) {
    domain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(oldExpirationTime)
            .setTransferData(
                domain
                    .getTransferData()
                    .asBuilder()
                    .setTransferStatus(TransferStatus.PENDING)
                    .setTransferRequestTime(transferRequestTime)
                    .setPendingTransferExpirationTime(transferSuccessTime)
                    .build())
            .setLastEppUpdateTime(transferRequestTime)
            .setLastEppUpdateClientId(domain.getTransferData().getGainingClientId())
            .build();
  }

  @Test
  public void testEppLastUpdateTimeAndClientId_autoRenewBeforeTransferSuccess() {
    DateTime now = fakeClock.nowUtc();
    DateTime transferRequestDateTime = now.plusDays(1);
    DateTime autorenewDateTime = now.plusDays(3);
    DateTime transferSuccessDateTime = now.plusDays(5);
    setupPendingTransferDomain(autorenewDateTime, transferRequestDateTime, transferSuccessDateTime);

    DomainBase beforeAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.minusDays(1));
    assertThat(beforeAutoRenew.getLastEppUpdateTime()).isEqualTo(transferRequestDateTime);
    assertThat(beforeAutoRenew.getLastEppUpdateClientId()).isEqualTo("gaining");

    // If autorenew happens before transfer succeeds(before transfer grace period starts as well),
    // lastEppUpdateClientId should still be the current sponsor client id
    DomainBase afterAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.plusDays(1));
    assertThat(afterAutoRenew.getLastEppUpdateTime()).isEqualTo(autorenewDateTime);
    assertThat(afterAutoRenew.getLastEppUpdateClientId()).isEqualTo("losing");
  }

  @Test
  public void testEppLastUpdateTimeAndClientId_autoRenewAfterTransferSuccess() {
    DateTime now = fakeClock.nowUtc();
    DateTime transferRequestDateTime = now.plusDays(1);
    DateTime autorenewDateTime = now.plusDays(3);
    DateTime transferSuccessDateTime = now.plusDays(5);
    setupPendingTransferDomain(autorenewDateTime, transferRequestDateTime, transferSuccessDateTime);

    DomainBase beforeAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.minusDays(1));
    assertThat(beforeAutoRenew.getLastEppUpdateTime()).isEqualTo(transferRequestDateTime);
    assertThat(beforeAutoRenew.getLastEppUpdateClientId()).isEqualTo("gaining");

    DomainBase afterTransferSuccess =
        domain.cloneProjectedAtTime(transferSuccessDateTime.plusDays(1));
    assertThat(afterTransferSuccess.getLastEppUpdateTime()).isEqualTo(transferSuccessDateTime);
    assertThat(afterTransferSuccess.getLastEppUpdateClientId()).isEqualTo("gaining");
  }

  private void setupUnmodifiedDomain(DateTime oldExpirationTime) {
    domain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(oldExpirationTime)
            .setTransferData(TransferData.EMPTY)
            .setGracePeriods(ImmutableSet.of())
            .setLastEppUpdateTime(null)
            .setLastEppUpdateClientId(null)
            .build();
  }

  @Test
  public void testEppLastUpdateTimeAndClientId_isSetCorrectlyWithNullPreviousValue() {
    DateTime now = fakeClock.nowUtc();
    DateTime autorenewDateTime = now.plusDays(3);
    setupUnmodifiedDomain(autorenewDateTime);

    DomainBase beforeAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.minusDays(1));
    assertThat(beforeAutoRenew.getLastEppUpdateTime()).isEqualTo(null);
    assertThat(beforeAutoRenew.getLastEppUpdateClientId()).isEqualTo(null);

    DomainBase afterAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.plusDays(1));
    assertThat(afterAutoRenew.getLastEppUpdateTime()).isEqualTo(autorenewDateTime);
    assertThat(afterAutoRenew.getLastEppUpdateClientId()).isEqualTo("losing");
  }

  @Test
  public void testStackedGracePeriods() {
    ImmutableList<GracePeriod> gracePeriods =
        ImmutableList.of(
            GracePeriod.create(GracePeriodStatus.ADD, fakeClock.nowUtc().plusDays(3), "foo", null),
            GracePeriod.create(GracePeriodStatus.ADD, fakeClock.nowUtc().plusDays(2), "bar", null),
            GracePeriod.create(GracePeriodStatus.ADD, fakeClock.nowUtc().plusDays(1), "baz", null));
    domain = domain.asBuilder().setGracePeriods(ImmutableSet.copyOf(gracePeriods)).build();
    for (int i = 1; i < 3; ++i) {
      assertThat(domain.cloneProjectedAtTime(fakeClock.nowUtc().plusDays(i)).getGracePeriods())
          .containsExactlyElementsIn(Iterables.limit(gracePeriods, 3 - i));
    }
  }

  @Test
  public void testGracePeriodsByType() {
    ImmutableSet<GracePeriod> addGracePeriods =
        ImmutableSet.of(
            GracePeriod.create(GracePeriodStatus.ADD, fakeClock.nowUtc().plusDays(3), "foo", null),
            GracePeriod.create(GracePeriodStatus.ADD, fakeClock.nowUtc().plusDays(1), "baz", null));
    ImmutableSet<GracePeriod> renewGracePeriods =
        ImmutableSet.of(
            GracePeriod.create(
                GracePeriodStatus.RENEW, fakeClock.nowUtc().plusDays(3), "foo", null),
            GracePeriod.create(
                GracePeriodStatus.RENEW, fakeClock.nowUtc().plusDays(1), "baz", null));
    domain =
        domain
            .asBuilder()
            .setGracePeriods(
                Streams.concat(addGracePeriods.stream(), renewGracePeriods.stream())
                    .collect(toImmutableSet()))
            .build();
    assertThat(domain.getGracePeriodsOfType(GracePeriodStatus.ADD)).isEqualTo(addGracePeriods);
    assertThat(domain.getGracePeriodsOfType(GracePeriodStatus.RENEW)).isEqualTo(renewGracePeriods);
    assertThat(domain.getGracePeriodsOfType(GracePeriodStatus.TRANSFER)).isEmpty();
  }

  @Test
  public void testRenewalsHappenAtExpiration() {
    DomainBase renewed = domain.cloneProjectedAtTime(domain.getRegistrationExpirationTime());
    assertThat(renewed.getRegistrationExpirationTime())
        .isEqualTo(domain.getRegistrationExpirationTime().plusYears(1));
    assertThat(renewed.getLastEppUpdateTime()).isEqualTo(domain.getRegistrationExpirationTime());
    assertThat(getOnlyElement(renewed.getGracePeriods()).getType())
        .isEqualTo(GracePeriodStatus.AUTO_RENEW);
  }

  @Test
  public void testTldGetsSet() {
    createTld("tld");
    domain = newDomainBase("foo.tld");
    assertThat(domain.getTld()).isEqualTo("tld");
  }

  @Test
  public void testRenewalsDontHappenOnFebruary29() {
    domain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(DateTime.parse("2004-02-29T22:00:00.0Z"))
            .build();
    DomainBase renewed =
        domain.cloneProjectedAtTime(domain.getRegistrationExpirationTime().plusYears(4));
    assertThat(renewed.getRegistrationExpirationTime().getDayOfMonth()).isEqualTo(28);
  }

  @Test
  public void testMultipleAutoRenews() {
    // Change the registry so that renewal costs change every year to make sure we are using the
    // autorenew time as the lookup time for the cost.
    DateTime oldExpirationTime = domain.getRegistrationExpirationTime();
    persistResource(
        Registry.get("com")
            .asBuilder()
            .setRenewBillingCostTransitions(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 1))
                    .put(oldExpirationTime.plusMillis(1), Money.of(USD, 2))
                    .put(oldExpirationTime.plusYears(1).plusMillis(1), Money.of(USD, 3))
                    // Surround the third autorenew with price changes right before and after just
                    // to be 100% sure that we lookup the cost at the expiration time.
                    .put(oldExpirationTime.plusYears(2).minusMillis(1), Money.of(USD, 4))
                    .put(oldExpirationTime.plusYears(2).plusMillis(1), Money.of(USD, 5))
                    .build())
            .build());
    DomainBase renewedThreeTimes = domain.cloneProjectedAtTime(oldExpirationTime.plusYears(2));
    assertThat(renewedThreeTimes.getRegistrationExpirationTime())
        .isEqualTo(oldExpirationTime.plusYears(3));
    assertThat(renewedThreeTimes.getLastEppUpdateTime()).isEqualTo(oldExpirationTime.plusYears(2));
    assertThat(renewedThreeTimes.getGracePeriods())
        .containsExactly(
            GracePeriod.createForRecurring(
                GracePeriodStatus.AUTO_RENEW,
                oldExpirationTime
                    .plusYears(2)
                    .plus(Registry.get("com").getAutoRenewGracePeriodLength()),
                renewedThreeTimes.getCurrentSponsorClientId(),
                renewedThreeTimes.autorenewBillingEvent));
  }

  @Test
  public void testToHydratedString_notCircular() {
    domain.toHydratedString(); // If there are circular references, this will overflow the stack.
  }

  @Test
  public void testFailure_uppercaseDomainName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> domain.asBuilder().setFullyQualifiedDomainName("AAA.BBB"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Domain name must be in puny-coded, lower-case form");
  }

  @Test
  public void testFailure_utf8DomainName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> domain.asBuilder().setFullyQualifiedDomainName("みんな.みんな"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Domain name must be in puny-coded, lower-case form");
  }

  @Test
  public void testClone_doNotExtendExpirationOnDeletedDomain() {
    DateTime now = DateTime.now(UTC);
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(now.minusDays(1))
                .setDeletionTime(now.minusDays(10))
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE, StatusValue.INACTIVE))
                .build());
    assertThat(domain.cloneProjectedAtTime(now).getRegistrationExpirationTime())
        .isEqualTo(now.minusDays(1));
  }

  @Test
  public void testClone_doNotExtendExpirationOnFutureDeletedDomain() {
    // if a domain is in pending deletion (StatusValue.PENDING_DELETE), don't extend expiration
    DateTime now = DateTime.now(UTC);
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(now.plusDays(1))
                .setDeletionTime(now.plusDays(20))
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE, StatusValue.INACTIVE))
                .build());
    assertThat(domain.cloneProjectedAtTime(now).getRegistrationExpirationTime())
        .isEqualTo(now.plusDays(1));
  }

  @Test
  public void testClone_extendsExpirationForExpiredTransferredDomain() {
    // If the transfer implicitly succeeded, the expiration time should be extended
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.minusDays(1);
    DateTime previousExpiration = now.minusDays(2);

    TransferData transferData =
        new TransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingClientId("TheRegistrar")
            .build();
    Period extensionPeriod = transferData.getTransferPeriod();
    DateTime newExpiration = previousExpiration.plusYears(extensionPeriod.getValue());
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setTransferData(transferData)
                .build());

    assertThat(domain.cloneProjectedAtTime(now).getRegistrationExpirationTime())
        .isEqualTo(newExpiration);
  }

  @Test
  public void testClone_extendsExpirationForNonExpiredTransferredDomain() {
    // If the transfer implicitly succeeded, the expiration time should be extended even if it
    // hadn't already expired
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.minusDays(1);
    DateTime previousExpiration = now.plusWeeks(2);

    TransferData transferData =
        new TransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingClientId("TheRegistrar")
            .build();
    Period extensionPeriod = transferData.getTransferPeriod();
    DateTime newExpiration = previousExpiration.plusYears(extensionPeriod.getValue());
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setTransferData(transferData)
                .build());

    assertThat(domain.cloneProjectedAtTime(now).getRegistrationExpirationTime())
        .isEqualTo(newExpiration);
  }

  @Test
  public void testClone_doesNotExtendExpirationForPendingTransfer() {
    // Pending transfers shouldn't affect the expiration time
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.plusDays(1);
    DateTime previousExpiration = now.plusWeeks(2);

    TransferData transferData =
        new TransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingClientId("TheRegistrar")
            .build();
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setTransferData(transferData)
                .build());

    assertThat(domain.cloneProjectedAtTime(now).getRegistrationExpirationTime())
        .isEqualTo(previousExpiration);
  }

  @Test
  public void testClone_transferDuringAutorenew() {
    // When the domain is an an autorenew grace period, we should not extend the registration
    // expiration by a further year--it should just be whatever the autorenew was
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.minusDays(1);
    DateTime previousExpiration = now.minusDays(2);

    TransferData transferData =
        new TransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingClientId("TheRegistrar")
            .setServerApproveAutorenewEvent(recurringBillKey)
            .setServerApproveBillingEvent(oneTimeBillKey)
            .build();
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.createForRecurring(
                            GracePeriodStatus.AUTO_RENEW,
                            now.plusDays(1),
                            "NewRegistrar",
                            recurringBillKey)))
                .setTransferData(transferData)
                .setAutorenewBillingEvent(recurringBillKey)
                .build());
    DomainBase clone = domain.cloneProjectedAtTime(now);
    assertThat(clone.getRegistrationExpirationTime())
        .isEqualTo(domain.getRegistrationExpirationTime().plusYears(1));
    // Transferring removes the AUTORENEW grace period and adds a TRANSFER grace period
    assertThat(getOnlyElement(clone.getGracePeriods()).getType())
        .isEqualTo(GracePeriodStatus.TRANSFER);
  }

  private static ImmutableSet<Key<HostResource>> getOfyNameservers(DomainBase domain) {
    return domain.getNameservers().stream().map(key -> key.getOfyKey()).collect(toImmutableSet());
  }

  @Test
  public void testNameservers_nsHostsOfyKeys() {
    assertThat(domain.nsHosts).isEqualTo(getOfyNameservers(domain));

    // Test the setNameserver that functions on a function.
    VKey<HostResource> host1Key =
        persistResource(
                new HostResource.Builder()
                    .setFullyQualifiedHostName("ns2.example.com")
                    .setSuperordinateDomain(domainKey)
                    .setRepoId("2-COM")
                    .build())
            .createKey();

    DomainBase dom = new DomainBase.Builder(domain).setNameservers(host1Key).build();
    assertThat(dom.getNameservers()).isEqualTo(ImmutableSet.of(host1Key));
    assertThat(getOfyNameservers(dom)).isEqualTo(ImmutableSet.of(host1Key.getOfyKey()));

    // Test that setting to a single host of null throws an NPE.
    assertThrows(
        NullPointerException.class,
        () -> new DomainBase.Builder(domain).setNameservers((VKey<HostResource>) null));

    // Test that setting to a set of values works.
    VKey<HostResource> host2Key =
        persistResource(
                new HostResource.Builder()
                    .setFullyQualifiedHostName("ns3.example.com")
                    .setSuperordinateDomain(domainKey)
                    .setRepoId("3-COM")
                    .build())
            .createKey();
    dom =
        new DomainBase.Builder(domain).setNameservers(ImmutableSet.of(host1Key, host2Key)).build();
    assertThat(dom.getNameservers()).isEqualTo(ImmutableSet.of(host1Key, host2Key));
    assertThat(getOfyNameservers(dom))
        .isEqualTo(ImmutableSet.of(host1Key.getOfyKey(), host2Key.getOfyKey()));

    // Set of values, passing null.
    dom =
        new DomainBase.Builder(domain)
            .setNameservers((ImmutableSet<VKey<HostResource>>) null)
            .build();
    assertThat(dom.nsHostVKeys).isNull();
    assertThat(dom.nsHosts).isNull();

    // Empty set of values gets translated to null.
    dom = new DomainBase.Builder(domain).setNameservers(ImmutableSet.of()).build();
    assertThat(dom.nsHostVKeys).isNull();
    assertThat(dom.nsHosts).isNull();
  }
}
