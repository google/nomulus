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

package google.registry.beam.initsql;

import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.Assert.assertThrows;

import com.google.appengine.api.datastore.Entity;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatastoreHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import org.joda.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DomainBaseUtil}. */
public class DomainBaseUtilTest {

  private final FakeClock fakeClock = new FakeClock(Instant.now());

  private DomainBase domain;
  private Entity domainEntity;
  private Key<OneTime> oneTimeBillKey;
  private VKey<BillingEvent.Recurring> recurringBillKey;
  private Key<DomainBase> domainKey;

  @RegisterExtension
  AppEngineExtension appEngineRule =
      AppEngineExtension.builder().withDatastore().withClock(fakeClock).build();

  @RegisterExtension InjectRule injectRule = new InjectRule();

  @BeforeEach
  void beforeEach() {
    injectRule.setStaticField(Ofy.class, "clock", fakeClock);
    createTld("com");
    domainKey = Key.create(null, DomainBase.class, "4-COM");
    VKey<HostResource> hostKey =
        persistResource(
                new HostResource.Builder()
                    .setHostName("ns1.example.com")
                    .setSuperordinateDomain(VKey.from(domainKey))
                    .setRepoId("1-COM")
                    .build())
            .createVKey();
    VKey<ContactResource> contact1Key =
        persistResource(
                new ContactResource.Builder()
                    .setContactId("contact_id1")
                    .setRepoId("2-COM")
                    .build())
            .createVKey();
    VKey<ContactResource> contact2Key =
        persistResource(
                new ContactResource.Builder()
                    .setContactId("contact_id2")
                    .setRepoId("3-COM")
                    .build())
            .createVKey();
    Key<HistoryEntry> historyEntryKey =
        Key.create(persistResource(new HistoryEntry.Builder().setParent(domainKey).build()));
    oneTimeBillKey = Key.create(historyEntryKey, BillingEvent.OneTime.class, 1);
    recurringBillKey = VKey.from(Key.create(historyEntryKey, BillingEvent.Recurring.class, 2));
    VKey<PollMessage.Autorenew> autorenewPollKey =
        VKey.from(Key.create(historyEntryKey, PollMessage.Autorenew.class, 3));
    VKey<PollMessage.OneTime> onetimePollKey =
        VKey.from(Key.create(historyEntryKey, PollMessage.OneTime.class, 1));
    // Set up a new persisted domain entity.
    domain =
        persistResource(
            cloneAndSetAutoTimestamps(
                new DomainBase.Builder()
                    .setDomainName("example.com")
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
                    .setContacts(
                        ImmutableSet.of(
                            DesignatedContact.create(DesignatedContact.Type.ADMIN, contact2Key)))
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
                        new DomainTransferData.Builder()
                            .setGainingClientId("gaining")
                            .setLosingClientId("losing")
                            .setPendingTransferExpirationTime(fakeClock.nowUtc())
                            .setServerApproveEntities(
                                ImmutableSet.of(
                                    VKey.from(oneTimeBillKey), recurringBillKey, autorenewPollKey))
                            .setServerApproveBillingEvent(VKey.from(oneTimeBillKey))
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
    domainEntity = tm().transact(() -> ofy().toEntity(domain));
  }

  @Test
  void removeBillingAndPollAndHosts_allFkeysPresent() {
    DomainBase domainTransformedByOfy =
        domain
            .asBuilder()
            .setAutorenewBillingEvent(null)
            .setAutorenewPollMessage(null)
            .setNameservers(ImmutableSet.of())
            .setDeletePollMessage(null)
            .setTransferData(null)
            .build();
    DomainBase domainTransformedByUtil =
        (DomainBase) ofy().toPojo(DomainBaseUtil.removeBillingAndPollAndHosts(domainEntity));
    // Compensates for the missing INACTIVE status.
    domainTransformedByUtil = domainTransformedByUtil.asBuilder().build();
    assertAboutImmutableObjects()
        .that(domainTransformedByUtil)
        .isEqualExceptFields(domainTransformedByOfy, "revisions");
  }

  @Test
  void removeBillingAndPollAndHosts_noFkeysPresent() {
    DomainBase domainWithoutFKeys =
        domain
            .asBuilder()
            .setAutorenewBillingEvent(null)
            .setAutorenewPollMessage(null)
            .setNameservers(ImmutableSet.of())
            .setDeletePollMessage(null)
            .setTransferData(null)
            .build();
    Entity entityWithoutFkeys = tm().transact(() -> ofy().toEntity(domainWithoutFKeys));
    DomainBase domainTransformedByUtil =
        (DomainBase) ofy().toPojo(DomainBaseUtil.removeBillingAndPollAndHosts(entityWithoutFkeys));
    // Compensates for the missing INACTIVE status.
    domainTransformedByUtil = domainTransformedByUtil.asBuilder().build();
    assertAboutImmutableObjects()
        .that(domainTransformedByUtil)
        .isEqualExceptFields(domainWithoutFKeys, "revisions");
  }

  @Test
  void removeBillingAndPollAndHosts_notDomainBase() {
    Entity contactEntity =
        tm().transact(() -> ofy().toEntity(DatastoreHelper.newContactResource("contact")));

    assertThrows(
        IllegalArgumentException.class,
        () -> DomainBaseUtil.removeBillingAndPollAndHosts(contactEntity));
  }
}
