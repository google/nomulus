// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.common;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.AppEngineExtension.makeRegistrar1;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.registrar.Registrar;
import google.registry.model.tld.Registry;
import google.registry.model.transfer.ContactTransferData;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RegistryJpaIO.Read}. */
public class RegistryJpaReadTest {

  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private final FakeClock fakeClock = new FakeClock(START_TIME);

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore = new DatastoreEntityExtension();

  @RegisterExtension
  final transient JpaIntegrationTestExtension database =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationTestRule();

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private transient ImmutableList<ContactResource> contacts;

  @BeforeEach
  void beforeEach() {
    Registrar ofyRegistrar = AppEngineExtension.makeRegistrar2();
    jpaTm().transact(() -> jpaTm().put(ofyRegistrar));

    ImmutableList.Builder<ContactResource> builder = new ImmutableList.Builder<>();

    for (int i = 0; i < 3; i++) {
      ContactResource contact = DatabaseHelper.newContactResource("contact_" + i);
      builder.add(contact);
    }
    contacts = builder.build();
    jpaTm().transact(() -> jpaTm().putAll(contacts));
  }

  @Test
  void readWithCriteriaQuery() {
    Read<ContactResource, String> read =
        RegistryJpaIO.read(
            () -> CriteriaQueryBuilder.create(ContactResource.class).build(),
            ContactBase::getContactId);
    PCollection<String> repoIds = testPipeline.apply(read);

    PAssert.that(repoIds).containsInAnyOrder("contact_0", "contact_1", "contact_2");
    testPipeline.run();
  }

  @Test
  void readWithStringQuery() {
    setupForJoinQuery();
    Read<Object[], String> read =
        RegistryJpaIO.read(
            "select d, r.emailAddress from Domain d join Registrar r on"
                + " d.currentSponsorClientId = r.clientIdentifier where r.type = :type"
                + " and d.deletionTime > now()",
            ImmutableMap.of("type", Registrar.Type.REAL),
            false,
            (Object[] row) -> {
              DomainBase domainBase = (DomainBase) row[0];
              String emailAddress = (String) row[1];
              return domainBase.getRepoId() + "-" + emailAddress;
            });
    PCollection<String> joinedStrings = testPipeline.apply(read);

    PAssert.that(joinedStrings).containsInAnyOrder("4-COM-me@google.com");
    testPipeline.run();
  }

  @Test
  void readWithStringNativeQuery() {
    setupForJoinQuery();
    Read<Object[], String> read =
        RegistryJpaIO.read(
            "select d.repo_id, r.email_address from \"Domain\" d join \"Registrar\" r on"
                + " d.current_sponsor_registrar_id = r.registrar_id where r.type = :type"
                + " and d.deletion_time > now()",
            ImmutableMap.of("type", "REAL"),
            true,
            (Object[] row) -> {
              String repoId = (String) row[0];
              String emailAddress = (String) row[1];
              return repoId + "-" + emailAddress;
            });
    PCollection<String> joinedStrings = testPipeline.apply(read);

    PAssert.that(joinedStrings).containsInAnyOrder("4-COM-me@google.com");
    testPipeline.run();
  }

  @Test
  void readWithStringTypedQuery() {
    setupForJoinQuery();
    Read<DomainBase, String> read =
        RegistryJpaIO.read(
            "select d from Domain d join Registrar r on"
                + " d.currentSponsorClientId = r.clientIdentifier where r.type = :type"
                + " and d.deletionTime > now()",
            ImmutableMap.of("type", Registrar.Type.REAL),
            DomainBase.class,
            DomainBase::getRepoId);
    PCollection<String> repoIds = testPipeline.apply(read);

    PAssert.that(repoIds).containsInAnyOrder("4-COM");
    testPipeline.run();
  }

  private void setupForJoinQuery() {
    Registry registry = newRegistry("com", "ABCD_APP");
    Registrar registrar =
        makeRegistrar1()
            .asBuilder()
            .setRegistrarId("registrar1")
            .setEmailAddress("me@google.com")
            .build();
    ContactResource contact =
        new ContactResource.Builder()
            .setRepoId("contactid_1")
            .setCreationRegistrarId(registrar.getRegistrarId())
            .setTransferData(new ContactTransferData.Builder().build())
            .setPersistedCurrentSponsorRegistrarId(registrar.getRegistrarId())
            .build();
    DomainBase domain =
        new DomainBase.Builder()
            .setDomainName("example.com")
            .setRepoId("4-COM")
            .setCreationRegistrarId(registrar.getRegistrarId())
            .setLastEppUpdateTime(fakeClock.nowUtc())
            .setLastEppUpdateRegistrarId(registrar.getRegistrarId())
            .setLastTransferTime(fakeClock.nowUtc())
            .setStatusValues(
                ImmutableSet.of(
                    StatusValue.CLIENT_DELETE_PROHIBITED,
                    StatusValue.SERVER_DELETE_PROHIBITED,
                    StatusValue.SERVER_TRANSFER_PROHIBITED,
                    StatusValue.SERVER_UPDATE_PROHIBITED,
                    StatusValue.SERVER_RENEW_PROHIBITED,
                    StatusValue.SERVER_HOLD))
            .setRegistrant(contact.createVKey())
            .setContacts(ImmutableSet.of())
            .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
            .setPersistedCurrentSponsorRegistrarId(registrar.getRegistrarId())
            .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
            .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .setLaunchNotice(
                LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
            .setSmdId("smdid")
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.ADD,
                    "4-COM",
                    END_OF_TIME,
                    registrar.getRegistrarId(),
                    null,
                    100L))
            .build();
    insertInDb(registry, registrar, contact, domain);
  }
}
