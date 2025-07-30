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
import static google.registry.testing.DatabaseHelper.assertHostDnsRequests;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.assertNoDnsRequests;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.newHost;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HostSubject.assertAboutHosts;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.flows.exceptions.ResourceToDeleteIsReferencedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotLowerCaseException;
import google.registry.flows.host.HostFlowUtils.HostNameNotNormalizedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotPunyCodedException;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DatabaseHelper;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HostDeleteFlow}. */
class HostDeleteFlowTest extends ResourceFlowTestCase<HostDeleteFlow, Host> {

  @BeforeEach
  void initFlowTest() {
    setEppInput("host_delete.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld"));
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testDryRun() throws Exception {
    persistActiveHost("ns1.example.tld");
    dryRunFlowAssertResponse(loadFile("host_delete_response.xml"));
  }

  @Test
  void testSuccess() throws Exception {
    persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("host_delete_response.xml"));
    assertSqlDeleteSuccess();
  }

  @Test
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("host_delete_no_cltrid.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld"));
    persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("host_delete_response_no_cltrid.xml"));
    assertSqlDeleteSuccess();
  }

  @Test
  void testFailure_neverExisted() {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(ns1.example.tld)");
  }

  @Test
  void testFailure_existedButWasDeleted() {
    persistDeletedHost("ns1.example.tld", clock.nowUtc().minusDays(1));
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(ns1.example.tld)");
  }

  private void doFailingStatusTest(StatusValue statusValue, Class<? extends Exception> exception) {
    persistResource(
        newHost("ns1.example.tld")
            .asBuilder()
            .setStatusValues(ImmutableSet.of(statusValue))
            .build());
    Exception e = assertThrows(exception, this::runFlow);
    assertThat(e).hasMessageThat().contains(statusValue.getXmlName());
  }

  @Test
  void testFailure_existedButWasClientDeleteProhibited() {
    doFailingStatusTest(
        StatusValue.CLIENT_DELETE_PROHIBITED, ResourceStatusProhibitsOperationException.class);
  }

  @Test
  void testFailure_existedButWasServerDeleteProhibited() {
    doFailingStatusTest(
        StatusValue.SERVER_DELETE_PROHIBITED, ResourceStatusProhibitsOperationException.class);
  }

  @Test
  void testFailure_existedButWasPendingDelete() {
    doFailingStatusTest(
        StatusValue.PENDING_DELETE, ResourceStatusProhibitsOperationException.class);
  }

  @Test
  void testFailure_unauthorizedClient() {
    sessionMetadata.setRegistrarId("NewRegistrar");
    persistActiveHost("ns1.example.tld");
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("host_delete_response.xml"));
    assertSqlDeleteSuccess();
  }

  @Test
  void testSuccess_clientDeleteProhibited_superuser() throws Exception {
    persistResource(
        persistActiveHost("ns1.example.tld")
            .asBuilder()
            .addStatusValue(StatusValue.CLIENT_DELETE_PROHIBITED)
            .build());
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("host_delete_response.xml"));
  }

  @Test
  void testSuccess_serverDeleteProhibited_superuser() throws Exception {
    persistResource(
        persistActiveHost("ns1.example.tld")
            .asBuilder()
            .addStatusValue(StatusValue.SERVER_DELETE_PROHIBITED)
            .build());
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("host_delete_response.xml"));
  }

  @Test
  void testFailure_pendingDelete_superuser() throws Exception {
    persistResource(
        persistActiveHost("ns1.example.tld")
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    assertAboutEppExceptions()
        .that(
            assertThrows(
                ResourceStatusProhibitsOperationException.class,
                () -> runFlow(CommitMode.LIVE, UserPrivileges.SUPERUSER)))
        .marshalsToXml();
  }

  @Test
  void testSuccess_authorizedClientReadFromSuperordinate() throws Exception {
    sessionMetadata.setRegistrarId("TheRegistrar");
    createTld("tld");
    Domain domain =
        persistResource(
            DatabaseHelper.newDomain("example.tld")
                .asBuilder()
                .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
                .build());
    persistResource(
        newHost("ns1.example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar") // Shouldn't hurt.
            .setSuperordinateDomain(domain.createVKey())
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("host_delete_response.xml"));
    assertSqlDeleteSuccess(true);
  }

  @Test
  void testFailure_unauthorizedClientReadFromSuperordinate() {
    sessionMetadata.setRegistrarId("TheRegistrar");
    createTld("tld");
    Domain domain =
        persistResource(
            DatabaseHelper.newDomain("example.tld")
                .asBuilder()
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                .build());
    persistResource(
        newHost("ns1.example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar") // Shouldn't help.
            .setSuperordinateDomain(domain.createVKey())
            .build());
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_authorizedClientReadFromTransferredSuperordinate() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    createTld("tld");
    // Setup a transfer that should have been server approved a day ago.
    DateTime now = clock.nowUtc();
    DateTime requestTime = now.minusDays(1).minus(Tld.DEFAULT_AUTOMATIC_TRANSFER_LENGTH);
    DateTime transferExpirationTime = now.minusDays(1);
    Domain domain =
        persistResource(
            DatabaseHelper.newDomain("example.tld")
                .asBuilder()
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar") // Shouldn't hurt.
                .addStatusValue(StatusValue.PENDING_TRANSFER)
                .setTransferData(
                    new DomainTransferData.Builder()
                        .setTransferStatus(TransferStatus.PENDING)
                        .setGainingRegistrarId("NewRegistrar")
                        .setTransferRequestTime(requestTime)
                        .setLosingRegistrarId("TheRegistrar")
                        .setPendingTransferExpirationTime(transferExpirationTime)
                        .build())
                .build());
    persistResource(
        newHost("ns1.example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar") // Shouldn't hurt.
            .setSuperordinateDomain(domain.createVKey())
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("host_delete_response.xml"));
    assertSqlDeleteSuccess(true);
  }

  @Test
  void testFailure_unauthorizedClientReadFromTransferredSuperordinate() {
    sessionMetadata.setRegistrarId("NewRegistrar");
    createTld("tld");
    // Setup a transfer that should have been server approved a day ago.
    DateTime now = clock.nowUtc();
    DateTime requestTime = now.minusDays(1).minus(Tld.DEFAULT_AUTOMATIC_TRANSFER_LENGTH);
    DateTime transferExpirationTime = now.minusDays(1);
    Domain domain =
        persistResource(
            DatabaseHelper.newDomain("example.tld")
                .asBuilder()
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar") // Shouldn't help.
                .addStatusValue(StatusValue.PENDING_TRANSFER)
                .setTransferData(
                    new DomainTransferData.Builder()
                        .setTransferStatus(TransferStatus.PENDING)
                        .setGainingRegistrarId("TheRegistrar")
                        .setTransferRequestTime(requestTime)
                        .setLosingRegistrarId("NewRegistrar")
                        .setPendingTransferExpirationTime(transferExpirationTime)
                        .build())
                .build());
    persistResource(
        newHost("ns1.example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar") // Shouldn't help.
            .setSuperordinateDomain(domain.createVKey())
            .build());
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_failfastWhenLinkedToDomain() {
    createTld("tld");
    persistResource(
        DatabaseHelper.newDomain("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(persistActiveHost("ns1.example.tld").createVKey()))
            .build());
    EppException thrown = assertThrows(ResourceToDeleteIsReferencedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_nonLowerCaseHostname() {
    setEppInput("host_delete.xml", ImmutableMap.of("HOSTNAME", "NS1.EXAMPLE.NET"));
    EppException thrown = assertThrows(HostNameNotLowerCaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_nonPunyCodedHostname() {
    setEppInput("host_delete.xml", ImmutableMap.of("HOSTNAME", "ns1.çauçalito.tld"));
    HostNameNotPunyCodedException thrown =
        assertThrows(HostNameNotPunyCodedException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected ns1.xn--aualito-txac.tld");
  }

  @Test
  void testFailure_nonCanonicalHostname() {
    setEppInput("host_delete.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld."));
    EppException thrown = assertThrows(HostNameNotNormalizedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-host-delete");
  }

  private void assertSqlDeleteSuccess(boolean isSubordinate) throws Exception {
    assertThat(reloadResourceByForeignKey()).isNull();
    Host deletedHost = reloadResourceByForeignKey(clock.nowUtc().minusMillis(1));
    assertAboutHosts()
        .that(deletedHost)
        .isNotActiveAt(clock.nowUtc())
        .and()
        .hasExactlyStatusValues(StatusValue.OK)
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(Type.HOST_DELETE);
    assertNoBillingEvents();
    if (isSubordinate) {
      assertHostDnsRequests(deletedHost.getHostName());
      assertThat(loadByKey(deletedHost.getSuperordinateDomain()).getSubordinateHosts()).isEmpty();
    } else {
      assertNoDnsRequests();
    }
    assertLastHistoryContainsResource(deletedHost);
  }

  private void assertSqlDeleteSuccess() throws Exception {
    assertSqlDeleteSuccess(false);
  }
}
