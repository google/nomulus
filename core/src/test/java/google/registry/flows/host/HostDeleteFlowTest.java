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
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.newHostResource;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HostResourceSubject.assertAboutHosts;
import static google.registry.testing.TaskQueueHelper.assertNoDnsTasksEnqueued;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.flows.exceptions.ResourceToDeleteIsReferencedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotLowerCaseException;
import google.registry.flows.host.HostFlowUtils.HostNameNotNormalizedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotPunyCodedException;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;

/** Unit tests for {@link HostDeleteFlow}. */
@DualDatabaseTest
class HostDeleteFlowTest extends ResourceFlowTestCase<HostDeleteFlow, HostResource> {

  @BeforeEach
  void initFlowTest() {
    setEppInput("host_delete.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld"));
  }

  @TestOfyAndSql
  void testDryRun() throws Exception {
    persistActiveHost("ns1.example.tld");
    dryRunFlowAssertResponse(loadFile("host_delete_response.xml"));
  }

  @TestOfyAndSql
  void testSuccess() throws Exception {
    persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("host_delete_response.xml"));
    HostResource deletedHost = reloadResourceByForeignKey();
    assertAboutHosts().that(deletedHost).hasStatusValue(StatusValue.PENDING_DELETE);
    assertAsyncDeletionTaskEnqueued(
        deletedHost, "TheRegistrar", Trid.create("ABC-12345", "server-trid"), false);
    assertAboutHosts()
        .that(deletedHost)
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.HOST_PENDING_DELETE);
    assertNoBillingEvents();
    assertNoDnsTasksEnqueued();
  }

  @TestOfyAndSql
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("host_delete_no_cltrid.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld"));
    persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("host_delete_response_no_cltrid.xml"));
    HostResource deletedHost = reloadResourceByForeignKey();
    assertAboutHosts().that(deletedHost).hasStatusValue(StatusValue.PENDING_DELETE);
    assertAsyncDeletionTaskEnqueued(
        deletedHost, "TheRegistrar", Trid.create(null, "server-trid"), false);
    assertAboutHosts()
        .that(deletedHost)
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.HOST_PENDING_DELETE);
    assertNoBillingEvents();
    assertNoDnsTasksEnqueued();
  }

  @TestOfyAndSql
  void testFailure_neverExisted() {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(ns1.example.tld)");
  }

  @TestOfyAndSql
  void testFailure_existedButWasDeleted() {
    persistDeletedHost("ns1.example.tld", clock.nowUtc().minusDays(1));
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(ns1.example.tld)");
  }

  private void doFailingStatusTest(StatusValue statusValue, Class<? extends Exception> exception) {
    persistResource(
        newHostResource("ns1.example.tld")
            .asBuilder()
            .setStatusValues(ImmutableSet.of(statusValue))
            .build());
    Exception e = assertThrows(exception, this::runFlow);
    assertThat(e).hasMessageThat().contains(statusValue.getXmlName());
  }

  @TestOfyAndSql
  void testFailure_existedButWasClientDeleteProhibited() {
    doFailingStatusTest(
        StatusValue.CLIENT_DELETE_PROHIBITED, ResourceStatusProhibitsOperationException.class);
  }

  @TestOfyAndSql
  void testFailure_existedButWasServerDeleteProhibited() {
    doFailingStatusTest(
        StatusValue.SERVER_DELETE_PROHIBITED, ResourceStatusProhibitsOperationException.class);
  }

  @TestOfyAndSql
  void testFailure_existedButWasPendingDelete() {
    doFailingStatusTest(
        StatusValue.PENDING_DELETE, ResourceStatusProhibitsOperationException.class);
  }

  @TestOfyAndSql
  void testFailure_unauthorizedClient() {
    sessionMetadata.setClientId("NewRegistrar");
    persistActiveHost("ns1.example.tld");
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("host_delete_response.xml"));
    HostResource deletedHost = reloadResourceByForeignKey();
    assertAboutHosts().that(deletedHost).hasStatusValue(StatusValue.PENDING_DELETE);
    assertAsyncDeletionTaskEnqueued(
        deletedHost, "NewRegistrar", Trid.create("ABC-12345", "server-trid"), true);
    assertAboutHosts()
        .that(deletedHost)
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.HOST_PENDING_DELETE);
    assertNoBillingEvents();
    assertNoDnsTasksEnqueued();
  }

  @TestOfyAndSql
  void testSuccess_authorizedClientReadFromSuperordinate() throws Exception {
    sessionMetadata.setClientId("TheRegistrar");
    createTld("tld");
    DomainBase domain =
        persistResource(
            newDomainBase("example.tld")
                .asBuilder()
                .setPersistedCurrentSponsorClientId("TheRegistrar")
                .build());
    persistResource(
        newHostResource("ns1.example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorClientId("NewRegistrar") // Shouldn't hurt.
            .setSuperordinateDomain(domain.createVKey())
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("host_delete_response.xml"));
  }

  @TestOfyAndSql
  void testFailure_unauthorizedClientReadFromSuperordinate() {
    sessionMetadata.setClientId("TheRegistrar");
    createTld("tld");
    DomainBase domain =
        persistResource(
            newDomainBase("example.tld")
                .asBuilder()
                .setPersistedCurrentSponsorClientId("NewRegistrar")
                .build());
    persistResource(
        newHostResource("ns1.example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorClientId("TheRegistrar") // Shouldn't help.
            .setSuperordinateDomain(domain.createVKey())
            .build());
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_authorizedClientReadFromTransferredSuperordinate() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    createTld("tld");
    // Setup a transfer that should have been server approved a day ago.
    DateTime now = clock.nowUtc();
    DateTime requestTime = now.minusDays(1).minus(Registry.DEFAULT_AUTOMATIC_TRANSFER_LENGTH);
    DateTime transferExpirationTime = now.minusDays(1);
    DomainBase domain =
        persistResource(
            newDomainBase("example.tld")
                .asBuilder()
                .setPersistedCurrentSponsorClientId("NewRegistrar") // Shouldn't hurt.
                .addStatusValue(StatusValue.PENDING_TRANSFER)
                .setTransferData(
                    new DomainTransferData.Builder()
                        .setTransferStatus(TransferStatus.PENDING)
                        .setGainingClientId("NewRegistrar")
                        .setTransferRequestTime(requestTime)
                        .setLosingClientId("TheRegistrar")
                        .setPendingTransferExpirationTime(transferExpirationTime)
                        .build())
                .build());
    persistResource(
        newHostResource("ns1.example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorClientId("TheRegistrar") // Shouldn't hurt.
            .setSuperordinateDomain(domain.createVKey())
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("host_delete_response.xml"));
  }

  @TestOfyAndSql
  void testFailure_unauthorizedClientReadFromTransferredSuperordinate() {
    sessionMetadata.setClientId("NewRegistrar");
    createTld("tld");
    // Setup a transfer that should have been server approved a day ago.
    DateTime now = clock.nowUtc();
    DateTime requestTime = now.minusDays(1).minus(Registry.DEFAULT_AUTOMATIC_TRANSFER_LENGTH);
    DateTime transferExpirationTime = now.minusDays(1);
    DomainBase domain =
        persistResource(
            newDomainBase("example.tld")
                .asBuilder()
                .setPersistedCurrentSponsorClientId("NewRegistrar") // Shouldn't help.
                .addStatusValue(StatusValue.PENDING_TRANSFER)
                .setTransferData(
                    new DomainTransferData.Builder()
                        .setTransferStatus(TransferStatus.PENDING)
                        .setGainingClientId("TheRegistrar")
                        .setTransferRequestTime(requestTime)
                        .setLosingClientId("NewRegistrar")
                        .setPendingTransferExpirationTime(transferExpirationTime)
                        .build())
                .build());
    persistResource(
        newHostResource("ns1.example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorClientId("NewRegistrar") // Shouldn't help.
            .setSuperordinateDomain(domain.createVKey())
            .build());
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_failfastWhenLinkedToDomain() {
    createTld("tld");
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(persistActiveHost("ns1.example.tld").createVKey()))
            .build());
    EppException thrown = assertThrows(ResourceToDeleteIsReferencedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_nonLowerCaseHostname() {
    setEppInput("host_delete.xml", ImmutableMap.of("HOSTNAME", "NS1.EXAMPLE.NET"));
    EppException thrown = assertThrows(HostNameNotLowerCaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_nonPunyCodedHostname() {
    setEppInput("host_delete.xml", ImmutableMap.of("HOSTNAME", "ns1.çauçalito.tld"));
    HostNameNotPunyCodedException thrown =
        assertThrows(HostNameNotPunyCodedException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected ns1.xn--aualito-txac.tld");
  }

  @TestOfyAndSql
  void testFailure_nonCanonicalHostname() {
    setEppInput("host_delete.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld."));
    EppException thrown = assertThrows(HostNameNotNormalizedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-host-delete");
  }
}
