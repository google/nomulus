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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.SqlHelper.assertThrowForeignKeyViolation;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableSet;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.launch.LaunchNotice;
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
import javax.persistence.EntityManager;
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

    jpaTm()
        .transact(
            () -> {
              // Load the domain in its entirety.
              EntityManager em = jpaTm().getEntityManager();
              DomainBase result = em.find(DomainBase.class, "4-COM");

              // Fix grace period and DS data, since we can't persist them yet.
              result =
                  result
                      .asBuilder()
                      .setRegistrant(contactKey)
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

  @Test
  void testHostForeignKeyConstraints() {
    assertThrowForeignKeyViolation(
        () -> {
          jpaTm()
              .transact(
                  () -> {
                    // Persist the domain without the associated host object.
                    jpaTm().saveNew(contact);
                    jpaTm().saveNew(contact2);
                    jpaTm().saveNew(domain);
                  });
        });
  }

  @Test
  void testContactForeignKeyConstraints() {
    assertThrowForeignKeyViolation(
        () -> {
          jpaTm()
              .transact(
                  () -> {
                    // Persist the domain without the associated contact objects.
                    jpaTm().saveNew(domain);
                    jpaTm().saveNew(host);
                  });
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
}
