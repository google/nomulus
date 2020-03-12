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
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import javax.persistence.EntityManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Verify that we can store/retrieve DomainBase objects from a SQL database. */
@RunWith(JUnit4.class)
public class DomainBaseSqlTest extends EntityTestCase {

  DomainBase domain;
  Key<ContactResource> contactKey;
  Key<ContactResource> contact2Key;

  @Before
  public void setUp() {
    contactKey = Key.create(ContactResource.class, "contact_id1");
    contact2Key = Key.create(ContactResource.class, "contact_id2");

    domain =
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
            .setRegistrant(contactKey)
            .setContacts(ImmutableSet.of(DesignatedContact.create(Type.ADMIN, contact2Key)))
            .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
            .setPersistedCurrentSponsorClientId("losing")
            .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
            .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .setLaunchNotice(
                LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
            .setSmdId("smdid")
            .build();
  }

  @Test
  public void testDomainBasePersistence() {
    jpaTm()
        .transact(
            () -> {
              // Persist the domain.
              EntityManager em = jpaTm().getEntityManager();
              em.persist(domain);
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
}
