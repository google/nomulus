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
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.domain.token.AllocationToken.TokenType.BULK_PRICING;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.testing.DatabaseHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newHost;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.ImmutableObject;
import google.registry.model.ImmutableObjectSubject;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.contact.Contact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.Host;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link Domain}. */
@SuppressWarnings("WeakerAccess") // Referred to by EppInputTest.
public class DomainTest {

  protected FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  private Domain domain;
  private VKey<BillingEvent> oneTimeBillKey;
  private VKey<BillingRecurrence> recurrenceBillKey;
  private DomainHistory domainHistory;
  private VKey<Contact> contact1Key, contact2Key;

  @BeforeEach
  void setUp() {
    saveRegistrar("registrar1");
    saveRegistrar("registrar2");
    saveRegistrar("losing");
    saveRegistrar("gaining");
    createTld("com");
    domain = persistActiveDomain("example.com");
    VKey<Host> hostKey = persistActiveHost("ns1.example.com").createVKey();
    contact1Key = persistActiveContact("contact_id1").createVKey();
    contact2Key = persistActiveContact("contact_id1").createVKey();
    domainHistory =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(domain)
                .setModificationTime(fakeClock.nowUtc())
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setRegistrarId("TheRegistrar")
                .build());
    oneTimeBillKey =
        persistResource(
                new BillingEvent.Builder()
                    // Use SERVER_STATUS, so we don't have to add a period.
                    .setReason(Reason.SERVER_STATUS)
                    .setTargetId(domain.getDomainName())
                    .setRegistrarId(domain.getCurrentSponsorRegistrarId())
                    .setBillingTime(DateTime.now(UTC))
                    .setCost(Money.of(USD, 100))
                    .setEventTime(DateTime.now(UTC).plusYears(1))
                    .setDomainHistory(domainHistory)
                    .build())
            .createVKey();
    DomainHistory historyEntry =
        new DomainHistory.Builder()
            .setRevisionId(100L)
            .setType(HistoryEntry.Type.DOMAIN_CREATE)
            .setPeriod(Period.create(1, Period.Unit.YEARS))
            .setModificationTime(DateTime.now(UTC))
            .setDomain(domain)
            .setRegistrarId(domain.getCurrentSponsorRegistrarId())
            // These are non-null, but I don't think some tests set them.
            .setReason("felt like it")
            .setRequestedByRegistrar(false)
            .setXmlBytes(new byte[0])
            .build();
    BillingEvent billingEventBill =
        new BillingEvent.Builder()
            .setId(500L)
            // Use SERVER_STATUS, so we don't have to add a period.
            .setReason(Reason.SERVER_STATUS)
            .setTargetId("example.com")
            .setRegistrarId("registrar1")
            .setBillingTime(DateTime.now(UTC))
            .setCost(Money.of(USD, 100))
            .setEventTime(DateTime.now(UTC).plusYears(1))
            .setDomainHistory(historyEntry)
            .build();
    oneTimeBillKey = billingEventBill.createVKey();
    BillingRecurrence billingRecurrence =
        new BillingRecurrence.Builder()
            .setId(200L)
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(domain.getDomainName())
            .setRegistrarId(domain.getCurrentSponsorRegistrarId())
            .setEventTime(DateTime.now(UTC).plusYears(1))
            .setRecurrenceEndTime(END_OF_TIME)
            .setDomainHistory(historyEntry)
            .build();
    persistResources(historyEntry, billingEventBill, billingRecurrence);
    recurrenceBillKey = billingRecurrence.createVKey();
    VKey<PollMessage.Autorenew> autorenewPollKey = VKey.create(PollMessage.Autorenew.class, 3L);
    VKey<PollMessage.OneTime> onetimePollKey = VKey.create(PollMessage.OneTime.class, 1L);
    // Set up a new persisted domain entity.
    domain =
        persistResource(
            cloneAndSetAutoTimestamps(
                domain
                    .asBuilder()
                    .setCreationRegistrarId("TheRegistrar")
                    .setLastEppUpdateTime(fakeClock.nowUtc())
                    .setLastEppUpdateRegistrarId("NewRegistrar")
                    .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                    .setLastTransferTime(fakeClock.nowUtc())
                    .setStatusValues(
                        ImmutableSet.of(
                            StatusValue.CLIENT_DELETE_PROHIBITED,
                            StatusValue.SERVER_DELETE_PROHIBITED,
                            StatusValue.SERVER_TRANSFER_PROHIBITED,
                            StatusValue.SERVER_UPDATE_PROHIBITED,
                            StatusValue.SERVER_RENEW_PROHIBITED,
                            StatusValue.SERVER_HOLD))
                    .setRegistrant(Optional.of(contact1Key))
                    .setNameservers(ImmutableSet.of(hostKey))
                    .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
                    .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                    .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
                    .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
                    .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, new byte[] {0, 1, 2})))
                    .setLaunchNotice(
                        LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
                    .setTransferData(
                        new DomainTransferData.Builder()
                            .setGainingRegistrarId("TheRegistrar")
                            .setLosingRegistrarId("NewRegistrar")
                            .setPendingTransferExpirationTime(fakeClock.nowUtc())
                            .setServerApproveEntities(
                                historyEntry.getRepoId(),
                                historyEntry.getRevisionId(),
                                ImmutableSet.of(
                                    oneTimeBillKey, recurrenceBillKey, autorenewPollKey))
                            .setServerApproveBillingEvent(oneTimeBillKey)
                            .setServerApproveAutorenewEvent(recurrenceBillKey)
                            .setServerApproveAutorenewPollMessage(autorenewPollKey)
                            .setTransferRequestTime(fakeClock.nowUtc().plusDays(1))
                            .setTransferStatus(TransferStatus.SERVER_APPROVED)
                            .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                            .build())
                    .setDeletePollMessage(onetimePollKey)
                    .setAutorenewBillingEvent(recurrenceBillKey)
                    .setAutorenewPollMessage(autorenewPollKey)
                    .setSmdId("smdid")
                    .addGracePeriod(
                        GracePeriod.create(
                            GracePeriodStatus.ADD,
                            domain.getRepoId(),
                            fakeClock.nowUtc().plusDays(1),
                            "TheRegistrar",
                            oneTimeBillKey))
                    .setAutorenewEndTime(Optional.of(fakeClock.nowUtc().plusYears(2)))
                    .build()));
  }

  @Test
  void testDomainBaseToDomain() {
    ImmutableObjectSubject.assertAboutImmutableObjects()
        .that(new Domain.Builder().copyFrom(domain).build())
        .isEqualExceptFields(domain, "updateTimestamp", "revisions");
  }

  @Test
  void testPersistence() {
    // Note that this only verifies that the value stored under the foreign key is the same as that
    // stored under the primary key ("domain" is the domain loaded from the the database, not the
    // original domain object).
    String foreignKey = domain.getForeignKey();
    assertThat(ForeignKeyUtils.loadResource(Domain.class, foreignKey, fakeClock.nowUtc()))
        .hasValue(domain);
  }

  @Test
  void testRegistrantNotRequired() {
    persistResource(domain.asBuilder().setRegistrant(Optional.empty()).build());
    String foreignKey = domain.getForeignKey();
    assertThat(
            ForeignKeyUtils.loadResource(Domain.class, foreignKey, fakeClock.nowUtc())
                .get()
                .getRegistrant())
        .isEmpty();
  }

  @Test
  void testEmptyStringsBecomeNull() {
    assertThat(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setPersistedCurrentSponsorRegistrarId(null)
                .build()
                .getCurrentSponsorRegistrarId())
        .isNull();
    assertThat(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setPersistedCurrentSponsorRegistrarId("")
                .build()
                .getCurrentSponsorRegistrarId())
        .isNull();
    assertThat(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setPersistedCurrentSponsorRegistrarId(" ")
                .build()
                .getCurrentSponsorRegistrarId())
        .isNotNull();
  }

  void testEmptySetsAndArraysBecomeNull() {
    assertThat(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setNameservers(ImmutableSet.of())
                .build()
                .nsHosts)
        .isNull();
    assertThat(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setNameservers(ImmutableSet.of())
                .build()
                .nsHosts)
        .isNull();
    assertThat(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setNameservers(ImmutableSet.of(newHost("foo.example.tld").createVKey()))
                .build()
                .nsHosts)
        .isNotNull();
    // This behavior should also hold true for ImmutableObjects nested in collections.
    assertThat(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setDsData(ImmutableSet.of(DomainDsData.create(1, 1, 1, (byte[]) null)))
                .build()
                .getDsData()
                .asList()
                .get(0)
                .getDigest())
        .isNull();
    assertThat(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setDsData(ImmutableSet.of(DomainDsData.create(1, 1, 1, new byte[] {})))
                .build()
                .getDsData()
                .asList()
                .get(0)
                .getDigest())
        .isNull();
    assertThat(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setDsData(ImmutableSet.of(DomainDsData.create(1, 1, 1, new byte[] {1})))
                .build()
                .getDsData()
                .asList()
                .get(0)
                .getDigest())
        .isNotNull();
  }

  @Test
  void testEmptyTransferDataBecomesNull() {
    Domain withNull =
        DatabaseHelper.newDomain("example.com").asBuilder().setTransferData(null).build();
    Domain withEmpty = withNull.asBuilder().setTransferData(DomainTransferData.EMPTY).build();
    assertThat(withNull).isEqualTo(withEmpty);
    assertThat(withEmpty.transferData).isNull();
  }

  @Test
  void testImplicitStatusValues() {
    ImmutableSet<VKey<Host>> nameservers = ImmutableSet.of(newHost("foo.example.tld").createVKey());
    StatusValue[] statuses = {StatusValue.OK};
    // OK is implicit if there's no other statuses but there are nameservers.
    assertAboutDomains()
        .that(
            DatabaseHelper.newDomain("example.com").asBuilder().setNameservers(nameservers).build())
        .hasExactlyStatusValues(statuses);
    StatusValue[] statuses1 = {StatusValue.CLIENT_HOLD};
    // If there are other status values, OK should be suppressed. (Domains can't be LINKED.)
    assertAboutDomains()
        .that(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setNameservers(nameservers)
                .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses1);
    StatusValue[] statuses2 = {StatusValue.CLIENT_HOLD};
    // When OK is suppressed, it should be removed even if it was originally there.
    assertAboutDomains()
        .that(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setNameservers(nameservers)
                .setStatusValues(ImmutableSet.of(StatusValue.OK, StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses2);
    StatusValue[] statuses3 = {StatusValue.INACTIVE};
    // If there are no nameservers, INACTIVE should be added, which suppresses OK.
    assertAboutDomains()
        .that(DatabaseHelper.newDomain("example.com").asBuilder().build())
        .hasExactlyStatusValues(statuses3);
    StatusValue[] statuses4 = {StatusValue.CLIENT_HOLD, StatusValue.INACTIVE};
    // If there are no nameservers but there are status values, INACTIVE should still be added.
    assertAboutDomains()
        .that(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses4);
    StatusValue[] statuses5 = {StatusValue.CLIENT_HOLD};
    // If there are nameservers, INACTIVE should be removed even if it was originally there.
    assertAboutDomains()
        .that(
            DatabaseHelper.newDomain("example.com")
                .asBuilder()
                .setNameservers(nameservers)
                .setStatusValues(ImmutableSet.of(StatusValue.INACTIVE, StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(statuses5);
  }

  private void assertTransferred(
      Domain domain, DateTime newExpirationTime, VKey<BillingRecurrence> newAutorenewEvent) {
    assertThat(domain.getTransferData().getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
    assertThat(domain.getCurrentSponsorRegistrarId()).isEqualTo("TheRegistrar");
    assertThat(domain.getLastTransferTime()).isEqualTo(fakeClock.nowUtc().plusDays(1));
    assertThat(domain.getRegistrationExpirationTime()).isEqualTo(newExpirationTime);
    assertThat(domain.getAutorenewBillingEvent()).isEqualTo(newAutorenewEvent);
  }

  private void doExpiredTransferTest(DateTime oldExpirationTime) {
    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(domain)
                .setModificationTime(fakeClock.nowUtc())
                .setRegistrarId(domain.getCurrentSponsorRegistrarId())
                .setType(HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST)
                .build());
    BillingEvent transferBillingEvent =
        persistResource(
            new BillingEvent.Builder()
                .setReason(Reason.TRANSFER)
                .setRegistrarId("TheRegistrar")
                .setTargetId(domain.getDomainName())
                .setEventTime(fakeClock.nowUtc())
                .setBillingTime(
                    fakeClock
                        .nowUtc()
                        .plusDays(1)
                        .plus(Tld.get("com").getTransferGracePeriodLength()))
                .setCost(Money.of(USD, 11))
                .setPeriodYears(1)
                .setDomainHistory(historyEntry)
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
                    .setGainingRegistrarId("TheRegistrar")
                    .setServerApproveBillingEvent(transferBillingEvent.createVKey())
                    .setServerApproveEntities(
                        domain.getRepoId(),
                        historyEntry.getRevisionId(),
                        ImmutableSet.of(transferBillingEvent.createVKey()))
                    .build())
            .addGracePeriod(
                // Okay for billing event to be null since the point of this grace period is just
                // to check that the transfer will clear all existing grace periods.
                GracePeriod.create(
                    GracePeriodStatus.ADD,
                    domain.getRepoId(),
                    fakeClock.nowUtc().plusDays(100),
                    "TheRegistrar",
                    oneTimeBillKey))
            .build();
    Domain afterTransfer = domain.cloneProjectedAtTime(fakeClock.nowUtc().plusDays(1));
    DateTime newExpirationTime = oldExpirationTime.plusYears(1);
    VKey<BillingRecurrence> serverApproveAutorenewEvent =
        domain.getTransferData().getServerApproveAutorenewEvent();
    assertTransferred(afterTransfer, newExpirationTime, serverApproveAutorenewEvent);
    assertThat(afterTransfer.getGracePeriods())
        .containsExactly(
            GracePeriod.create(
                GracePeriodStatus.TRANSFER,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(1).plus(Tld.get("com").getTransferGracePeriodLength()),
                "TheRegistrar",
                transferBillingEvent.createVKey(),
                afterTransfer.getGracePeriods().iterator().next().getGracePeriodId()));
    // If we project after the grace period expires all should be the same except the grace period.
    Domain afterGracePeriod =
        domain.cloneProjectedAtTime(
            fakeClock.nowUtc().plusDays(2).plus(Tld.get("com").getTransferGracePeriodLength()));
    assertTransferred(afterGracePeriod, newExpirationTime, serverApproveAutorenewEvent);
    assertThat(afterGracePeriod.getGracePeriods()).isEmpty();
  }

  @Test
  void testExpiredTransfer() {
    doExpiredTransferTest(fakeClock.nowUtc().plusMonths(1));
  }

  @Test
  void testExpiredTransfer_autoRenewBeforeTransfer() {
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
            .setLastEppUpdateRegistrarId(domain.getTransferData().getGainingRegistrarId())
            .build();
  }

  @Test
  void testEppLastUpdateTimeAndClientId_autoRenewBeforeTransferSuccess() {
    DateTime now = fakeClock.nowUtc();
    DateTime transferRequestDateTime = now.plusDays(1);
    DateTime autorenewDateTime = now.plusDays(3);
    DateTime transferSuccessDateTime = now.plusDays(5);
    setupPendingTransferDomain(autorenewDateTime, transferRequestDateTime, transferSuccessDateTime);

    Domain beforeAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.minusDays(1));
    assertThat(beforeAutoRenew.getLastEppUpdateTime()).isEqualTo(transferRequestDateTime);
    assertThat(beforeAutoRenew.getLastEppUpdateRegistrarId()).isEqualTo("TheRegistrar");

    // If autorenew happens before transfer succeeds(before transfer grace period starts as well),
    // lastEppUpdateRegistrarId should still be the current sponsor client id
    Domain afterAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.plusDays(1));
    assertThat(afterAutoRenew.getLastEppUpdateTime()).isEqualTo(autorenewDateTime);
    assertThat(afterAutoRenew.getLastEppUpdateRegistrarId()).isEqualTo("NewRegistrar");
  }

  @Test
  void testEppLastUpdateTimeAndClientId_autoRenewAfterTransferSuccess() {
    DateTime now = fakeClock.nowUtc();
    DateTime transferRequestDateTime = now.plusDays(1);
    DateTime autorenewDateTime = now.plusDays(3);
    DateTime transferSuccessDateTime = now.plusDays(5);
    setupPendingTransferDomain(autorenewDateTime, transferRequestDateTime, transferSuccessDateTime);

    Domain beforeAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.minusDays(1));
    assertThat(beforeAutoRenew.getLastEppUpdateTime()).isEqualTo(transferRequestDateTime);
    assertThat(beforeAutoRenew.getLastEppUpdateRegistrarId()).isEqualTo("TheRegistrar");

    Domain afterTransferSuccess = domain.cloneProjectedAtTime(transferSuccessDateTime.plusDays(1));
    assertThat(afterTransferSuccess.getLastEppUpdateTime()).isEqualTo(transferSuccessDateTime);
    assertThat(afterTransferSuccess.getLastEppUpdateRegistrarId()).isEqualTo("TheRegistrar");
  }

  private void setupUnmodifiedDomain(DateTime oldExpirationTime) {
    domain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(oldExpirationTime)
            .setTransferData(DomainTransferData.EMPTY)
            .setGracePeriods(ImmutableSet.of())
            .setLastEppUpdateTime(null)
            .setLastEppUpdateRegistrarId(null)
            .build();
  }

  @Test
  void testEppLastUpdateTimeAndClientId_isSetCorrectlyWithNullPreviousValue() {
    DateTime now = fakeClock.nowUtc();
    DateTime autorenewDateTime = now.plusDays(3);
    setupUnmodifiedDomain(autorenewDateTime);

    Domain beforeAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.minusDays(1));
    assertThat(beforeAutoRenew.getLastEppUpdateTime()).isEqualTo(null);
    assertThat(beforeAutoRenew.getLastEppUpdateRegistrarId()).isEqualTo(null);

    Domain afterAutoRenew = domain.cloneProjectedAtTime(autorenewDateTime.plusDays(1));
    assertThat(afterAutoRenew.getLastEppUpdateTime()).isEqualTo(autorenewDateTime);
    assertThat(afterAutoRenew.getLastEppUpdateRegistrarId()).isEqualTo("NewRegistrar");
  }

  @Test
  void testStackedGracePeriods() {
    ImmutableList<GracePeriod> gracePeriods =
        ImmutableList.of(
            GracePeriod.create(
                GracePeriodStatus.ADD,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(3),
                "foo",
                null),
            GracePeriod.create(
                GracePeriodStatus.ADD,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(2),
                "bar",
                null),
            GracePeriod.create(
                GracePeriodStatus.ADD,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(1),
                "baz",
                null));
    domain = domain.asBuilder().setGracePeriods(ImmutableSet.copyOf(gracePeriods)).build();
    for (int i = 1; i < 3; ++i) {
      assertThat(domain.cloneProjectedAtTime(fakeClock.nowUtc().plusDays(i)).getGracePeriods())
          .containsExactlyElementsIn(Iterables.limit(gracePeriods, 3 - i));
    }
  }

  @Test
  void testGracePeriodsByType() {
    ImmutableSet<GracePeriod> addGracePeriods =
        ImmutableSet.of(
            GracePeriod.create(
                GracePeriodStatus.ADD,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(3),
                "foo",
                null),
            GracePeriod.create(
                GracePeriodStatus.ADD,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(1),
                "baz",
                null));
    ImmutableSet<GracePeriod> renewGracePeriods =
        ImmutableSet.of(
            GracePeriod.create(
                GracePeriodStatus.RENEW,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(3),
                "foo",
                null),
            GracePeriod.create(
                GracePeriodStatus.RENEW,
                domain.getRepoId(),
                fakeClock.nowUtc().plusDays(1),
                "baz",
                null));
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
  void testRenewalsHappenAtExpiration() {
    Domain renewed = domain.cloneProjectedAtTime(domain.getRegistrationExpirationTime());
    assertThat(renewed.getRegistrationExpirationTime())
        .isEqualTo(domain.getRegistrationExpirationTime().plusYears(1));
    assertThat(renewed.getLastEppUpdateTime()).isEqualTo(domain.getRegistrationExpirationTime());
    assertThat(getOnlyElement(renewed.getGracePeriods()).getType())
        .isEqualTo(GracePeriodStatus.AUTO_RENEW);
  }

  @Test
  void testTldGetsSet() {
    createTld("tld");
    domain = DatabaseHelper.newDomain("foo.tld");
    assertThat(domain.getTld()).isEqualTo("tld");
  }

  @Test
  void testRenewalsDontHappenOnFebruary29() {
    domain =
        domain
            .asBuilder()
            .setRegistrationExpirationTime(DateTime.parse("2004-02-29T22:00:00.0Z"))
            .build();
    Domain renewed =
        domain.cloneProjectedAtTime(domain.getRegistrationExpirationTime().plusYears(4));
    assertThat(renewed.getRegistrationExpirationTime().getDayOfMonth()).isEqualTo(28);
  }

  @Test
  void testMultipleAutoRenews() {
    // Change the registry so that renewal costs change every year to make sure we are using the
    // autorenew time as the lookup time for the cost.
    DateTime oldExpirationTime = domain.getRegistrationExpirationTime();
    persistResource(
        Tld.get("com")
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
    Domain renewedThreeTimes = domain.cloneProjectedAtTime(oldExpirationTime.plusYears(2));
    assertThat(renewedThreeTimes.getRegistrationExpirationTime())
        .isEqualTo(oldExpirationTime.plusYears(3));
    assertThat(renewedThreeTimes.getLastEppUpdateTime()).isEqualTo(oldExpirationTime.plusYears(2));
    assertThat(renewedThreeTimes.getGracePeriods())
        .containsExactly(
            GracePeriod.createForRecurrence(
                GracePeriodStatus.AUTO_RENEW,
                domain.getRepoId(),
                oldExpirationTime.plusYears(2).plus(Tld.get("com").getAutoRenewGracePeriodLength()),
                renewedThreeTimes.getCurrentSponsorRegistrarId(),
                renewedThreeTimes.autorenewBillingEvent,
                renewedThreeTimes.getGracePeriods().iterator().next().getGracePeriodId()));
  }

  @Test
  void testToHydratedString_notCircular() {
    domain.toHydratedString(); // If there are circular references, this will overflow the stack.
  }

  @Test
  void testFailure_uppercaseDomainName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> domain.asBuilder().setDomainName("AAA.BBB"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name AAA.BBB not in puny-coded, lower-case form");
  }

  @Test
  void testFailure_utf8DomainName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> domain.asBuilder().setDomainName("みんな.みんな"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name みんな.みんな not in puny-coded, lower-case form");
  }

  @Test
  void testClone_doNotExtendExpirationOnDeletedDomain() {
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
  void testClone_doNotExtendExpirationOnFutureDeletedDomain() {
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
  void testClone_extendsExpirationForExpiredTransferredDomain() {
    // If the transfer implicitly succeeded, the expiration time should be extended
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.minusDays(1);
    DateTime previousExpiration = now.minusDays(2);

    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingRegistrarId("TheRegistrar")
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
  void testClone_extendsExpirationForNonExpiredTransferredDomain() {
    // If the transfer implicitly succeeded, the expiration time should be extended even if it
    // hadn't already expired
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.minusDays(1);
    DateTime previousExpiration = now.plusWeeks(2);

    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingRegistrarId("TheRegistrar")
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
  void testClone_removesBulkTokenFromTransferredDomain() {
    // If the transfer implicitly succeeded, the expiration time should be extended even if it
    // hadn't already expired
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.minusDays(1);
    DateTime previousExpiration = now.plusWeeks(2);

    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingRegistrarId("TheRegistrar")
            .build();
    Period extensionPeriod = transferData.getTransferPeriod();
    DateTime newExpiration = previousExpiration.plusYears(extensionPeriod.getValue());
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(BULK_PRICING)
                .setDiscountFraction(1.0)
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .build());
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setTransferData(transferData)
                .setCurrentBulkToken(allocationToken.createVKey())
                .build());

    assertThat(domain.getCurrentBulkToken()).isPresent();
    Domain clonedDomain = domain.cloneProjectedAtTime(now);
    assertThat(clonedDomain.getRegistrationExpirationTime()).isEqualTo(newExpiration);
    assertThat(clonedDomain.getCurrentBulkToken()).isEmpty();
  }

  @Test
  void testClone_doesNotExtendExpirationForPendingTransfer() {
    // Pending transfers shouldn't affect the expiration time
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.plusDays(1);
    DateTime previousExpiration = now.plusWeeks(2);

    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingRegistrarId("TheRegistrar")
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
  void testClone_doesNotRemoveBulkTokenForPendingTransfer() {
    // Pending transfers shouldn't affect the expiration time
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.plusDays(1);
    DateTime previousExpiration = now.plusWeeks(2);

    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingRegistrarId("TheRegistrar")
            .build();
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(BULK_PRICING)
                .setDiscountFraction(1.0)
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .build());
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setTransferData(transferData)
                .setCurrentBulkToken(allocationToken.createVKey())
                .build());

    Domain clonedDomain = domain.cloneProjectedAtTime(now);
    assertThat(clonedDomain.getRegistrationExpirationTime()).isEqualTo(previousExpiration);
    assertThat(clonedDomain.getCurrentBulkToken().get()).isEqualTo(allocationToken.createVKey());
  }

  @Test
  void testClone_transferDuringAutorenew() {
    // When the domain is an autorenew grace period, we should not extend the registration
    // expiration by a further year--it should just be whatever the autorenew was
    DateTime now = DateTime.now(UTC);
    DateTime transferExpirationTime = now.minusDays(1);
    DateTime previousExpiration = now.minusDays(2);

    DomainTransferData transferData =
        new DomainTransferData.Builder()
            .setPendingTransferExpirationTime(transferExpirationTime)
            .setTransferStatus(TransferStatus.PENDING)
            .setGainingRegistrarId("TheRegistrar")
            .setServerApproveAutorenewEvent(recurrenceBillKey)
            .setServerApproveBillingEvent(oneTimeBillKey)
            .build();
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(previousExpiration)
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.createForRecurrence(
                            GracePeriodStatus.AUTO_RENEW,
                            domain.getRepoId(),
                            now.plusDays(1),
                            "NewRegistrar",
                            recurrenceBillKey)))
                .setTransferData(transferData)
                .setAutorenewBillingEvent(recurrenceBillKey)
                .build());
    Domain clone = domain.cloneProjectedAtTime(now);
    assertThat(clone.getRegistrationExpirationTime())
        .isEqualTo(domain.getRegistrationExpirationTime().plusYears(1));
    // Transferring removes the AUTORENEW grace period and adds a TRANSFER grace period
    assertThat(getOnlyElement(clone.getGracePeriods()).getType())
        .isEqualTo(GracePeriodStatus.TRANSFER);
  }

  @Test
  void testHistoryIdRestoration() {
    // Verify that history ids for billing events are restored during load.
    DateTime now = fakeClock.nowUtc();
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(now.plusYears(1))
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.createForRecurrence(
                            GracePeriodStatus.AUTO_RENEW,
                            domain.getRepoId(),
                            now.plusDays(1),
                            "NewRegistrar",
                            recurrenceBillKey),
                        GracePeriod.create(
                            GracePeriodStatus.RENEW,
                            domain.getRepoId(),
                            now.plusDays(1),
                            "NewRegistrar",
                            oneTimeBillKey)))
                .build());
    ImmutableSet<BillEventInfo> historyIds =
        domain.getGracePeriods().stream()
            .map(gp -> new BillEventInfo(gp.getBillingRecurrence(), gp.getBillingEvent()))
            .collect(toImmutableSet());
    assertThat(historyIds)
        .isEqualTo(
            ImmutableSet.of(
                new BillEventInfo(null, oneTimeBillKey),
                new BillEventInfo(recurrenceBillKey, null)));
  }

  static class BillEventInfo extends ImmutableObject {
    VKey<BillingRecurrence> recurrence;
    Long recurrenceHistoryId;
    VKey<BillingEvent> billingEventOneTime;
    Long billingEventOneTimeHistoryId;

    BillEventInfo(VKey<BillingRecurrence> recurrence, VKey<BillingEvent> billingEventOneTime) {
      this.recurrence = recurrence;
      this.billingEventOneTime = billingEventOneTime;
    }
  }

  @Test
  void testContactFields() {
    VKey<Contact> contact3Key = persistActiveContact("contact_id3").createVKey();
    VKey<Contact> contact4Key = persistActiveContact("contact_id4").createVKey();

    // Set all of the contacts.
    domain.setContactFields(
        ImmutableSet.of(
            DesignatedContact.create(Type.REGISTRANT, contact1Key),
            DesignatedContact.create(Type.ADMIN, contact2Key),
            DesignatedContact.create(Type.BILLING, contact3Key),
            DesignatedContact.create(Type.TECH, contact4Key)),
        true);
    assertThat(domain.getRegistrant()).hasValue(contact1Key);
    assertThat(domain.getAdminContact()).hasValue(contact2Key);
    assertThat(domain.getBillingContact()).hasValue(contact3Key);
    assertThat(domain.getTechContact()).hasValue(contact4Key);

    // Make sure everything gets nulled out.
    domain.setContactFields(ImmutableSet.of(), true);
    assertThat(domain.getRegistrant()).isEmpty();
    assertThat(domain.getAdminContact()).isEmpty();
    assertThat(domain.getBillingContact()).isEmpty();
    assertThat(domain.getTechContact()).isEmpty();

    // Make sure that changes don't affect the registrant unless requested.
    domain.setContactFields(
        ImmutableSet.of(
            DesignatedContact.create(Type.REGISTRANT, contact1Key),
            DesignatedContact.create(Type.ADMIN, contact2Key),
            DesignatedContact.create(Type.BILLING, contact3Key),
            DesignatedContact.create(Type.TECH, contact4Key)),
        false);
    assertThat(domain.getRegistrant()).isEmpty();
    assertThat(domain.getAdminContact()).hasValue(contact2Key);
    assertThat(domain.getBillingContact()).hasValue(contact3Key);
    assertThat(domain.getTechContact()).hasValue(contact4Key);
    domain = domain.asBuilder().setRegistrant(Optional.of(contact1Key)).build();
    domain.setContactFields(ImmutableSet.of(), false);
    assertThat(domain.getRegistrant()).hasValue(contact1Key);
    assertThat(domain.getAdminContact()).isEmpty();
    assertThat(domain.getBillingContact()).isEmpty();
    assertThat(domain.getTechContact()).isEmpty();
  }

  @Test
  void testFail_currentBulkTokenWrongTokenType() {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder().setToken("abc123").setTokenType(SINGLE_USE).build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> domain.asBuilder().setCurrentBulkToken(allocationToken.createVKey()).build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("The currentBulkToken must have a BULK_PRICING TokenType");
  }

  @Test
  void testFailure_bulkTokenDoesNotExist() {
    AllocationToken allocationToken =
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(BULK_PRICING)
            .setDiscountFraction(1.0)
            .setRenewalPriceBehavior(SPECIFIED)
            .setRenewalPrice(Money.of(USD, 0))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .build();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> domain.asBuilder().setCurrentBulkToken(allocationToken.createVKey()).build());
    assertThat(thrown).hasMessageThat().isEqualTo("The bulk token abc123 does not exist");
  }

  @Test
  void testSuccess_removeCurrentBulkToken() {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(BULK_PRICING)
                .setDiscountFraction(1.0)
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .build());
    domain = domain.asBuilder().setCurrentBulkToken(allocationToken.createVKey()).build();
    assertThat(domain.getCurrentBulkToken().get()).isEqualTo(allocationToken.createVKey());
    domain = domain.asBuilder().setCurrentBulkToken(null).build();
    assertThat(domain.getCurrentBulkToken()).isEmpty();
  }
}
