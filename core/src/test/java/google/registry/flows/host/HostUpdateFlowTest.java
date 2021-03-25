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

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_HOST_RENAME;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.newHostResource;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistActiveSubordinateHost;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainBaseSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.GenericEppResourceSubject.assertAboutEppResources;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.testing.HostResourceSubject.assertAboutHosts;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.EppRequestSource;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.AddRemoveSameValueException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.ResourceFlowUtils.StatusNotClientSettableException;
import google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.flows.host.HostFlowUtils.HostDomainNotOwnedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotLowerCaseException;
import google.registry.flows.host.HostFlowUtils.HostNameNotNormalizedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotPunyCodedException;
import google.registry.flows.host.HostFlowUtils.HostNameTooLongException;
import google.registry.flows.host.HostFlowUtils.HostNameTooShallowException;
import google.registry.flows.host.HostFlowUtils.InvalidHostNameException;
import google.registry.flows.host.HostFlowUtils.SuperordinateDomainDoesNotExistException;
import google.registry.flows.host.HostFlowUtils.SuperordinateDomainInPendingDeleteException;
import google.registry.flows.host.HostUpdateFlow.CannotAddIpToExternalHostException;
import google.registry.flows.host.HostUpdateFlow.CannotRemoveSubordinateHostLastIpException;
import google.registry.flows.host.HostUpdateFlow.CannotRenameExternalHostException;
import google.registry.flows.host.HostUpdateFlow.HostAlreadyExistsException;
import google.registry.flows.host.HostUpdateFlow.RenameHostToExternalRemoveIpException;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.TestOfyAndSql;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link HostUpdateFlow}. */
@DualDatabaseTest
class HostUpdateFlowTest extends ResourceFlowTestCase<HostUpdateFlow, HostResource> {

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithCompare(clock);

  private void setEppHostUpdateInput(
      String oldHostName, String newHostName, String ipOrStatusToAdd, String ipOrStatusToRem) {
    setEppInput(
        "host_update.xml",
        ImmutableMap.of(
            "OLD-HOSTNAME",
            oldHostName,
            "NEW-HOSTNAME",
            newHostName,
            "ADD-HOSTADDRSORSTATUS",
            nullToEmpty(ipOrStatusToAdd),
            "REM-HOSTADDRSORSTATUS",
            nullToEmpty(ipOrStatusToRem)));
  }

  HostUpdateFlowTest() {
    setEppHostUpdateInput("ns1.example.tld", "ns2.example.tld", null, null);
  }

  /**
   * Setup a domain with a transfer that should have been server approved a day ago.
   *
   * <p>The transfer is from "TheRegistrar" to "NewRegistrar".
   */
  private DomainBase createDomainWithServerApprovedTransfer(String domainName) {
    DateTime now = clock.nowUtc();
    DateTime requestTime = now.minusDays(1).minus(Registry.DEFAULT_AUTOMATIC_TRANSFER_LENGTH);
    DateTime transferExpirationTime = now.minusDays(1);
    return newDomainBase(domainName)
        .asBuilder()
        .setPersistedCurrentSponsorClientId("TheRegistrar")
        .addStatusValue(StatusValue.PENDING_TRANSFER)
        .setTransferData(
            new DomainTransferData.Builder()
                .setTransferStatus(TransferStatus.PENDING)
                .setGainingClientId("NewRegistrar")
                .setTransferRequestTime(requestTime)
                .setLosingClientId("TheRegistrar")
                .setPendingTransferExpirationTime(transferExpirationTime)
                .build())
        .build();
  }

  /** Alias for better readability. */
  private String oldHostName() throws Exception {
    return getUniqueIdFromCommand();
  }

  @TestOfyAndSql
  void testDryRun() throws Exception {
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    dryRunFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  private HostResource doSuccessfulTest() throws Exception {
    return doSuccessfulTest(false); // default to normal user privileges
  }

  private HostResource doSuccessfulTestAsSuperuser() throws Exception {
    return doSuccessfulTest(true);
  }

  private HostResource doSuccessfulTest(boolean isSuperuser) throws Exception {
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    runFlowAssertResponse(
        CommitMode.LIVE,
        isSuperuser ? UserPrivileges.SUPERUSER : UserPrivileges.NORMAL,
        loadFile("generic_success_response.xml"));
    // The example xml does a host rename, so reloading the host (which uses the original host name)
    // should now return null.
    assertThat(reloadResourceByForeignKey()).isNull();
    // However, it should load correctly if we use the new name (taken from the xml).
    HostResource renamedHost =
        loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc()).get();
    assertAboutHosts()
        .that(renamedHost)
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.HOST_UPDATE);
    assertNoBillingEvents();
    return renamedHost;
  }

  @TestOfyAndSql
  void testSuccess_rename_noOtherHostEverUsedTheOldName() throws Exception {
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.isSubordinate()).isTrue();
    assertDnsTasksEnqueued("ns1.example.tld", "ns2.example.tld");
    // As we don't actually store ForeignKeyIndex<HostResource> entity in SQL database(it is
    // reconstructed by reading the information from Host table), we can not reconstruct the entity
    // for the old host name because of the rename so we just skip the check for SQL database.
    if (tm().isOfy()) {
      // The old ForeignKeyIndex is invalidated at the time we did the rename.
      ForeignKeyIndex<HostResource> oldFkiBeforeRename =
          ForeignKeyIndex.load(HostResource.class, oldHostName(), clock.nowUtc().minusMillis(1));
      assertThat(oldFkiBeforeRename.getResourceKey()).isEqualTo(renamedHost.createVKey());
      assertThat(oldFkiBeforeRename.getDeletionTime()).isEqualTo(clock.nowUtc());
    }
    ForeignKeyIndex<HostResource> oldFkiAfterRename =
        ForeignKeyIndex.load(HostResource.class, oldHostName(), clock.nowUtc());
    assertThat(oldFkiAfterRename).isNull();
  }

  @TestOfyAndSql
  void testSuccess_withReferencingDomain() throws Exception {
    createTld("tld");
    createTld("xn--q9jyb4c");
    HostResource host =
        persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    persistResource(
        newDomainBase("test.xn--q9jyb4c")
            .asBuilder()
            .setDeletionTime(END_OF_TIME)
            .setNameservers(ImmutableSet.of(host.createVKey()))
            .build());
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.isSubordinate()).isTrue();
    // Task enqueued to change the NS record of the referencing domain via mapreduce.
    assertTasksEnqueued(
        QUEUE_ASYNC_HOST_RENAME,
        new TaskMatcher()
            .param("hostKey", Key.create(renamedHost).getString())
            .param("requestedTime", clock.nowUtc().toString()));
  }

  @TestOfyAndSql
  void testSuccess_nameUnchanged_superordinateDomainNeverTransferred() throws Exception {
    setEppInput("host_update_name_unchanged.xml");
    createTld("tld");
    DomainBase domain = persistActiveDomain("example.tld");
    HostResource oldHost = persistActiveSubordinateHost(oldHostName(), domain);
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    // The example xml doesn't do a host rename, so reloading the host should work.
    assertAboutHosts()
        .that(reloadResourceByForeignKey())
        .hasLastSuperordinateChange(oldHost.getLastSuperordinateChange())
        .and()
        .hasSuperordinateDomain(domain.createVKey())
        .and()
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(null)
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.HOST_UPDATE);
    assertDnsTasksEnqueued("ns1.example.tld");
  }

  @TestOfyAndSql
  void testSuccess_nameUnchanged_superordinateDomainWasTransferred() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    setEppInput("host_update_name_unchanged.xml");
    createTld("tld");
    // Create a domain that will belong to NewRegistrar after cloneProjectedAtTime is called.
    DomainBase domain = persistResource(createDomainWithServerApprovedTransfer("example.tld"));
    HostResource oldHost = persistActiveSubordinateHost(oldHostName(), domain);
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    // The example xml doesn't do a host rename, so reloading the host should work.
    assertAboutHosts()
        .that(reloadResourceByForeignKey())
        .hasLastSuperordinateChange(oldHost.getLastSuperordinateChange())
        .and()
        .hasSuperordinateDomain(domain.createVKey())
        .and()
        .hasPersistedCurrentSponsorClientId("NewRegistrar")
        .and()
        .hasLastTransferTime(domain.getTransferData().getPendingTransferExpirationTime())
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.HOST_UPDATE);
    assertDnsTasksEnqueued("ns1.example.tld");
  }

  @TestOfyAndSql
  void testSuccess_internalToInternalOnSameDomain() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DateTime now = clock.nowUtc();
    DateTime oneDayAgo = now.minusDays(1);
    DomainBase domain =
        persistResource(
            newDomainBase("example.tld")
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of(oldHostName()))
                .setLastTransferTime(oneDayAgo)
                .build());
    HostResource oldHost = persistActiveSubordinateHost(oldHostName(), domain);
    assertThat(domain.getSubordinateHosts()).containsExactly("ns1.example.tld");
    HostResource renamedHost = doSuccessfulTest();
    assertAboutHosts()
        .that(renamedHost)
        .hasSuperordinateDomain(domain.createVKey())
        .and()
        .hasLastSuperordinateChange(oldHost.getLastSuperordinateChange())
        .and()
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(oneDayAgo);
    DomainBase reloadedDomain = loadByEntity(domain).cloneProjectedAtTime(now);
    assertThat(reloadedDomain.getSubordinateHosts()).containsExactly("ns2.example.tld");
    assertDnsTasksEnqueued("ns1.example.tld", "ns2.example.tld");
  }

  @TestOfyAndSql
  void testSuccess_internalToInternalOnSameTld() throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainBase example = persistActiveDomain("example.tld");
    DomainBase foo =
        persistResource(
            newDomainBase("foo.tld")
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of(oldHostName()))
                .build());
    persistActiveSubordinateHost(oldHostName(), foo);
    assertThat(foo.getSubordinateHosts()).containsExactly("ns2.foo.tld");
    assertThat(example.getSubordinateHosts()).isEmpty();
    HostResource renamedHost = doSuccessfulTest();
    DateTime now = clock.nowUtc();
    assertAboutHosts()
        .that(renamedHost)
        .hasSuperordinateDomain(example.createVKey())
        .and()
        .hasLastSuperordinateChange(now)
        .and()
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(null);
    assertThat(loadByEntity(foo).cloneProjectedAtTime(now).getSubordinateHosts()).isEmpty();
    assertThat(loadByEntity(example).cloneProjectedAtTime(now).getSubordinateHosts())
        .containsExactly("ns2.example.tld");
    assertDnsTasksEnqueued("ns2.foo.tld", "ns2.example.tld");
  }

  @TestOfyAndSql
  void testSuccess_internalToInternalOnDifferentTld() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("foo");
    createTld("tld");
    DomainBase fooDomain =
        persistResource(
            newDomainBase("example.foo")
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of(oldHostName()))
                .build());
    DomainBase tldDomain = persistActiveDomain("example.tld");
    persistActiveSubordinateHost(oldHostName(), fooDomain);
    assertThat(fooDomain.getSubordinateHosts()).containsExactly("ns1.example.foo");
    assertThat(tldDomain.getSubordinateHosts()).isEmpty();
    HostResource renamedHost = doSuccessfulTest();
    DateTime now = clock.nowUtc();
    assertAboutHosts()
        .that(renamedHost)
        .hasSuperordinateDomain(tldDomain.createVKey())
        .and()
        .hasLastSuperordinateChange(now)
        .and()
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(null);
    DomainBase reloadedFooDomain = loadByEntity(fooDomain).cloneProjectedAtTime(now);
    assertThat(reloadedFooDomain.getSubordinateHosts()).isEmpty();
    DomainBase reloadedTldDomain = loadByEntity(tldDomain).cloneProjectedAtTime(now);
    assertThat(reloadedTldDomain.getSubordinateHosts()).containsExactly("ns2.example.tld");
    assertDnsTasksEnqueued("ns1.example.foo", "ns2.example.tld");
  }

  @TestOfyAndSql
  void testSuccess_internalToExternal() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        null,
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("foo");
    // This registrar should be superseded by domain's registrar.
    persistNewRegistrar("Superseded");
    DomainBase domain =
        persistResource(
            newDomainBase("example.foo")
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of(oldHostName()))
                .build());
    assertThat(domain.getCurrentSponsorClientId()).isEqualTo("TheRegistrar");
    DateTime oneDayAgo = clock.nowUtc().minusDays(1);
    HostResource oldHost =
        persistResource(
            persistActiveSubordinateHost(oldHostName(), domain)
                .asBuilder()
                .setPersistedCurrentSponsorClientId("Superseded")
                .setLastTransferTime(oneDayAgo)
                .build());
    assertThat(oldHost.isSubordinate()).isTrue();
    assertThat(domain.getSubordinateHosts()).containsExactly("ns1.example.foo");
    HostResource renamedHost = doSuccessfulTest();
    assertAboutHosts()
        .that(renamedHost)
        .hasSuperordinateDomain(null)
        .and()
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(oneDayAgo)
        .and()
        .hasLastSuperordinateChange(clock.nowUtc());
    assertThat(renamedHost.getLastTransferTime()).isEqualTo(oneDayAgo);
    DomainBase reloadedDomain = loadByEntity(domain).cloneProjectedAtTime(clock.nowUtc());
    assertThat(reloadedDomain.getSubordinateHosts()).isEmpty();
    assertDnsTasksEnqueued("ns1.example.foo");
  }

  @TestOfyAndSql
  void testFailure_externalToInternal() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo", "ns2.example.tld", "<host:addr ip=\"v4\">192.0.2.22</host:addr>", null);
    createTld("tld");
    DomainBase domain = persistActiveDomain("example.tld");
    persistActiveHost(oldHostName());
    assertThat(domain.getSubordinateHosts()).isEmpty();
    assertThrows(CannotRenameExternalHostException.class, this::runFlow);
    assertNoDnsTasksEnqueued();
  }

  @TestOfyAndSql
  void testSuccess_superuserExternalToInternal() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo", "ns2.example.tld", "<host:addr ip=\"v4\">192.0.2.22</host:addr>", null);
    createTld("tld");
    DomainBase domain = persistActiveDomain("example.tld");
    persistActiveHost(oldHostName());
    assertThat(domain.getSubordinateHosts()).isEmpty();
    HostResource renamedHost = doSuccessfulTestAsSuperuser();
    DateTime now = clock.nowUtc();
    assertAboutHosts()
        .that(renamedHost)
        .hasSuperordinateDomain(domain.createVKey())
        .and()
        .hasLastSuperordinateChange(now)
        .and()
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(null);
    assertThat(loadByEntity(domain).cloneProjectedAtTime(now).getSubordinateHosts())
        .containsExactly("ns2.example.tld");
    assertDnsTasksEnqueued("ns2.example.tld");
  }

  @TestOfyAndSql
  void testFailure_externalToExternal() throws Exception {
    setEppHostUpdateInput("ns1.example.foo", "ns2.example.tld", null, null);
    persistActiveHost(oldHostName());
    EppException thrown = assertThrows(CannotRenameExternalHostException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_superuserExternalToExternal() throws Exception {
    setEppHostUpdateInput("ns1.example.foo", "ns2.example.tld", null, null);
    persistActiveHost(oldHostName());
    HostResource renamedHost = doSuccessfulTestAsSuperuser();
    assertAboutHosts()
        .that(renamedHost)
        .hasSuperordinateDomain(null)
        .and()
        .hasLastSuperordinateChange(null)
        .and()
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(null);
    assertNoDnsTasksEnqueued();
  }

  @TestOfyAndSql
  void testSuccess_superuserClientUpdateProhibited() throws Exception {
    setEppInput("host_update_add_status.xml");
    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
    assertAboutHosts()
        .that(reloadResourceByForeignKey())
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(null)
        .and()
        .hasStatusValue(StatusValue.CLIENT_UPDATE_PROHIBITED)
        .and()
        .hasStatusValue(StatusValue.SERVER_UPDATE_PROHIBITED);
  }

  @TestOfyAndSql
  void testSuccess_subordToSubord_lastTransferTimeFromPreviousSuperordinateWinsOut()
      throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DateTime lastTransferTime = clock.nowUtc().minusDays(5);
    DomainBase foo =
        persistResource(
            newDomainBase("foo.tld").asBuilder().setLastTransferTime(lastTransferTime).build());
    // Set the new domain to have a last transfer time that is different than the last transfer
    // time on the host in question.
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setLastTransferTime(clock.nowUtc().minusDays(10))
            .build());

    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setSuperordinateDomain(foo.createVKey())
            .setLastTransferTime(null)
            .build());
    persistResource(foo.asBuilder().setSubordinateHosts(ImmutableSet.of(oldHostName())).build());
    clock.advanceOneMilli();
    HostResource renamedHost = doSuccessfulTest();
    assertAboutHosts()
        .that(renamedHost)
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(lastTransferTime);
  }

  @TestOfyAndSql
  void testSuccess_subordToSubord_lastTransferTimeOnExistingHostWinsOut() throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainBase domain =
        persistResource(
            newDomainBase("foo.tld")
                .asBuilder()
                .setLastTransferTime(clock.nowUtc().minusDays(5))
                .build());
    // Set the new domain to have a last transfer time that is different than the last transfer
    // time on the host in question.
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setLastTransferTime(clock.nowUtc().minusDays(10))
            .build());
    HostResource host =
        persistResource(
            newHostResource(oldHostName())
                .asBuilder()
                .setSuperordinateDomain(domain.createVKey())
                .setLastTransferTime(clock.nowUtc().minusDays(20))
                .setLastSuperordinateChange(clock.nowUtc().minusDays(3))
                .build());
    DateTime lastTransferTime = host.getLastTransferTime();
    persistResource(domain.asBuilder().setSubordinateHosts(ImmutableSet.of(oldHostName())).build());
    HostResource renamedHost = doSuccessfulTest();
    assertAboutHosts()
        .that(renamedHost)
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(lastTransferTime);
  }

  @TestOfyAndSql
  void testSuccess_subordToSubord_lastTransferTimeOnExistingHostWinsOut_whenNullOnNewDomain()
      throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainBase foo =
        persistResource(
            newDomainBase("foo.tld")
                .asBuilder()
                .setLastTransferTime(clock.nowUtc().minusDays(5))
                .build());
    // Set the new domain to have a null last transfer time.
    persistResource(newDomainBase("example.tld").asBuilder().setLastTransferTime(null).build());
    DateTime lastTransferTime = clock.nowUtc().minusDays(20);

    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setSuperordinateDomain(foo.createVKey())
            .setLastTransferTime(lastTransferTime)
            .setLastSuperordinateChange(clock.nowUtc().minusDays(3))
            .build());
    persistResource(foo.asBuilder().setSubordinateHosts(ImmutableSet.of(oldHostName())).build());
    clock.advanceOneMilli();
    HostResource renamedHost = doSuccessfulTest();
    assertAboutHosts()
        .that(renamedHost)
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(lastTransferTime);
  }

  @TestOfyAndSql
  void testSuccess_subordToSubord_lastTransferTimeOnExistingHostWins_whenNullOnBothDomains()
      throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainBase foo =
        persistResource(newDomainBase("foo.tld").asBuilder().setLastTransferTime(null).build());
    // Set the new domain to have a null last transfer time.
    persistResource(newDomainBase("example.tld").asBuilder().setLastTransferTime(null).build());
    DateTime lastTransferTime = clock.nowUtc().minusDays(20);

    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setSuperordinateDomain(foo.createVKey())
            .setLastTransferTime(lastTransferTime)
            .setLastSuperordinateChange(clock.nowUtc().minusDays(10))
            .build());
    persistResource(foo.asBuilder().setSubordinateHosts(ImmutableSet.of(oldHostName())).build());
    clock.advanceOneMilli();
    HostResource renamedHost = doSuccessfulTest();
    assertAboutHosts()
        .that(renamedHost)
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(lastTransferTime);
  }

  @TestOfyAndSql
  void testSuccess_subordToSubord_lastTransferTimeIsNull_whenNullOnBoth() throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainBase foo =
        persistResource(
            newDomainBase("foo.tld")
                .asBuilder()
                .setLastTransferTime(clock.nowUtc().minusDays(5))
                .build());
    // Set the new domain to have a null last transfer time.
    persistResource(newDomainBase("example.tld").asBuilder().setLastTransferTime(null).build());
    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setSuperordinateDomain(foo.createVKey())
            .setLastTransferTime(null)
            .setLastSuperordinateChange(clock.nowUtc().minusDays(3))
            .build());
    persistResource(foo.asBuilder().setSubordinateHosts(ImmutableSet.of(oldHostName())).build());
    HostResource renamedHost = doSuccessfulTest();
    assertAboutHosts()
        .that(renamedHost)
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(null);
  }

  @TestOfyAndSql
  void testSuccess_internalToExternal_lastTransferTimeFrozenWhenComingFromSuperordinate()
      throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        null,
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("foo");
    DomainBase domain = persistActiveDomain("example.foo");
    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setSuperordinateDomain(domain.createVKey())
            .build());
    DateTime lastTransferTime = clock.nowUtc().minusDays(2);
    persistResource(
        domain
            .asBuilder()
            .setLastTransferTime(lastTransferTime)
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    clock.advanceOneMilli();
    HostResource renamedHost = doSuccessfulTest();
    clock.advanceOneMilli();
    persistResource(domain.asBuilder().setLastTransferTime(clock.nowUtc().minusDays(1)).build());
    // The last transfer time should be what was on the superordinate domain at the time of the host
    // update, not what it is later changed to.
    assertAboutHosts()
        .that(renamedHost)
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(lastTransferTime)
        .and()
        // Need to add two milliseconds to account for the increment of "persist resource" and the
        // artificial increment introduced after the flow itself.
        .hasLastSuperordinateChange(clock.nowUtc().minusMillis(2));
  }

  @TestOfyAndSql
  void testSuccess_internalToExternal_lastTransferTimeFrozenWhenComingFromHost() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        null,
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("foo");
    DomainBase domain = persistActiveDomain("example.foo");
    DateTime lastTransferTime = clock.nowUtc().minusDays(12);
    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setSuperordinateDomain(domain.createVKey())
            .setLastTransferTime(lastTransferTime)
            .setLastSuperordinateChange(clock.nowUtc().minusDays(4))
            .build());
    persistResource(
        domain
            .asBuilder()
            .setLastTransferTime(clock.nowUtc().minusDays(14))
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    clock.advanceOneMilli();
    HostResource renamedHost = doSuccessfulTest();
    // The last transfer time should be what was on the host, because the host's old superordinate
    // domain wasn't transferred more recently than when the host was changed to have that
    // superordinate domain.
    assertAboutHosts()
        .that(renamedHost)
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(lastTransferTime);
  }

  @TestOfyAndSql
  void testSuccess_internalToExternal_lastTransferTimeFrozenWhenDomainOverridesHost()
      throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        null,
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("foo");
    DomainBase domain = persistActiveDomain("example.foo");
    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setSuperordinateDomain(domain.createVKey())
            .setLastTransferTime(clock.nowUtc().minusDays(12))
            .setLastSuperordinateChange(clock.nowUtc().minusDays(4))
            .build());
    domain =
        persistResource(
            domain
                .asBuilder()
                .setLastTransferTime(clock.nowUtc().minusDays(2))
                .setSubordinateHosts(ImmutableSet.of(oldHostName()))
                .build());
    HostResource renamedHost = doSuccessfulTest();
    // The last transfer time should be what was on the superordinate domain, because the domain
    // was transferred more recently than the last time the host's superordinate domain was changed.
    assertAboutHosts()
        .that(renamedHost)
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(domain.getLastTransferTime());
  }

  private void doExternalToInternalLastTransferTimeTest(
      DateTime hostTransferTime, @Nullable DateTime domainTransferTime) throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo", "ns2.example.tld", "<host:addr ip=\"v4\">192.0.2.22</host:addr>", null);
    createTld("tld");
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setLastTransferTime(domainTransferTime)
            .build());
    persistResource(
        newHostResource(oldHostName()).asBuilder().setLastTransferTime(hostTransferTime).build());
    HostResource renamedHost = doSuccessfulTestAsSuperuser();
    assertAboutHosts()
        .that(renamedHost)
        .hasPersistedCurrentSponsorClientId("TheRegistrar")
        .and()
        .hasLastTransferTime(hostTransferTime);
  }

  @TestOfyAndSql
  void testSuccess_externalToSubord_lastTransferTimeNotOverridden_whenLessRecent()
      throws Exception {
    doExternalToInternalLastTransferTimeTest(
        clock.nowUtc().minusDays(2), clock.nowUtc().minusDays(1));
  }

  @TestOfyAndSql
  void testSuccess_externalToSubord_lastTransferTimeNotOverridden_whenMoreRecent()
      throws Exception {
    doExternalToInternalLastTransferTimeTest(
        clock.nowUtc().minusDays(2), clock.nowUtc().minusDays(3));
  }

  /** Test when the new superdordinate domain has never been transferred before. */
  @TestOfyAndSql
  void testSuccess_externalToSubord_lastTransferTimeNotOverridden_whenNull() throws Exception {
    doExternalToInternalLastTransferTimeTest(clock.nowUtc().minusDays(2), null);
  }

  @TestOfyAndSql
  void testFailure_superordinateMissing() throws Exception {
    createTld("tld");
    persistActiveHost(oldHostName());
    SuperordinateDomainDoesNotExistException thrown =
        assertThrows(SuperordinateDomainDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(example.tld)");
  }

  @TestOfyAndSql
  void testFailure_superordinateInPendingDelete() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainBase domain =
        persistResource(
            newDomainBase("example.tld")
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of(oldHostName()))
                .setDeletionTime(clock.nowUtc().plusDays(35))
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
                .build());
    persistActiveSubordinateHost(oldHostName(), domain);
    clock.advanceOneMilli();
    SuperordinateDomainInPendingDeleteException thrown =
        assertThrows(SuperordinateDomainInPendingDeleteException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Superordinate domain for this hostname is in pending delete");
  }

  @TestOfyAndSql
  void testFailure_neverExisted() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_neverExisted_updateWithoutNameChange() throws Exception {
    setEppInput("host_update_name_unchanged.xml");
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_existedButWasDeleted() throws Exception {
    persistDeletedHost(oldHostName(), clock.nowUtc().minusDays(1));
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_renameToCurrentName() throws Exception {
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    clock.advanceOneMilli();
    setEppHostUpdateInput("ns1.example.tld", "ns1.example.tld", null, null);
    HostAlreadyExistsException thrown =
        assertThrows(HostAlreadyExistsException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns1.example.tld");
  }

  @TestOfyAndSql
  void testFailure_renameToNameOfExistingOtherHost() throws Exception {
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    persistActiveHost("ns2.example.tld");
    HostAlreadyExistsException thrown =
        assertThrows(HostAlreadyExistsException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns2.example.tld");
  }

  @TestOfyAndSql
  void testFailure_referToNonLowerCaseHostname() {
    setEppHostUpdateInput("ns1.EXAMPLE.tld", "ns2.example.tld", null, null);
    EppException thrown = assertThrows(HostNameNotLowerCaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_renameToNonLowerCaseHostname() {
    persistActiveHost("ns1.example.tld");
    setEppHostUpdateInput("ns1.example.tld", "ns2.EXAMPLE.tld", null, null);
    EppException thrown = assertThrows(HostNameNotLowerCaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_referToNonPunyCodedHostname() {
    setEppHostUpdateInput("ns1.çauçalito.tld", "ns1.sausalito.tld", null, null);
    HostNameNotPunyCodedException thrown =
        assertThrows(HostNameNotPunyCodedException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected ns1.xn--aualito-txac.tld");
  }

  @TestOfyAndSql
  void testFailure_renameToNonPunyCodedHostname() {
    persistActiveHost("ns1.sausalito.tld");
    setEppHostUpdateInput("ns1.sausalito.tld", "ns1.çauçalito.tld", null, null);
    HostNameNotPunyCodedException thrown =
        assertThrows(HostNameNotPunyCodedException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected ns1.xn--aualito-txac.tld");
  }

  @TestOfyAndSql
  void testFailure_referToNonCanonicalHostname() {
    persistActiveHost("ns1.example.tld.");
    setEppHostUpdateInput("ns1.example.tld.", "ns2.example.tld", null, null);
    EppException thrown = assertThrows(HostNameNotNormalizedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_renameToNonCanonicalHostname() {
    persistActiveHost("ns1.example.tld");
    setEppHostUpdateInput("ns1.example.tld", "ns2.example.tld.", null, null);
    EppException thrown = assertThrows(HostNameNotNormalizedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_subordinateNeedsIps() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.tld",
        "ns2.example.tld",
        null,
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    EppException thrown =
        assertThrows(CannotRemoveSubordinateHostLastIpException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_subordinateToExternal_mustRemoveAllIps() throws Exception {
    setEppHostUpdateInput("ns1.example.tld", "ns2.example.com", null, null);
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    EppException thrown = assertThrows(RenameHostToExternalRemoveIpException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_subordinateToExternal_cantAddAnIp() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.tld", "ns2.example.com", "<host:addr ip=\"v4\">192.0.2.22</host:addr>", null);
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    EppException thrown = assertThrows(CannotAddIpToExternalHostException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_addRemoveSameStatusValues() throws Exception {
    createTld("tld");
    persistActiveDomain("example.tld");
    setEppHostUpdateInput(
        "ns1.example.tld",
        "ns2.example.tld",
        "<host:status s=\"clientUpdateProhibited\"/>",
        "<host:status s=\"clientUpdateProhibited\"/>");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    EppException thrown = assertThrows(AddRemoveSameValueException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_addRemoveSameInetAddresses() throws Exception {
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    setEppHostUpdateInput(
        "ns1.example.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>");
    EppException thrown = assertThrows(AddRemoveSameValueException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_clientUpdateProhibited_removed() throws Exception {
    setEppInput("host_update_remove_client_update_prohibited.xml");
    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    clock.advanceOneMilli();
    runFlow();
    assertAboutEppResources()
        .that(reloadResourceByForeignKey())
        .doesNotHaveStatusValue(StatusValue.CLIENT_UPDATE_PROHIBITED);
  }

  @TestOfyAndSql
  void testFailure_clientUpdateProhibited() throws Exception {
    createTld("tld");
    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .setSuperordinateDomain(persistActiveDomain("example.tld").createVKey())
            .build());
    EppException thrown =
        assertThrows(ResourceHasClientUpdateProhibitedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_serverUpdateProhibited() throws Exception {
    createTld("tld");
    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.SERVER_UPDATE_PROHIBITED))
            .setSuperordinateDomain(persistActiveDomain("example.tld").createVKey())
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("serverUpdateProhibited");
  }

  @TestOfyAndSql
  void testFailure_pendingDelete() throws Exception {
    createTld("tld");
    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .setSuperordinateDomain(persistActiveDomain("example.tld").createVKey())
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("pendingDelete");
  }

  @TestOfyAndSql
  void testFailure_statusValueNotClientSettable() throws Exception {
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    setEppInput("host_update_prohibited_status.xml");
    EppException thrown = assertThrows(StatusNotClientSettableException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_superuserStatusValueNotClientSettable() throws Exception {
    setEppInput("host_update_prohibited_status.xml");
    createTld("tld");
    persistActiveDomain("example.tld");
    persistActiveHost("ns1.example.tld");

    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
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
    persistActiveHost(oldHostName());

    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.DRY_RUN, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @TestOfyAndSql
  void testSuccess_authorizedClientReadFromSuperordinate() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
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
            .setPersistedCurrentSponsorClientId("TheRegistrar") // Shouldn't hurt.
            .setSuperordinateDomain(domain.createVKey())
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
            .build());

    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @TestOfyAndSql
  void testFailure_unauthorizedClientReadFromSuperordinate() {
    sessionMetadata.setClientId("NewRegistrar");
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
            .setPersistedCurrentSponsorClientId("NewRegistrar") // Shouldn't help.
            .setSuperordinateDomain(domain.createVKey())
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
            .build());

    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_authorizedClientReadFromTransferredSuperordinate() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    createTld("tld");
    // Create a domain that will belong to NewRegistrar after cloneProjectedAtTime is called.
    DomainBase domain = persistResource(createDomainWithServerApprovedTransfer("example.tld"));
    persistResource(
        newHostResource("ns1.example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorClientId("TheRegistrar") // Shouldn't hurt.
            .setSuperordinateDomain(domain.createVKey())
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
            .build());

    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @TestOfyAndSql
  void testFailure_unauthorizedClientReadFromTransferredSuperordinate() {
    sessionMetadata.setClientId("TheRegistrar");
    createTld("tld");
    // Create a domain that will belong to NewRegistrar after cloneProjectedAtTime is called.
    DomainBase domain = persistResource(createDomainWithServerApprovedTransfer("example.tld"));
    persistResource(
        newHostResource("ns1.example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorClientId("TheRegistrar") // Shouldn't help.
            .setSuperordinateDomain(domain.createVKey())
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
            .build());

    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_newSuperordinateOwnedByDifferentRegistrar() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo", "ns2.example.tld", "<host:addr ip=\"v4\">192.0.2.22</host:addr>", null);
    sessionMetadata.setClientId("TheRegistrar");
    createTld("foo");
    createTld("tld");
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setPersistedCurrentSponsorClientId("NewRegistrar")
            .build());
    HostResource host =
        persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.foo"));
    assertAboutHosts().that(host).hasPersistedCurrentSponsorClientId("TheRegistrar");

    EppException thrown = assertThrows(HostDomainNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_newSuperordinateWasTransferredToDifferentRegistrar() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo", "ns2.example.tld", "<host:addr ip=\"v4\">192.0.2.22</host:addr>", null);
    sessionMetadata.setClientId("TheRegistrar");
    createTld("foo");
    createTld("tld");
    HostResource host =
        persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.foo"));
    // The domain will belong to NewRegistrar after cloneProjectedAtTime is called.
    DomainBase domain = persistResource(createDomainWithServerApprovedTransfer("example.tld"));
    assertAboutDomains().that(domain).hasPersistedCurrentSponsorClientId("TheRegistrar");
    assertAboutHosts().that(host).hasPersistedCurrentSponsorClientId("TheRegistrar");

    EppException thrown = assertThrows(HostDomainNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_newSuperordinateWasTransferredToCorrectRegistrar() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo", "ns2.example.tld", "<host:addr ip=\"v4\">192.0.2.22</host:addr>", null);
    sessionMetadata.setClientId("NewRegistrar");
    createTld("foo");
    createTld("tld");
    // The domain will belong to NewRegistrar after cloneProjectedAtTime is called.
    DomainBase domain = persistResource(createDomainWithServerApprovedTransfer("example.tld"));
    DomainBase superordinate =
        persistResource(
            newDomainBase("example.foo")
                .asBuilder()
                .setPersistedCurrentSponsorClientId("NewRegistrar")
                .build());
    assertAboutDomains().that(domain).hasPersistedCurrentSponsorClientId("TheRegistrar");
    persistResource(
        newHostResource("ns1.example.foo")
            .asBuilder()
            .setSuperordinateDomain(superordinate.createVKey())
            .setPersistedCurrentSponsorClientId("NewRegistrar")
            .build());

    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  private void doFailingHostNameTest(String hostName, Class<? extends EppException> exception)
      throws Exception {
    persistActiveHost(oldHostName());
    setEppHostUpdateInput(
        "ns1.example.tld",
        hostName,
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    EppException thrown = assertThrows(exception, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_renameToBadCharacter() throws Exception {
    doFailingHostNameTest("foo bar", InvalidHostNameException.class);
  }

  @TestOfyAndSql
  void testFailure_renameToNotPunyCoded() throws Exception {
    doFailingHostNameTest("みんな", HostNameNotPunyCodedException.class);
  }

  @TestOfyAndSql
  void testFailure_renameToTooLong() throws Exception {
    // Host names can be max 253 chars.
    String suffix = ".example.tld";
    String tooLong = Strings.repeat("a", 254 - suffix.length()) + suffix;
    doFailingHostNameTest(tooLong, HostNameTooLongException.class);
  }

  @TestOfyAndSql
  void testFailure_renameToTooShallowPublicSuffix() throws Exception {
    doFailingHostNameTest("example.com", HostNameTooShallowException.class);
  }

  @TestOfyAndSql
  void testFailure_renameToTooShallowCcTld() throws Exception {
    doFailingHostNameTest("foo.co.uk", HostNameTooShallowException.class);
  }

  @TestOfyAndSql
  void testFailure_renameToBarePublicSuffix() throws Exception {
    doFailingHostNameTest("com", HostNameTooShallowException.class);
  }

  @TestOfyAndSql
  void testFailure_renameToBareCcTld() throws Exception {
    doFailingHostNameTest("co.uk", HostNameTooShallowException.class);
  }

  @TestOfyAndSql
  void testFailure_renameToTooShallowNewTld() throws Exception {
    doFailingHostNameTest("example.lol", HostNameTooShallowException.class);
  }

  @TestOfyAndSql
  void testFailure_ccTldInBailiwick() throws Exception {
    createTld("co.uk");
    doFailingHostNameTest("foo.co.uk", HostNameTooShallowException.class);
  }

  @TestOfyAndSql
  void testSuccess_metadata() throws Exception {
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    clock.advanceOneMilli();
    setEppInput("host_update_metadata.xml");
    eppRequestSource = EppRequestSource.TOOL;
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    assertAboutHistoryEntries()
        .that(
            getOnlyHistoryEntryOfType(reloadResourceByForeignKey(), HistoryEntry.Type.HOST_UPDATE))
        .hasMetadataReason("host-update-test")
        .and()
        .hasMetadataRequestedByRegistrar(false);
  }

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    createTld("tld");
    persistActiveSubordinateHost(oldHostName(), persistActiveDomain("example.tld"));
    clock.advanceOneMilli();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-host-update");
  }
}
