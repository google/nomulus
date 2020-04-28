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
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
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

  DomainBase domain;
  Key<ContactResource> contactKey;
  Key<ContactResource> contact2Key;
  VKey<HostResource> host1VKey;
  HostResource host;

  @BeforeEach
  public void setUp() {
    contactKey = Key.create(ContactResource.class, "contact_id1");
    contact2Key = Key.create(ContactResource.class, "contact_id2");

    host1VKey = VKey.createSql(HostResource.class, "host1");

    domain =
        new DomainBase.Builder()
            .setFullyQualifiedDomainName("example.com")
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
            .setFullyQualifiedHostName("ns1.example.com")
            .setCreationClientId("registrar1")
            .setPersistedCurrentSponsorClientId("registrar2")
            .build();
  }

  @Test
  public void testDomainBasePersistence() {
    saveRegistrar("registrar1");
    saveRegistrar("registrar2");
    saveRegistrar("registrar3");

    jpaTm()
        .transact(
            () -> {
              // Persist the domain.
              EntityManager em = jpaTm().getEntityManager();
              em.persist(domain);

              // Persist the host.
              em.persist(host);
            });

    jpaTm()
        .transact(
            () -> {
              // Load the domain in its entirety.
              EntityManager em = jpaTm().getEntityManager();
              DomainBase result = em.find(DomainBase.class, "4-COM");

              // Fix contacts, grace period and DS data, since we can't persist them yet.
              result =
                  result
                      .asBuilder()
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

  @Test
  public void testForeignKeyConstraints() {
    assertThrowForeignKeyViolation(
        () -> {
          jpaTm()
              .transact(
                  () -> {
                    // Persist the domain without the associated host object.
                    EntityManager em = jpaTm().getEntityManager();
                    em.persist(domain);
                  });
        });
  }
}
