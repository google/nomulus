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

package google.registry.model.host;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistNewRegistrars;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.HostResourceSubject.assertAboutHosts;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.ImmutableObjectSubject;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyHostIndex;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;

/** Unit tests for {@link HostResource}. */
@DualDatabaseTest
class HostResourceTest extends EntityTestCase {

  private final DateTime day3 = fakeClock.nowUtc();
  private final DateTime day2 = day3.minusDays(1);
  private final DateTime day1 = day2.minusDays(1);

  private DomainBase domain;
  private HostResource host;

  @BeforeEach
  void setUp() {
    createTld("com");
    persistNewRegistrars("gaining", "losing", "thisRegistrar", "thatRegistrar");
    // Set up a new persisted registrar entity.
    domain =
        persistResource(
            newDomainBase("example.com")
                .asBuilder()
                .setRepoId("1-COM")
                .setTransferData(
                    new DomainTransferData.Builder()
                        .setGainingRegistrarId("gaining")
                        .setLosingRegistrarId("losing")
                        .setPendingTransferExpirationTime(fakeClock.nowUtc())
                        .setTransferRequestTime(fakeClock.nowUtc())
                        .setTransferStatus(TransferStatus.SERVER_APPROVED)
                        .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                        .build())
                .build());
    host =
        persistResource(
            cloneAndSetAutoTimestamps(
                new HostResource.Builder()
                    .setRepoId("DEADBEEF-COM")
                    .setHostName("ns1.example.com")
                    .setCreationRegistrarId("thisRegistrar")
                    .setLastEppUpdateTime(fakeClock.nowUtc())
                    .setLastEppUpdateRegistrarId("thatRegistrar")
                    .setLastTransferTime(fakeClock.nowUtc())
                    .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
                    .setStatusValues(ImmutableSet.of(StatusValue.OK))
                    .setSuperordinateDomain(domain.createVKey())
                    .build()));
  }

  @TestOfyAndSql
  void testHostBaseToHostResource() {
    ImmutableObjectSubject.assertAboutImmutableObjects()
        .that(new HostResource.Builder().copyFrom(host).build())
        .isEqualExceptFields(host, "updateTimestamp", "revisions");
  }

  @TestOfyAndSql
  void testPersistence() {
    HostResource newHost = host.asBuilder().setRepoId("NEWHOST").build();
    tm().transact(() -> tm().insert(newHost));
    assertThat(ImmutableList.of(tm().transact(() -> tm().loadByKey(newHost.createVKey()))))
        .comparingElementsUsing(immutableObjectCorrespondence("revisions"))
        .containsExactly(newHost);
  }

  @TestOfyOnly
  void testLoadingByForeignKey() {
    assertThat(loadByForeignKey(HostResource.class, host.getForeignKey(), fakeClock.nowUtc()))
        .hasValue(host);
  }

  @TestOfyOnly
  void testIndexing() throws Exception {
    // Clone it and save it before running the indexing test so that its transferData fields are
    // populated from the superordinate domain.
    verifyDatastoreIndexing(
        persistResource(host),
        "deletionTime",
        "fullyQualifiedHostName",
        "inetAddresses",
        "superordinateDomain",
        "currentSponsorClientId");
  }

  @TestOfyAndSql
  void testEmptyStringsBecomeNull() {
    assertThat(
            new HostResource.Builder()
                .setPersistedCurrentSponsorRegistrarId(null)
                .build()
                .getPersistedCurrentSponsorRegistrarId())
        .isNull();
    assertThat(
            new HostResource.Builder()
                .setPersistedCurrentSponsorRegistrarId("")
                .build()
                .getPersistedCurrentSponsorRegistrarId())
        .isNull();
    assertThat(
            new HostResource.Builder()
                .setPersistedCurrentSponsorRegistrarId(" ")
                .build()
                .getPersistedCurrentSponsorRegistrarId())
        .isNotNull();
  }

  @TestOfyAndSql
  void testEmptySetsBecomeNull() {
    assertThat(new HostResource.Builder().setInetAddresses(null).build().inetAddresses).isNull();
    assertThat(new HostResource.Builder().setInetAddresses(ImmutableSet.of()).build().inetAddresses)
        .isNull();
    assertThat(
            new HostResource.Builder()
                .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
                .build()
                .inetAddresses)
        .isNotNull();
  }

  @TestOfyAndSql
  void testImplicitStatusValues() {
    // OK is implicit if there's no other statuses.
    assertAboutHosts()
        .that(new HostResource.Builder().build())
        .hasExactlyStatusValues(StatusValue.OK);
    // If there are other status values, OK should be suppressed.
    assertAboutHosts()
        .that(
            new HostResource.Builder()
                .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
    // When OK is suppressed, it should be removed even if it was originally there.
    assertAboutHosts()
        .that(
            new HostResource.Builder()
                .setStatusValues(ImmutableSet.of(StatusValue.OK, StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
  }

  @TestOfyAndSql
  void testToHydratedString_notCircular() {
    // If there are circular references, this will overflow the stack.
    host.toHydratedString();
  }

  @TestOfyAndSql
  void testFailure_uppercaseHostName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> host.asBuilder().setHostName("AAA.BBB.CCC"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Host name must be in puny-coded, lower-case form");
  }

  @TestOfyAndSql
  void testFailure_utf8HostName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> host.asBuilder().setHostName("みんな.みんな.みんな"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Host name must be in puny-coded, lower-case form");
  }

  @TestOfyAndSql
  void testComputeLastTransferTime_hostNeverSwitchedDomains_domainWasNeverTransferred() {
    domain = domain.asBuilder().setLastTransferTime(null).build();
    host = host.asBuilder().setLastTransferTime(null).setLastSuperordinateChange(null).build();
    assertThat(host.computeLastTransferTime(domain)).isNull();
  }

  @TestOfyAndSql
  void testComputeLastTransferTime_hostNeverSwitchedDomains_domainWasTransferred() {
    // Host was created on Day 1.
    // Domain was transferred on Day 2.
    // Host was always subordinate to domain (and was created before the transfer).
    domain = domain.asBuilder().setLastTransferTime(day2).build();
    host =
        host.asBuilder()
            .setCreationTimeForTest(day1)
            .setLastTransferTime(null)
            .setLastSuperordinateChange(null)
            .build();
    assertThat(host.computeLastTransferTime(domain)).isEqualTo(day2);
  }

  @TestOfyAndSql
  void testComputeLastTransferTime_hostCreatedAfterDomainWasTransferred() {
    // Domain was transferred on Day 1.
    // Host was created subordinate to domain on Day 2.
    domain = domain.asBuilder().setLastTransferTime(day1).build();
    host =
        persistResource(
            cloneAndSetAutoTimestamps(
                new HostResource.Builder()
                    .setCreationTime(day2)
                    .setRepoId("DEADBEEF-COM")
                    .setHostName("ns1.example.com")
                    .setCreationRegistrarId("thisRegistrar")
                    .setLastEppUpdateTime(fakeClock.nowUtc())
                    .setLastEppUpdateRegistrarId("thatRegistrar")
                    .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
                    .setStatusValues(ImmutableSet.of(StatusValue.OK))
                    .setSuperordinateDomain(domain.createVKey())
                    .build()));
    assertThat(host.computeLastTransferTime(domain)).isNull();
  }

  @TestOfyAndSql
  void testComputeLastTransferTime_hostWasTransferred_domainWasNeverTransferred() {
    // Host was transferred on Day 1.
    // Host was made subordinate to domain on Day 2.
    // Domain was never transferred.
    domain = domain.asBuilder().setLastTransferTime(null).build();
    host = host.asBuilder().setLastTransferTime(day1).setLastSuperordinateChange(day2).build();
    assertThat(host.computeLastTransferTime(domain)).isEqualTo(day1);
  }

  @TestOfyAndSql
  void testComputeLastTransferTime_domainWasTransferredBeforeHostBecameSubordinate() {
    // Host was transferred on Day 1.
    // Domain was transferred on Day 2.
    // Host was made subordinate to domain on Day 3.
    domain = domain.asBuilder().setLastTransferTime(day2).build();
    host = host.asBuilder().setLastTransferTime(day1).setLastSuperordinateChange(day3).build();
    assertThat(host.computeLastTransferTime(domain)).isEqualTo(day1);
  }

  @TestOfyAndSql
  void testComputeLastTransferTime_domainWasTransferredAfterHostBecameSubordinate() {
    // Host was transferred on Day 1.
    // Host was made subordinate to domain on Day 2.
    // Domain was transferred on Day 3.
    domain = domain.asBuilder().setLastTransferTime(day3).build();
    host = host.asBuilder().setLastTransferTime(day1).setLastSuperordinateChange(day2).build();
    assertThat(host.computeLastTransferTime(domain)).isEqualTo(day3);
  }

  @TestOfyOnly
  void testBeforeDatastoreSaveOnReplay_indexes() {
    ImmutableList<ForeignKeyHostIndex> foreignKeyIndexes =
        ofyTm().loadAllOf(ForeignKeyHostIndex.class);
    ImmutableList<EppResourceIndex> eppResourceIndexes = ofyTm().loadAllOf(EppResourceIndex.class);
    fakeClock.advanceOneMilli();
    ofyTm()
        .transact(
            () -> {
              foreignKeyIndexes.forEach(ofyTm()::delete);
              eppResourceIndexes.forEach(ofyTm()::delete);
            });
    assertThat(ofyTm().loadAllOf(ForeignKeyHostIndex.class)).isEmpty();
    assertThat(ofyTm().loadAllOf(EppResourceIndex.class)).isEmpty();

    ofyTm().transact(() -> host.beforeDatastoreSaveOnReplay());

    assertThat(ofyTm().loadAllOf(ForeignKeyHostIndex.class))
        .containsExactly(ForeignKeyIndex.create(host, host.getDeletionTime()));
    assertThat(ofyTm().loadAllOf(EppResourceIndex.class))
        .containsExactly(EppResourceIndex.create(Key.create(host)));
  }
}
