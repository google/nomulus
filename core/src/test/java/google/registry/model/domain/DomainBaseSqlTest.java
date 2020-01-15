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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.billing.BillingEvent;
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
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import javax.persistence.EntityManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Verify that we can store/retrieve DomainBase objects from a SQL database. */
@RunWith(JUnit4.class)
public class DomainBaseSqlTest extends EntityTestCase {

  @Rule
  public final JpaIntegrationTestRule jpaRule =
      new JpaTestRules.Builder().buildIntegrationTestRule();

  DomainBase domain;
  Key<ContactResource> contactKey;
  Key<ContactResource> contact2Key;

  @Before
  public void setUp() {
    Key<HistoryEntry> historyEntryKey = Key.create(HistoryEntry.class, "history");
    contactKey = Key.create(ContactResource.class, "contact_id1");
    contact2Key = Key.create(ContactResource.class, "contact_id2");
    Key<HostResource> hostKey = Key.create(HostResource.class, "host1");
    Key<BillingEvent.OneTime> oneTimeBillKey =
        Key.create(historyEntryKey, BillingEvent.OneTime.class, 1);
    Key<BillingEvent.Recurring> recurringBillKey =
        Key.create(historyEntryKey, BillingEvent.Recurring.class, 2);
    Key<PollMessage.Autorenew> autorenewPollKey =
        Key.create(historyEntryKey, PollMessage.Autorenew.class, 3);
    Key<PollMessage.OneTime> onetimePollKey =
        Key.create(historyEntryKey, PollMessage.OneTime.class, 1);

    domain =
        new DomainBase.Builder()
            .setFullyQualifiedDomainName("example.com")
            .setRepoId("4-COM")
            .setCreationClientId("a registrar")
            .setLastEppUpdateTime(clock.nowUtc())
            .setLastEppUpdateClientId("AnotherRegistrar")
            .setLastTransferTime(clock.nowUtc())
            // TODO(mmuller): reinstate this as soon as we can persist Set<StatusValue>
            // .setStatusValues(
            //    ImmutableSet.of(
            //        StatusValue.CLIENT_DELETE_PROHIBITED,
            //        StatusValue.SERVER_DELETE_PROHIBITED,
            //        StatusValue.SERVER_TRANSFER_PROHIBITED,
            //        StatusValue.SERVER_UPDATE_PROHIBITED,
            //        StatusValue.SERVER_RENEW_PROHIBITED,
            //        StatusValue.SERVER_HOLD))
            .setRegistrant(contactKey)
            .setContacts(ImmutableSet.of(DesignatedContact.create(Type.ADMIN, contact2Key)))
            .setNameservers(ImmutableSet.of(hostKey))
            .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
            .setPersistedCurrentSponsorClientId("losing")
            .setRegistrationExpirationTime(clock.nowUtc().plusYears(1))
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
            .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .setLaunchNotice(
                LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
            .setTransferData(
                new TransferData.Builder()
                    .setGainingClientId("gaining")
                    .setLosingClientId("losing")
                    .setPendingTransferExpirationTime(clock.nowUtc())
                    .setServerApproveEntities(
                        ImmutableSet.of(oneTimeBillKey, recurringBillKey, autorenewPollKey))
                    .setServerApproveBillingEvent(oneTimeBillKey)
                    .setServerApproveAutorenewEvent(recurringBillKey)
                    .setServerApproveAutorenewPollMessage(autorenewPollKey)
                    .setTransferRequestTime(clock.nowUtc().plusDays(1))
                    .setTransferStatus(TransferStatus.SERVER_APPROVED)
                    .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                    .setTransferredRegistrationExpirationTime(clock.nowUtc().plusYears(2))
                    .build())
            .setDeletePollMessage(onetimePollKey)
            .setAutorenewBillingEvent(recurringBillKey)
            .setAutorenewPollMessage(autorenewPollKey)
            .setSmdId("smdid")
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.ADD, clock.nowUtc().plusDays(1), "registrar", null))
            .build();
  }

  @Test
  public void testDomainBasePersistence() {
    jpaTm()
        .transact(
            () -> {

              // Persist the domain and all of its contents.
              EntityManager em = jpaTm().getEntityManager();
              em.persist(domain);
              for (DelegationSignerData ds : domain.getDsData()) {
                em.persist(ds);
              }
              for (GracePeriod gp : domain.getGracePeriods()) {
                em.persist(gp);
              }
            });

    jpaTm()
        .transact(
            () -> {
              // Load the domain in its entirety.
              EntityManager em = jpaTm().getEntityManager();
              DomainBase result = em.find(DomainBase.class, "4-COM");

              // Fix status, contacts and DS data, since we can't persist them yet.
              result =
                  result
                      .asBuilder()
                      .setStatusValues(ImmutableSet.of(StatusValue.OK))
                      .setRegistrant(contactKey)
                      .setContacts(
                          ImmutableSet.of(DesignatedContact.create(Type.ADMIN, contact2Key)))
                      .setDsData(
                          ImmutableSet.of(
                              DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
                      .build();

              // Fix the original creation timestamp (this gets initialized on first write)
              DomainBase org = domain.asBuilder().setCreationTime(result.getCreationTime()).build();

              // Note that the equality comparison forces a lazy load of all fields.
              assertThat(result).isEqualTo(org);
            });
  }
}
