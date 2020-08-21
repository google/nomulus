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
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
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
                      jpaTm().saveNew(contact);
                      jpaTm().saveNew(contact2);
                      jpaTm().saveNew(domain);
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
                      jpaTm().saveNew(domain);
                      jpaTm().saveNew(host);
                    }));
  }

  @Test
  void testResaveDomain_succeeds() {
    persistDomain();
    jpaTm()
        .transact(
            () -> {
              DomainBase persisted = jpaTm().load(domain.createVKey());
              jpaTm().saveNewOrUpdate(persisted.asBuilder().build());
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
              jpaTm().saveNewOrUpdate(modified);
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
              jpaTm().saveNewOrUpdate(modified);
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
              jpaTm().saveNewOrUpdate(modified);
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
              jpaTm().saveNewOrUpdate(builder.build());
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
              jpaTm().saveNewOrUpdate(modified);
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
              jpaTm().saveNewOrUpdate(modified);
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
              jpaTm().saveNew(contact);
              jpaTm().saveNew(contact2);
              jpaTm().saveNew(domain);
              jpaTm().saveNew(host);
            });
    domain = domain.asBuilder().setNameservers(ImmutableSet.of()).build();
    jpaTm().transact(() -> jpaTm().saveNewOrUpdate(domain));
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
              jpaTm().saveNew(contact);
              jpaTm().saveNew(contact2);

              // Persist the domain.
              jpaTm().saveNew(domain);

              // Persist the host.  This does _not_ need to be persisted before the domain,
              // because only the row in the join table (DomainHost) is subject to foreign key
              // constraints, and Hibernate knows to insert it after domain and host.
              jpaTm().saveNew(host);
            });
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
