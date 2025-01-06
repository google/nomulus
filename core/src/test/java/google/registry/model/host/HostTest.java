// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistNewRegistrars;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.HostSubject.assertAboutHosts;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.model.EntityTestCase;
import google.registry.model.ImmutableObjectSubject;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DatabaseHelper;
import google.registry.util.SerializeUtils;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Host}. */
class HostTest extends EntityTestCase {

  private final DateTime day3 = fakeClock.nowUtc();
  private final DateTime day2 = day3.minusDays(1);
  private final DateTime day1 = day2.minusDays(1);

  private Domain domain;
  private Host host;

  @BeforeEach
  void setUp() {
    createTld("com");
    persistNewRegistrars("gaining", "losing", "thisRegistrar", "thatRegistrar");
    // Set up a new persisted registrar entity.
    domain =
        persistResource(
            DatabaseHelper.newDomain("example.com")
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
                new Host.Builder()
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

  @Test
  void testHostBaseToHost() {
    ImmutableObjectSubject.assertAboutImmutableObjects()
        .that(new Host.Builder().copyFrom(host).build())
        .isEqualExceptFields(host, "updateTimestamp", "revisions");
  }

  @Test
  void testPersistence() {
    Host newHost = host.asBuilder().setRepoId("NEWHOST").build();
    tm().transact(() -> tm().insert(newHost));
    assertThat(ImmutableList.of(tm().transact(() -> tm().loadByKey(newHost.createVKey()))))
        .comparingElementsUsing(immutableObjectCorrespondence("revisions"))
        .containsExactly(newHost);
  }

  @Test
  void testSerializable() {
    Host newHost = host.asBuilder().setRepoId("NEWHOST").build();
    tm().transact(() -> tm().insert(newHost));
    Host persisted = tm().transact(() -> tm().loadByEntity(newHost));
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }

  @Test
  void testEmptyStringsBecomeNull() {
    assertThat(
            new Host.Builder()
                .setPersistedCurrentSponsorRegistrarId(null)
                .build()
                .getPersistedCurrentSponsorRegistrarId())
        .isNull();
    assertThat(
            new Host.Builder()
                .setPersistedCurrentSponsorRegistrarId("")
                .build()
                .getPersistedCurrentSponsorRegistrarId())
        .isNull();
    assertThat(
            new Host.Builder()
                .setPersistedCurrentSponsorRegistrarId(" ")
                .build()
                .getPersistedCurrentSponsorRegistrarId())
        .isNotNull();
  }

  @Test
  void testEmptySetsBecomeNull() {
    assertThat(new Host.Builder().setInetAddresses(null).build().inetAddresses).isNull();
    assertThat(new Host.Builder().setInetAddresses(ImmutableSet.of()).build().inetAddresses)
        .isNull();
    assertThat(
            new Host.Builder()
                .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
                .build()
                .inetAddresses)
        .isNotNull();
  }

  @Test
  void testImplicitStatusValues() {
    // OK is implicit if there's no other statuses.
    assertAboutHosts().that(new Host.Builder().build()).hasExactlyStatusValues(StatusValue.OK);
    // If there are other status values, OK should be suppressed.
    assertAboutHosts()
        .that(new Host.Builder().setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD)).build())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
    // When OK is suppressed, it should be removed even if it was originally there.
    assertAboutHosts()
        .that(
            new Host.Builder()
                .setStatusValues(ImmutableSet.of(StatusValue.OK, StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
  }

  @Test
  void testToHydratedString_notCircular() {
    // If there are circular references, this will overflow the stack.
    host.toHydratedString();
  }

  @Test
  void testFailure_uppercaseHostName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> host.asBuilder().setHostName("AAA.BBB.CCC"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Host name AAA.BBB.CCC not in puny-coded, lower-case form");
  }

  @Test
  void testFailure_utf8HostName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> host.asBuilder().setHostName("みんな.みんな.みんな"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Host name みんな.みんな.みんな not in puny-coded, lower-case form");
  }

  @Test
  void testComputeLastTransferTime_hostNeverSwitchedDomains_domainWasNeverTransferred() {
    domain = domain.asBuilder().setLastTransferTime(null).build();
    host = host.asBuilder().setLastTransferTime(null).setLastSuperordinateChange(null).build();
    assertThat(host.computeLastTransferTime(domain)).isNull();
  }

  @Test
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

  @Test
  void testComputeLastTransferTime_hostCreatedAfterDomainWasTransferred() {
    // Domain was transferred on Day 1.
    // Host was created subordinate to domain on Day 2.
    domain = domain.asBuilder().setLastTransferTime(day1).build();
    host =
        persistResource(
            cloneAndSetAutoTimestamps(
                new Host.Builder()
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

  @Test
  void testComputeLastTransferTime_hostWasTransferred_domainWasNeverTransferred() {
    // Host was transferred on Day 1.
    // Host was made subordinate to domain on Day 2.
    // Domain was never transferred.
    domain = domain.asBuilder().setLastTransferTime(null).build();
    host = host.asBuilder().setLastTransferTime(day1).setLastSuperordinateChange(day2).build();
    assertThat(host.computeLastTransferTime(domain)).isEqualTo(day1);
  }

  @Test
  void testComputeLastTransferTime_domainWasTransferredBeforeHostBecameSubordinate() {
    // Host was transferred on Day 1.
    // Domain was transferred on Day 2.
    // Host was made subordinate to domain on Day 3.
    domain = domain.asBuilder().setLastTransferTime(day2).build();
    host = host.asBuilder().setLastTransferTime(day1).setLastSuperordinateChange(day3).build();
    assertThat(host.computeLastTransferTime(domain)).isEqualTo(day1);
  }

  @Test
  void testComputeLastTransferTime_domainWasTransferredAfterHostBecameSubordinate() {
    // Host was transferred on Day 1.
    // Host was made subordinate to domain on Day 2.
    // Domain was transferred on Day 3.
    domain = domain.asBuilder().setLastTransferTime(day3).build();
    host = host.asBuilder().setLastTransferTime(day1).setLastSuperordinateChange(day2).build();
    assertThat(host.computeLastTransferTime(domain)).isEqualTo(day3);
  }
}
