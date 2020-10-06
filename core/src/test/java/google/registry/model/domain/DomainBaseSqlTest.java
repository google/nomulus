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
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.SqlHelper.assertThrowForeignKeyViolation;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.ContactTransferData;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import java.util.Arrays;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Verify that we can store/retrieve DomainBase objects from a SQL database. */
public class DomainBaseSqlTest {

  protected FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  @Order(value = 1)
  DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  @RegisterExtension
  JpaIntegrationWithCoverageExtension jpa =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  private DomainBase domain;
  private DomainHistory historyEntry;
  private VKey<ContactResource> contactKey;
  private VKey<ContactResource> contact2Key;
  private VKey<HostResource> host1VKey;
  private HostResource host;
  private ContactResource contact;
  private ContactResource contact2;

  @BeforeEach
  void setUp() {
    saveRegistrar("registrar1");
    saveRegistrar("registrar2");
    saveRegistrar("registrar3");
    contactKey = VKey.createSql(ContactResource.class, "contact_id1");
    contact2Key = VKey.createSql(ContactResource.class, "contact_id2");

    host1VKey = VKey.createSql(HostResource.class, "host1");

    domain =
        new DomainBase.Builder()
            .setDomainName("example.com")
            .setRepoId("4-COM")
            .setCreationClientId("registrar1")
            .setLastEppUpdateTime(fakeClock.nowUtc())
            .setLastEppUpdateClientId("registrar2")
            .setLastTransferTime(fakeClock.nowUtc())
            .setNameservers(host1VKey)
            .setStatusValues(
                ImmutableSet.of(
                    StatusValue.CLIENT_DELETE_PROHIBITED,
                    StatusValue.SERVER_DELETE_PROHIBITED,
                    StatusValue.SERVER_TRANSFER_PROHIBITED,
                    StatusValue.SERVER_UPDATE_PROHIBITED,
                    StatusValue.SERVER_RENEW_PROHIBITED,
                    StatusValue.SERVER_HOLD))
            .setRegistrant(contactKey)
            .setContacts(ImmutableSet.of(DesignatedContact.create(Type.ADMIN, contact2Key)))
            .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
            .setPersistedCurrentSponsorClientId("registrar3")
            .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
            .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .setLaunchNotice(
                LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
            .setSmdId("smdid")
            .addGracePeriod(
                GracePeriod.create(GracePeriodStatus.ADD, "4-COM", END_OF_TIME, "registrar1", null))
            .build();

    host =
        new HostResource.Builder()
            .setRepoId("host1")
            .setHostName("ns1.example.com")
            .setCreationClientId("registrar1")
            .setPersistedCurrentSponsorClientId("registrar2")
            .build();
    contact = makeContact("contact_id1");
    contact2 = makeContact("contact_id2");
  }

  @Test
  void testDomainBasePersistence() {
    persistDomain();

    jpaTm()
        .transact(
            () -> {
              DomainBase result = jpaTm().load(domain.createVKey());
              assertEqualDomainExcept(result);
            });
  }

  @Test
  void testHostForeignKeyConstraints() {
    assertThrowForeignKeyViolation(
        () ->
            jpaTm()
                .transact(
                    () -> {
                      // Persist the domain without the associated host object.
                      jpaTm().insert(contact);
                      jpaTm().insert(contact2);
                      jpaTm().insert(domain);
                    }));
  }

  @Test
  void testContactForeignKeyConstraints() {
    assertThrowForeignKeyViolation(
        () ->
            jpaTm()
                .transact(
                    () -> {
                      // Persist the domain without the associated contact objects.
                      jpaTm().insert(domain);
                      jpaTm().insert(host);
                    }));
  }

  @Test
  void testResaveDomain_succeeds() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              jpaTm().put(persisted.asBuilder().build());
            });
    jpaTm()
        .transact(
            () -> {
              // Load the domain in its entirety.
              DomainBase result = jpaTm().load(domain.createVKey());
              assertEqualDomainExcept(result);
            });
  }

  @Test
  void testModifyGracePeriod_setEmptyCollectionSuccessfully() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              DomainBase modified =
                  persisted.asBuilder().setGracePeriods(ImmutableSet.of()).build();
              jpaTm().put(modified);
            });

    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              assertThat(persisted.getGracePeriods()).isEmpty();
            });
  }

  @Test
  void testModifyGracePeriod_setNullCollectionSuccessfully() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              DomainBase modified = persisted.asBuilder().setGracePeriods(null).build();
              jpaTm().put(modified);
            });

    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              assertThat(persisted.getGracePeriods()).isEmpty();
            });
  }

  @Test
  void testModifyGracePeriod_addThenRemoveSuccessfully() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              DomainBase modified =
                  persisted
                      .asBuilder()
                      .addGracePeriod(
                          GracePeriod.create(
                              GracePeriodStatus.RENEW, "4-COM", END_OF_TIME, "registrar1", null))
                      .build();
              jpaTm().put(modified);
            });

    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              assertThat(persisted.getGracePeriods().size()).isEqualTo(2);
              persisted
                  .getGracePeriods()
                  .forEach(
                      gracePeriod -> {
                        assertThat(gracePeriod.id).isNotNull();
                        if (gracePeriod.getType() == GracePeriodStatus.ADD) {
                          assertAboutImmutableObjects()
                              .that(gracePeriod)
                              .isEqualExceptFields(
                                  GracePeriod.create(
                                      GracePeriodStatus.ADD,
                                      "4-COM",
                                      END_OF_TIME,
                                      "registrar1",
                                      null),
                                  "id");
                        } else if (gracePeriod.getType() == GracePeriodStatus.RENEW) {
                          assertAboutImmutableObjects()
                              .that(gracePeriod)
                              .isEqualExceptFields(
                                  GracePeriod.create(
                                      GracePeriodStatus.RENEW,
                                      "4-COM",
                                      END_OF_TIME,
                                      "registrar1",
                                      null),
                                  "id");
                        } else {
                          fail("Unexpected GracePeriod: " + gracePeriod);
                        }
                      });
              assertEqualDomainExcept(persisted, "gracePeriods");
            });

    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              DomainBase.Builder builder = persisted.asBuilder();
              for (GracePeriod gracePeriod : persisted.getGracePeriods()) {
                if (gracePeriod.getType() == GracePeriodStatus.RENEW) {
                  builder.removeGracePeriod(gracePeriod);
                }
              }
              jpaTm().put(builder.build());
            });

    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              assertEqualDomainExcept(persisted);
            });
  }

  @Test
  void testModifyGracePeriod_removeThenAddSuccessfully() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              DomainBase modified =
                  persisted.asBuilder().setGracePeriods(ImmutableSet.of()).build();
              jpaTm().put(modified);
            });

    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              assertThat(persisted.getGracePeriods()).isEmpty();
              DomainBase modified =
                  persisted
                      .asBuilder()
                      .addGracePeriod(
                          GracePeriod.create(
                              GracePeriodStatus.ADD, "4-COM", END_OF_TIME, "registrar1", null))
                      .build();
              jpaTm().put(modified);
            });

    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              assertThat(persisted.getGracePeriods().size()).isEqualTo(1);
              assertAboutImmutableObjects()
                  .that(persisted.getGracePeriods().iterator().next())
                  .isEqualExceptFields(
                      GracePeriod.create(
                          GracePeriodStatus.ADD, "4-COM", END_OF_TIME, "registrar1", null),
                      "id");
              assertEqualDomainExcept(persisted, "gracePeriods");
            });
  }

  @Test
  void testUpdates() {
    jpaTm()
        .transact(
            () -> {
              jpaTm().insert(contact);
              jpaTm().insert(contact2);
              jpaTm().insert(domain);
              jpaTm().insert(host);
            });
    domain = domain.asBuilder().setNameservers(ImmutableSet.of()).build();
    jpaTm().transact(() -> jpaTm().put(domain));
    jpaTm()
        .transact(
            () -> {
              DomainBase result = jpaTm().load(domain.createVKey());

              // Fix DS data, since we can't persist that yet.
              result =
                  result
                      .asBuilder()
                      .setDsData(
                          ImmutableSet.of(
                              DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
                      .build();

              assertAboutImmutableObjects()
                  .that(result)
                  .isEqualExceptFields(domain, "updateTimestamp", "creationTime");
            });
  }

  static ContactResource makeContact(String repoId) {
    return new ContactResource.Builder()
        .setRepoId(repoId)
        .setCreationClientId("registrar1")
        .setTransferData(new ContactTransferData.Builder().build())
        .setPersistedCurrentSponsorClientId("registrar1")
        .build();
  }

  private void persistDomain() {
    jpaTm()
        .transact(
            () -> {
              // Persist the contacts.  Note that these need to be persisted before the domain
              // otherwise we get a foreign key constraint error.  If we ever decide to defer the
              // relevant foreign key checks to commit time, then the order would not matter.
              jpaTm().insert(contact);
              jpaTm().insert(contact2);

              // Persist the domain.
              jpaTm().insert(domain);

              // Persist the host.  This does _not_ need to be persisted before the domain,
              // because only the row in the join table (DomainHost) is subject to foreign key
              // constraints, and Hibernate knows to insert it after domain and host.
              jpaTm().insert(host);
            });
  }

  @Test
  void persistDomainWithCompositeVKeys() {
    jpaTm()
        .transact(
            () -> {
              historyEntry =
                  new DomainHistory.Builder()
                      .setType(HistoryEntry.Type.DOMAIN_CREATE)
                      .setPeriod(Period.create(1, Period.Unit.YEARS))
                      .setModificationTime(DateTime.now(UTC))
                      .setParent(Key.create(DomainBase.class, "4-COM"))
                      .setDomainRepoId("4-COM")

                      // These are non-null, but I don't think some tests set them.
                      .setReason("felt like it")
                      .setRequestedByRegistrar(false)
                      .setXmlBytes(new byte[0])
                      .build();
              BillingEvent.Recurring billEvent =
                  new BillingEvent.Recurring.Builder()
                      .setReason(Reason.RENEW)
                      .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                      .setTargetId("example.com")
                      .setClientId("registrar1")
                      .setDomainRepoId("4-COM")
                      .setDomainHistoryRevisionId(1L)
                      .setEventTime(DateTime.now(UTC).plusYears(1))
                      .setRecurrenceEndTime(END_OF_TIME)
                      .setParent(historyEntry)
                      .build();
              PollMessage.Autorenew autorenewPollMessage =
                  new PollMessage.Autorenew.Builder()
                      .setClientId("registrar1")
                      .setEventTime(DateTime.now(UTC).plusYears(1))
                      .setParent(historyEntry)
                      .build();
              PollMessage.OneTime deletePollMessage =
                  new PollMessage.OneTime.Builder()
                      .setClientId("registrar1")
                      .setEventTime(DateTime.now(UTC).plusYears(1))
                      .setParent(historyEntry)
                      .build();

              jpaTm().insert(contact);
              jpaTm().insert(contact2);
              jpaTm().insert(host);
              domain =
                  domain
                      .asBuilder()
                      .setAutorenewBillingEvent(billEvent.createVKey())
                      .setAutorenewPollMessage(autorenewPollMessage.createVKey())
                      .setDeletePollMessage(deletePollMessage.createVKey())
                      .build();
              historyEntry = historyEntry.asBuilder().setDomainContent(domain).build();
              jpaTm().insert(historyEntry);
              jpaTm().insert(autorenewPollMessage);
              jpaTm().insert(billEvent);
              jpaTm().insert(deletePollMessage);
              jpaTm().insert(domain);
            });

    // Store the existing BillingRecurrence VKey.  This happens after the event has been persisted.
    DomainBase persisted = jpaTm().transact(() -> jpaTm().load(domain.createVKey()));

    // Verify that the domain data has been persisted.
    // dsData still isn't persisted.  gracePeriods appears to have the same values but for some
    // reason is showing up as different.
    assertEqualDomainExcept(persisted, "creationTime", "dsData", "gracePeriods");

    // Verify that the DomainContent object from the history record sets the fields correctly.
    DomainHistory persistedHistoryEntry =
        jpaTm().transact(() -> jpaTm().load(historyEntry.createVKey()));
    assertThat(persistedHistoryEntry.getDomainContent().get().getAutorenewPollMessage())
        .isEqualTo(domain.getAutorenewPollMessage());
    assertThat(persistedHistoryEntry.getDomainContent().get().getAutorenewBillingEvent())
        .isEqualTo(domain.getAutorenewBillingEvent());
    assertThat(persistedHistoryEntry.getDomainContent().get().getDeletePollMessage())
        .isEqualTo(domain.getDeletePollMessage());
  }

  @Test
  void persistDomainWithLegacyVKeys() {
    jpaTm()
        .transact(
            () -> {
              historyEntry =
                  new DomainHistory.Builder()
                      .setType(HistoryEntry.Type.DOMAIN_CREATE)
                      .setPeriod(Period.create(1, Period.Unit.YEARS))
                      .setModificationTime(DateTime.now(UTC))
                      .setParent(Key.create(DomainBase.class, "4-COM"))
                      .setDomainRepoId("4-COM")

                      // These are non-null, but I don't think some tests set them.
                      .setReason("felt like it")
                      .setRequestedByRegistrar(false)
                      .setXmlBytes(new byte[0])
                      .build();
              BillingEvent.Recurring billEvent =
                  new BillingEvent.Recurring.Builder()
                      .setReason(Reason.RENEW)
                      .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                      .setTargetId("example.com")
                      .setClientId("registrar1")
                      .setDomainRepoId("4-COM")
                      .setDomainHistoryRevisionId(1L)
                      .setEventTime(DateTime.now(UTC).plusYears(1))
                      .setRecurrenceEndTime(END_OF_TIME)
                      .setParent(historyEntry)
                      .build();
              PollMessage.Autorenew autorenewPollMessage =
                  new PollMessage.Autorenew.Builder()
                      .setClientId("registrar1")
                      .setEventTime(DateTime.now(UTC).plusYears(1))
                      .setParent(historyEntry)
                      .build();
              PollMessage.OneTime deletePollMessage =
                  new PollMessage.OneTime.Builder()
                      .setClientId("registrar1")
                      .setEventTime(DateTime.now(UTC).plusYears(1))
                      .setParent(historyEntry)
                      .build();

              jpaTm().insert(contact);
              jpaTm().insert(contact2);
              jpaTm().insert(host);
              domain =
                  domain
                      .asBuilder()
                      .setAutorenewBillingEvent(
                          createLegacyVKey(BillingEvent.Recurring.class, billEvent.getId()))
                      .setAutorenewPollMessage(
                          createLegacyVKey(PollMessage.Autorenew.class, deletePollMessage.getId()))
                      .setDeletePollMessage(
                          createLegacyVKey(PollMessage.OneTime.class, autorenewPollMessage.getId()))
                      .build();
              historyEntry = historyEntry.asBuilder().setDomainContent(domain).build();
              jpaTm().insert(historyEntry);
              jpaTm().insert(autorenewPollMessage);
              jpaTm().insert(billEvent);
              jpaTm().insert(deletePollMessage);
              jpaTm().insert(domain);
            });

    // Store the existing BillingRecurrence VKey.  This happens after the event has been persisted.
    DomainBase persisted = jpaTm().transact(() -> jpaTm().load(domain.createVKey()));

    // Verify that the domain data has been persisted.
    // dsData still isn't persisted.  gracePeriods appears to have the same values but for some
    // reason is showing up as different.
    assertEqualDomainExcept(persisted, "creationTime", "dsData", "gracePeriods");

    // Verify that the DomainContent object from the history record sets the fields correctly.
    DomainHistory persistedHistoryEntry =
        jpaTm().transact(() -> jpaTm().load(historyEntry.createVKey()));
    assertThat(persistedHistoryEntry.getDomainContent().get().getAutorenewPollMessage())
        .isEqualTo(domain.getAutorenewPollMessage());
    assertThat(persistedHistoryEntry.getDomainContent().get().getAutorenewBillingEvent())
        .isEqualTo(domain.getAutorenewBillingEvent());
    assertThat(persistedHistoryEntry.getDomainContent().get().getDeletePollMessage())
        .isEqualTo(domain.getDeletePollMessage());
  }

  private <T> VKey<T> createLegacyVKey(Class<T> clazz, long id) {
    return VKey.create(
        clazz, id, Key.create(Key.create(EntityGroupRoot.class, "per-tld"), clazz, id));
  }

  private void assertEqualDomainExcept(DomainBase thatDomain, String... excepts) {
    // Fix DS data, since we can't persist it yet.
    thatDomain =
        thatDomain
            .asBuilder()
            .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .build();

    // Fix the original creation timestamp (this gets initialized on first write)
    DomainBase org = domain.asBuilder().setCreationTime(thatDomain.getCreationTime()).build();

    String[] moreExcepts = Arrays.copyOf(excepts, excepts.length + 1);
    moreExcepts[moreExcepts.length - 1] = "updateTimestamp";

    // Note that the equality comparison forces a lazy load of all fields.
    assertAboutImmutableObjects().that(thatDomain).isEqualExceptFields(org, moreExcepts);
  }
}
