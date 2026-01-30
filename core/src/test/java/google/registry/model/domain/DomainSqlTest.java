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
import static google.registry.model.domain.token.AllocationToken.TokenStatus.NOT_STARTED;
import static google.registry.model.domain.token.AllocationToken.TokenType.BULK_PRICING;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.SqlHelper.assertThrowForeignKeyViolation;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.contact.Contact;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.transfer.ContactTransferData;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import google.registry.util.SerializeUtils;
import java.util.Arrays;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Verify that we can store/retrieve Domain objects from a SQL database. */
public class DomainSqlTest {

  protected FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  private Domain domain;
  private VKey<Host> host1VKey;
  private Host host;
  private AllocationToken allocationToken;

  @BeforeEach
  void setUp() {
    saveRegistrar("registrar1");
    saveRegistrar("registrar2");
    saveRegistrar("registrar3");
    host1VKey = createKey(Host.class, "host1");

    domain =
        new Domain.Builder()
            .setDomainName("example.com")
            .setRepoId("4-COM")
            .setCreationRegistrarId("registrar1")
            .setLastEppUpdateTime(fakeClock.nowUtc())
            .setLastEppUpdateRegistrarId("registrar2")
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
            .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
            .setPersistedCurrentSponsorRegistrarId("registrar3")
            .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .setLaunchNotice(
                LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
            .setSmdId("smdid")
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.ADD, "4-COM", END_OF_TIME, "registrar1", null, 100L))
            .build();

    host =
        new Host.Builder()
            .setRepoId("host1")
            .setHostName("ns1.example.com")
            .setCreationRegistrarId("registrar1")
            .setPersistedCurrentSponsorRegistrarId("registrar2")
            .build();

    allocationToken =
        new AllocationToken.Builder()
            .setToken("abc123Unlimited")
            .setTokenType(BULK_PRICING)
            .setDiscountFraction(1.0)
            .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
            .setAllowedTlds(ImmutableSet.of("dev", "app"))
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(CurrencyUnit.USD, 0))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, NOT_STARTED)
                    .put(DateTime.now(UTC), TokenStatus.VALID)
                    .put(DateTime.now(UTC).plusWeeks(8), TokenStatus.ENDED)
                    .build())
            .build();
  }

  @Test
  void testDomainPersistence() {
    persistDomain();
    assertEqualDomainExcept(loadByKey(domain.createVKey()));
  }

  @Test
  void testDomainBasePersistenceWithCurrentBulkToken() {
    persistResource(allocationToken);
    domain = domain.asBuilder().setCurrentBulkToken(allocationToken.createVKey()).build();
    persistDomain();
    assertEqualDomainExcept(loadByKey(domain.createVKey()));
  }

  @Test
  void testHostForeignKeyConstraints() {
    // Persist the domain without the associated host object.
    assertThrowForeignKeyViolation(() -> persistResources(domain));
  }

  @Test
  void testContactForeignKeyConstraints() {
    // Persist the domain without the associated contact objects.
    assertThrowForeignKeyViolation(() -> persistResources(domain, host));
  }

  @Test
  void testResaveDomain_succeeds() {
    persistDomain();
    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              tm().put(persisted.asBuilder().build());
            });
    // Load the domain in its entirety.
    assertEqualDomainExcept(loadByKey(domain.createVKey()));
  }

  @Test
  void testModifyGracePeriod_setEmptyCollectionSuccessfully() {
    persistDomain();
    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              Domain modified = persisted.asBuilder().setGracePeriods(ImmutableSet.of()).build();
              tm().put(modified);
            });

    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              assertThat(persisted.getGracePeriods()).isEmpty();
            });
  }

  @Test
  void testModifyGracePeriod_setNullCollectionSuccessfully() {
    persistDomain();
    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              Domain modified = persisted.asBuilder().setGracePeriods(null).build();
              tm().put(modified);
            });

    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              assertThat(persisted.getGracePeriods()).isEmpty();
            });
  }

  @Test
  void testModifyGracePeriod_addThenRemoveSuccessfully() {
    persistDomain();
    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              Domain modified =
                  persisted
                      .asBuilder()
                      .addGracePeriod(
                          GracePeriod.create(
                              GracePeriodStatus.RENEW,
                              "4-COM",
                              END_OF_TIME,
                              "registrar1",
                              null,
                              200L))
                      .build();
              tm().put(modified);
            });

    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              assertThat(persisted.getGracePeriods())
                  .containsExactly(
                      GracePeriod.create(
                          GracePeriodStatus.ADD, "4-COM", END_OF_TIME, "registrar1", null, 100L),
                      GracePeriod.create(
                          GracePeriodStatus.RENEW, "4-COM", END_OF_TIME, "registrar1", null, 200L));
              assertEqualDomainExcept(persisted, "gracePeriods");
            });

    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              Domain.Builder builder = persisted.asBuilder();
              for (GracePeriod gracePeriod : persisted.getGracePeriods()) {
                if (gracePeriod.getType() == GracePeriodStatus.RENEW) {
                  builder.removeGracePeriod(gracePeriod);
                }
              }
              tm().put(builder.build());
            });

    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              assertEqualDomainExcept(persisted);
            });
  }

  @Test
  void testModifyGracePeriod_removeThenAddSuccessfully() {
    persistDomain();
    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              Domain modified = persisted.asBuilder().setGracePeriods(ImmutableSet.of()).build();
              tm().put(modified);
            });

    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              assertThat(persisted.getGracePeriods()).isEmpty();
              Domain modified =
                  persisted
                      .asBuilder()
                      .addGracePeriod(
                          GracePeriod.create(
                              GracePeriodStatus.ADD,
                              "4-COM",
                              END_OF_TIME,
                              "registrar1",
                              null,
                              100L))
                      .build();
              tm().put(modified);
            });

    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              assertThat(persisted.getGracePeriods())
                  .containsExactly(
                      GracePeriod.create(
                          GracePeriodStatus.ADD, "4-COM", END_OF_TIME, "registrar1", null, 100L));
              assertEqualDomainExcept(persisted, "gracePeriods");
            });
  }

  @Test
  void testModifyDsData_addThenRemoveSuccessfully() {
    persistDomain();
    DomainDsData extraDsData = DomainDsData.create(2, 2, 3, new byte[] {0, 1, 2}, "4-COM");
    ImmutableSet<DomainDsData> unionDsData =
        Sets.union(domain.getDsData(), ImmutableSet.of(extraDsData)).immutableCopy();

    // Add an extra DomainDsData to dsData set.
    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              assertThat(persisted.getDsData()).containsExactlyElementsIn(domain.getDsData());
              Domain modified = persisted.asBuilder().setDsData(unionDsData).build();
              tm().put(modified);
            });

    // Verify that the persisted domain entity contains both DomainDsData records.
    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              assertThat(persisted.getDsData()).containsExactlyElementsIn(unionDsData);
              assertEqualDomainExcept(persisted, "dsData");
            });

    // Remove the extra DomainDsData record from dsData set.
    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              tm().put(persisted.asBuilder().setDsData(domain.getDsData()).build());
            });

    // Verify that the persisted domain is equal to the original domain.
    tm().transact(
            () -> {
              Domain persisted = tm().loadByKey(domain.createVKey());
              assertEqualDomainExcept(persisted);
            });
  }

  @Test
  void testSerializable() {
    createTld("com");
    persistResources(domain, host);
    Domain persisted = tm().transact(() -> tm().loadByEntity(domain));
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }

  @Test
  void testUpdates() {
    createTld("com");
    persistResources(domain, host);
    domain = domain.asBuilder().setNameservers(ImmutableSet.of()).build();
    persistResource(domain);
    assertAboutImmutableObjects()
        .that(loadByEntity(domain))
        .isEqualExceptFields(domain, "updateTimestamp", "creationTime");
  }

  static Contact makeContact(String repoId) {
    return new Contact.Builder()
        .setRepoId(repoId)
        .setCreationRegistrarId("registrar1")
        .setTransferData(new ContactTransferData.Builder().build())
        .setPersistedCurrentSponsorRegistrarId("registrar1")
        .build();
  }

  private void persistDomain() {
    createTld("com");
    persistResources(domain, host);
  }

  private <T> VKey<T> createKey(Class<T> clazz, String key) {
    return VKey.create(clazz, key);
  }

  private void assertEqualDomainExcept(Domain thatDomain, String... excepts) {
    ImmutableList<String> moreExcepts =
        new ImmutableList.Builder<String>()
            .addAll(Arrays.asList(excepts))
            .add("creationTime")
            .add("updateTimestamp")
            .add("transferData")
            .build();
    // Note that the equality comparison forces a lazy load of all fields.
    assertAboutImmutableObjects().that(thatDomain).isEqualExceptFields(domain, moreExcepts);
    // Transfer data cannot be directly compared due to serverApproveEntities inequalities
    assertAboutImmutableObjects()
        .that(domain.getTransferData())
        .isEqualExceptFields(thatDomain.getTransferData(), "serverApproveEntities");
  }

  @Test
  void testUpdateTimeAfterNameserverUpdate() {
    persistDomain();
    Domain persisted = loadByKey(domain.createVKey());
    DateTime originalUpdateTime = persisted.getUpdateTimestamp().getTimestamp();
    fakeClock.advanceOneMilli();
    Host host2 =
        new Host.Builder()
            .setRepoId("host2")
            .setHostName("ns2.example.com")
            .setCreationRegistrarId("registrar1")
            .setPersistedCurrentSponsorRegistrarId("registrar2")
            .build();
    persistResource(host2);
                  domain = persisted.asBuilder().addNameserver(host2.createVKey()).build();
    persistResource(domain);
    domain = loadByKey(domain.createVKey());
    assertThat(domain.getUpdateTimestamp().getTimestamp())
        .isEqualTo(originalUpdateTime.plusMillis(1));
  }

  @Test
  void testUpdateTimeAfterDsDataUpdate() {
    persistDomain();
    Domain persisted = loadByKey(domain.createVKey());
    DateTime originalUpdateTime = persisted.getUpdateTimestamp().getTimestamp();
    fakeClock.advanceOneMilli();
    domain =
        persisted
            .asBuilder()
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .build();
    persistResource(domain);
    domain = loadByKey(domain.createVKey());
    assertThat(domain.getUpdateTimestamp().getTimestamp())
        .isEqualTo(originalUpdateTime.plusMillis(1));
  }
}
