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

package google.registry.flows.host;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.deleteResource;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.host.HostFlowUtils.HostNameNotLowerCaseException;
import google.registry.flows.host.HostFlowUtils.HostNameNotNormalizedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotPunyCodedException;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.TestOfyAndSql;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link HostInfoFlow}. */
@DualDatabaseTest
class HostInfoFlowTest extends ResourceFlowTestCase<HostInfoFlow, HostResource> {

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithDoubleReplay(clock);

  HostInfoFlowTest() {
    setEppInput("host_info.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld"));
  }

  @BeforeEach
  void initHostTest() {
    createTld("foobar");
  }

  private HostResource persistHostResource() throws Exception {
    return persistResource(
        new HostResource.Builder()
            .setHostName(getUniqueIdFromCommand())
            .setRepoId("1FF-FOOBAR")
            .setPersistedCurrentSponsorRegistrarId("my sponsor")
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .setInetAddresses(
                ImmutableSet.of(
                    InetAddresses.forString("192.0.2.2"),
                    InetAddresses.forString("1080:0:0:0:8:800:200C:417A"),
                    InetAddresses.forString("192.0.2.29")))
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .setCreationRegistrarId("NewRegistrar")
            .setLastEppUpdateRegistrarId("NewRegistrar")
            .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
            .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
            .setLastTransferTime(DateTime.parse("2000-04-08T09:00:00.0Z"))
            .build());
  }

  @TestOfyAndSql
  void testSuccess() throws Exception {
    persistHostResource();
    assertTransactionalFlow(false);
    // Check that the persisted host info was returned.
    runFlowAssertResponse(
        loadFile("host_info_response.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @TestOfyAndSql
  void testSuccess_linked() throws Exception {
    persistHostResource();
    persistResource(
        newDomainBase("example.foobar")
            .asBuilder()
            .addNameserver(persistHostResource().createVKey())
            .build());
    assertTransactionalFlow(false);
    // Check that the persisted host info was returned.
    runFlowAssertResponse(
        loadFile("host_info_response_linked.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  private void runTest_superordinateDomain(
      DateTime domainTransferTime, @Nullable DateTime lastSuperordinateChange) throws Exception {
    persistNewRegistrar("superclientid");
    DomainBase domain =
        persistResource(
            newDomainBase("parent.foobar")
                .asBuilder()
                .setRepoId("BEEF-FOOBAR")
                .setLastTransferTime(domainTransferTime)
                .setPersistedCurrentSponsorRegistrarId("superclientid")
                .build());
    HostResource firstHost = persistHostResource();
    persistResource(
        firstHost
            .asBuilder()
            .setRepoId("CEEF-FOOBAR")
            .setSuperordinateDomain(domain.createVKey())
            .setLastSuperordinateChange(lastSuperordinateChange)
            .build());
    // we shouldn't have two active hosts with the same hostname
    deleteResource(firstHost);
    assertTransactionalFlow(false);
    runFlowAssertResponse(
        loadFile("host_info_response_superordinate_clientid.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @TestOfyAndSql
  void testSuccess_withSuperordinateDomain_hostMovedAfterDomainTransfer() throws Exception {
    runTest_superordinateDomain(
        DateTime.parse("2000-01-08T09:00:00.0Z"), DateTime.parse("2000-03-01T01:00:00.0Z"));
  }

  @TestOfyAndSql
  void testSuccess_withSuperordinateDomain_hostMovedBeforeDomainTransfer() throws Exception {
    runTest_superordinateDomain(
        DateTime.parse("2000-04-08T09:00:00.0Z"), DateTime.parse("2000-02-08T09:00:00.0Z"));
  }

  @TestOfyAndSql
  void testSuccess_withSuperordinateDomain() throws Exception {
    runTest_superordinateDomain(DateTime.parse("2000-04-08T09:00:00.0Z"), null);
  }

  @TestOfyAndSql
  void testFailure_neverExisted() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_existedButWasDeleted() throws Exception {
    persistResource(
        persistHostResource().asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_nonLowerCaseHostname() {
    setEppInput("host_info.xml", ImmutableMap.of("HOSTNAME", "NS1.EXAMPLE.NET"));
    EppException thrown = assertThrows(HostNameNotLowerCaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_nonPunyCodedHostname() {
    setEppInput("host_info.xml", ImmutableMap.of("HOSTNAME", "ns1.çauçalito.tld"));
    HostNameNotPunyCodedException thrown =
        assertThrows(HostNameNotPunyCodedException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected ns1.xn--aualito-txac.tld");
  }

  @TestOfyAndSql
  void testFailure_nonCanonicalHostname() {
    setEppInput("host_info.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld."));
    EppException thrown = assertThrows(HostNameNotNormalizedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistHostResource();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-host-info");
  }
}
