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
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.testing.DatastoreHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.HostResourceSubject.assertAboutHosts;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.ExceptionRule;
import java.net.InetAddress;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link HostResource}. */
public class HostResourceTest extends EntityTestCase {

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  HostResource hostResource;

  @Before
  public void setUp() throws Exception {
    createTld("com");
    // Set up a new persisted registrar entity.
    persistResource(
        newDomainResource("example.com").asBuilder()
            .setRepoId("1-COM")
            .setTransferData(new TransferData.Builder()
                .setExtendedRegistrationYears(0)
                .setGainingClientId("gaining")
                .setLosingClientId("losing")
                .setPendingTransferExpirationTime(clock.nowUtc())
                .setServerApproveEntities(
                    ImmutableSet.<Key<? extends TransferServerApproveEntity>>of(
                        Key.create(BillingEvent.OneTime.class, 1)))
                .setTransferRequestTime(clock.nowUtc())
                .setTransferStatus(TransferStatus.SERVER_APPROVED)
                .setTransferRequestTrid(Trid.create("client trid"))
                .build())
            .build());
    hostResource =
        persistResource(
            cloneAndSetAutoTimestamps(
                new HostResource.Builder()
                    .setRepoId("DEADBEEF-COM")
                    .setFullyQualifiedHostName("ns1.example.com")
                    .setCreationClientId("a registrar")
                    .setLastEppUpdateTime(clock.nowUtc())
                    .setLastEppUpdateClientId("another registrar")
                    .setLastTransferTime(clock.nowUtc())
                    .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
                    .setStatusValues(ImmutableSet.of(StatusValue.OK))
                    .setSuperordinateDomain(
                        Key.create(
                            loadByForeignKey(DomainResource.class, "example.com", clock.nowUtc())))
                    .build()));
  }

  @Test
  public void testPersistence() throws Exception {
    assertThat(loadByForeignKey(
        HostResource.class, hostResource.getForeignKey(), clock.nowUtc()))
        .isEqualTo(hostResource.cloneProjectedAtTime(clock.nowUtc()));
  }

  @Test
  public void testIndexing() throws Exception {
    // Clone it and save it before running the indexing test so that its transferData fields are
    // populated from the superordinate domain.
    verifyIndexing(
        persistResource(hostResource.cloneProjectedAtTime(clock.nowUtc())),
        "deletionTime",
        "fullyQualifiedHostName",
        "inetAddresses",
        "superordinateDomain",
        "currentSponsorClientId");
  }

  @Test
  public void testEmptyStringsBecomeNull() {
    assertThat(new HostResource.Builder().setCurrentSponsorClientId(null).build()
        .getCurrentSponsorClientId())
            .isNull();
    assertThat(new HostResource.Builder().setCurrentSponsorClientId("").build()
        .getCurrentSponsorClientId())
            .isNull();
    assertThat(new HostResource.Builder().setCurrentSponsorClientId(" ").build()
        .getCurrentSponsorClientId())
            .isNotNull();
  }

  @Test
  public void testCurrentSponsorClientId_comesFromSuperordinateDomain() {
    assertThat(hostResource.getCurrentSponsorClientId()).isNull();
    HostResource projectedHost =
        loadByForeignKey(HostResource.class, hostResource.getForeignKey(), clock.nowUtc());
    assertThat(projectedHost.getCurrentSponsorClientId())
        .isEqualTo(loadByForeignKey(
            DomainResource.class,
            "example.com",
            clock.nowUtc())
            .getCurrentSponsorClientId());
  }

  @Test
  public void testEmptySetsBecomeNull() throws Exception {
    assertThat(new HostResource.Builder().setInetAddresses(null).build().inetAddresses).isNull();
    assertThat(new HostResource.Builder()
        .setInetAddresses(ImmutableSet.<InetAddress>of()).build().inetAddresses)
            .isNull();
    assertThat(
            new HostResource.Builder()
                .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
                .build()
                .inetAddresses)
        .isNotNull();
  }

  @Test
  public void testImplicitStatusValues() {
    // OK is implicit if there's no other statuses.
    assertAboutHosts()
        .that(new HostResource.Builder().build())
        .hasExactlyStatusValues(StatusValue.OK);
    // If there are other status values, OK should be suppressed.
    assertAboutHosts()
        .that(new HostResource.Builder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
            .build())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
    // When OK is suppressed, it should be removed even if it was originally there.
    assertAboutHosts()
        .that(new HostResource.Builder()
            .setStatusValues(ImmutableSet.of(StatusValue.OK, StatusValue.CLIENT_HOLD))
            .build())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
  }

  @Nullable
  private DateTime runCloneProjectedAtTimeTest(
      @Nullable DateTime domainTransferTime,
      @Nullable DateTime hostTransferTime,
      @Nullable DateTime superordinateChangeTime) {
    DomainResource domain = loadByForeignKey(
        DomainResource.class, "example.com", clock.nowUtc());
    persistResource(
        domain.asBuilder().setTransferData(null).setLastTransferTime(domainTransferTime).build());
    hostResource = persistResource(
        hostResource.asBuilder()
            .setLastSuperordinateChange(superordinateChangeTime)
            .setLastTransferTime(hostTransferTime)
            .build());
    return hostResource.cloneProjectedAtTime(clock.nowUtc()).getLastTransferTime();
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeComesOffHostWhenTransferredMoreRecently() {
    assertThat(runCloneProjectedAtTimeTest(
        clock.nowUtc().minusDays(10), clock.nowUtc().minusDays(2), clock.nowUtc().minusDays(1)))
            .isEqualTo(clock.nowUtc().minusDays(2));
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeNullWhenAllTransfersAreNull() {
    assertThat(runCloneProjectedAtTimeTest(null, null, null)).isNull();
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeComesOffHostWhenTimeOnDomainIsNull() {
    assertThat(runCloneProjectedAtTimeTest(null, clock.nowUtc().minusDays(30), null))
        .isEqualTo(clock.nowUtc().minusDays(30));
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeIsNullWhenHostMovedAfterDomainTransferred() {
    assertThat(runCloneProjectedAtTimeTest(
        clock.nowUtc().minusDays(30), null, clock.nowUtc().minusDays(20)))
            .isNull();
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeComesOffDomainWhenTimeOnHostIsNull() {
    assertThat(runCloneProjectedAtTimeTest(clock.nowUtc().minusDays(5), null, null))
        .isEqualTo(clock.nowUtc().minusDays(5));
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeComesOffDomainWhenLastMoveIsntNull() {
    assertThat(runCloneProjectedAtTimeTest(
        clock.nowUtc().minusDays(5), null, clock.nowUtc().minusDays(10)))
            .isEqualTo(clock.nowUtc().minusDays(5));
  }

  @Test
  public void testCloneProjectedAtTime_lastTransferTimeComesOffDomainWhenThatIsMostRecent() {
    assertThat(runCloneProjectedAtTimeTest(
        clock.nowUtc().minusDays(5), clock.nowUtc().minusDays(20), clock.nowUtc().minusDays(10)))
            .isEqualTo(clock.nowUtc().minusDays(5));
  }

  @Test
  public void testExpiredTransfer_subordinateHost() {
    DomainResource domain = loadByForeignKey(
        DomainResource.class, "example.com", clock.nowUtc());
    persistResource(domain.asBuilder()
        .setTransferData(domain.getTransferData().asBuilder()
            .setTransferStatus(TransferStatus.PENDING)
            .setPendingTransferExpirationTime(clock.nowUtc().plusDays(1))
            .setGainingClientId("winner")
            .setExtendedRegistrationYears(2)
            .setServerApproveBillingEvent(Key.create(
                new BillingEvent.OneTime.Builder()
                    .setParent(new HistoryEntry.Builder().setParent(domain).build())
                    .setCost(Money.parse("USD 100"))
                    .setBillingTime(clock.nowUtc().plusYears(2))
                    .setReason(BillingEvent.Reason.TRANSFER)
                    .setClientId("TheRegistrar")
                    .setTargetId("example.com")
                    .setEventTime(clock.nowUtc().plusYears(2))
                    .setPeriodYears(2)
                    .build()))
            .build())
        .build());
    HostResource afterTransfer = hostResource.cloneProjectedAtTime(clock.nowUtc().plusDays(1));
    assertThat(afterTransfer.getCurrentSponsorClientId()).isEqualTo("winner");
    assertThat(afterTransfer.getLastTransferTime()).isEqualTo(clock.nowUtc().plusDays(1));
  }

  @Test
  public void testToHydratedString_notCircular() {
    // If there are circular references, this will overflow the stack.
    hostResource.toHydratedString();
  }

  @Test
  public void testFailure_uppercaseHostName() {
    thrown.expect(
        IllegalArgumentException.class, "Host name must be in puny-coded, lower-case form");
    hostResource.asBuilder().setFullyQualifiedHostName("AAA.BBB.CCC");
  }

  @Test
  public void testFailure_utf8HostName() {
    thrown.expect(
        IllegalArgumentException.class, "Host name must be in puny-coded, lower-case form");
    hostResource.asBuilder().setFullyQualifiedHostName("みんな.みんな.みんな");
  }
}
