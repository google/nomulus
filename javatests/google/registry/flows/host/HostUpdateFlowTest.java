// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static google.registry.flows.async.AsyncFlowUtils.ASYNC_FLOW_QUEUE_NAME;
import static google.registry.model.EppResourceUtils.loadByUniqueId;
import static google.registry.request.Actions.getPathForAction;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistActiveSubordinateHost;
import static google.registry.testing.DatastoreHelper.persistDeletedHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.GenericEppResourceSubject.assertAboutEppResources;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.testing.HostResourceSubject.assertAboutHosts;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Ref;
import google.registry.flows.EppRequestSource;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.ResourceMutateFlow.ResourceToMutateDoesNotExistException;
import google.registry.flows.ResourceUpdateFlow.ResourceHasClientUpdateProhibitedException;
import google.registry.flows.ResourceUpdateFlow.StatusNotClientSettableException;
import google.registry.flows.SingleResourceFlow.ResourceStatusProhibitsOperationException;
import google.registry.flows.async.DnsRefreshForHostRenameAction;
import google.registry.flows.host.HostFlowUtils.HostNameTooShallowException;
import google.registry.flows.host.HostFlowUtils.InvalidHostNameException;
import google.registry.flows.host.HostFlowUtils.SuperordinateDomainDoesNotExistException;
import google.registry.flows.host.HostUpdateFlow.CannotAddIpToExternalHostException;
import google.registry.flows.host.HostUpdateFlow.CannotRemoveSubordinateHostLastIpException;
import google.registry.flows.host.HostUpdateFlow.HostAlreadyExistsException;
import google.registry.flows.host.HostUpdateFlow.RenameHostToExternalRemoveIpException;
import google.registry.flows.host.HostUpdateFlow.RenameHostToSubordinateRequiresIpException;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link HostUpdateFlow}. */
public class HostUpdateFlowTest extends ResourceFlowTestCase<HostUpdateFlow, HostResource> {

  private void setEppHostUpdateInput(
      String oldHostName, String newHostName, String addHostAddrs, String remHostAddrs) {
    setEppInput(
        "host_update.xml",
        ImmutableMap.of(
            "OLD-HOSTNAME", oldHostName,
            "NEW-HOSTNAME", newHostName,
            "ADD-HOSTADDRS", (addHostAddrs == null) ? "" : addHostAddrs,
            "REM-HOSTADDRS", (remHostAddrs == null) ? "" : remHostAddrs));
  }

  public HostUpdateFlowTest() {
    setEppHostUpdateInput("ns1.example.tld", "ns2.example.tld", null, null);
  }

  @Before
  public void initHostTest() throws Exception {
    createTld("xn--q9jyb4c");
  }

  /** Alias for better readability. */
  private String oldHostName() throws Exception {
    return getUniqueIdFromCommand();
  }

  @Test
  public void testDryRun() throws Exception {
    persistActiveHost("ns1.example.tld");
    dryRunFlowAssertResponse(readFile("host_update_response.xml"));
  }

  private HostResource doSuccessfulTest() throws Exception {
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    runFlowAssertResponse(readFile("host_update_response.xml"));
    // The example xml does a host rename, so reloading the host (which uses the original host name)
    // should now return null.
    assertThat(reloadResourceByUniqueId()).isNull();
    // However, it should load correctly if we use the new name (taken from the xml).
    HostResource renamedHost =
        loadByUniqueId(HostResource.class, "ns2.example.tld", clock.nowUtc());
    assertAboutHosts().that(renamedHost)
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.HOST_UPDATE);
    assertNoBillingEvents();
    return renamedHost;
  }

  @Test
  public void testSuccess_removeClientUpdateProhibited() throws Exception {
    setEppInput("host_update_remove_client_update_prohibited.xml");
    persistActiveHost(oldHostName());
    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    clock.advanceOneMilli();
    runFlow();
    assertAboutEppResources().that(reloadResourceByUniqueId())
        .doesNotHaveStatusValue(StatusValue.CLIENT_UPDATE_PROHIBITED);
  }

  @Test
  public void testSuccess_rename_noOtherHostEverUsedTheOldName() throws Exception {
    persistActiveHost(oldHostName());
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getSuperordinateDomain()).isNull();
    assertNoDnsTasksEnqueued();  // No tasks enqueued since it's a rename of an external host.
    // The old ForeignKeyIndex is invalidated at the time we did the rename.
    ForeignKeyIndex<HostResource> oldFkiBeforeRename =
        ForeignKeyIndex.load(
            HostResource.class, oldHostName(), clock.nowUtc().minusMillis(1));
    assertThat(oldFkiBeforeRename.getReference()).isEqualTo(Ref.create(renamedHost));
    assertThat(oldFkiBeforeRename.getDeletionTime()).isEqualTo(clock.nowUtc());
    ForeignKeyIndex<HostResource> oldFkiAfterRename =
        ForeignKeyIndex.load(
            HostResource.class, oldHostName(), clock.nowUtc());
    assertThat(oldFkiAfterRename).isNull();
  }

  @Test
  public void testSuccess_withReferencingDomain() throws Exception {
    HostResource host = persistActiveHost(oldHostName());
    persistResource(
        newDomainResource("test.xn--q9jyb4c").asBuilder()
            .setDeletionTime(END_OF_TIME)
            .setNameservers(ImmutableSet.of(Ref.create(host)))
            .build());
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getSuperordinateDomain()).isNull();
    // Task enqueued to change the NS record of the referencing domain via mapreduce.
    assertTasksEnqueued(ASYNC_FLOW_QUEUE_NAME, new TaskMatcher()
        .url(getPathForAction(DnsRefreshForHostRenameAction.class))
        .param(DnsRefreshForHostRenameAction.PARAM_HOST_KEY, Key.create(renamedHost).getString()));
  }

  @Test
  public void testSuccess_nameUnchanged() throws Exception {
    setEppInput("host_update_name_unchanged.xml");
    createTld("tld");
    DomainResource domain = persistActiveDomain("example.tld");
    persistActiveSubordinateHost(oldHostName(), domain);

    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("host_update_response.xml"));
    // The example xml doesn't do a host rename, so reloading the host should work.
    assertAboutHosts().that(reloadResourceByUniqueId())
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.HOST_UPDATE);
    assertDnsTasksEnqueued("ns1.example.tld");
  }

  @Test
  public void testSuccess_renameWithSameSuperordinateDomain() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainResource domain = persistActiveDomain("example.tld");
    persistActiveSubordinateHost(oldHostName(), domain);
    persistResource(
        domain.asBuilder()
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    assertThat(
        loadByUniqueId(
            DomainResource.class, "example.tld", clock.nowUtc()).getSubordinateHosts())
            .containsExactly("ns1.example.tld");
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getSuperordinateDomain()).isEqualTo(Ref.create(domain));
    assertThat(
        loadByUniqueId(
            DomainResource.class, "example.tld", clock.nowUtc()).getSubordinateHosts())
            .containsExactly("ns2.example.tld");
    assertDnsTasksEnqueued("ns1.example.tld", "ns2.example.tld");
  }

  @Test
  public void testSuccess_internalToSameInternal() throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainResource foo = persistActiveDomain("foo.tld");
    DomainResource example = persistActiveDomain("example.tld");
    persistActiveSubordinateHost(oldHostName(), foo);
    persistResource(
        foo.asBuilder()
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    assertThat(
        loadByUniqueId(
            DomainResource.class, "foo.tld", clock.nowUtc()).getSubordinateHosts())
            .containsExactly("ns2.foo.tld");
    assertThat(
        loadByUniqueId(
            DomainResource.class, "example.tld", clock.nowUtc()).getSubordinateHosts())
            .isEmpty();
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getSuperordinateDomain()).isEqualTo(Ref.create(example));
    assertThat(
        loadByUniqueId(
            DomainResource.class, "foo.tld", clock.nowUtc()).getSubordinateHosts())
            .isEmpty();
    assertThat(
        loadByUniqueId(
            DomainResource.class, "example.tld", clock.nowUtc()).getSubordinateHosts())
            .containsExactly("ns2.example.tld");
    assertDnsTasksEnqueued("ns2.foo.tld", "ns2.example.tld");
  }

  @Test
  public void testSuccess_internalToExternal() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        null,
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("foo");
    DomainResource domain = persistActiveDomain("example.foo");
    persistActiveSubordinateHost(oldHostName(), domain);
    persistResource(
        domain.asBuilder()
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    assertThat(
        loadByUniqueId(
            DomainResource.class, "example.foo", clock.nowUtc()).getSubordinateHosts())
            .containsExactly("ns1.example.foo");
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getSuperordinateDomain()).isNull();
    // Ensure that the client id is set to the new registrar correctly (and that this necessarily
    // comes from the field on the host itself, because the superordinate domain is null).
    assertThat(renamedHost.getCurrentSponsorClientId()).isEqualTo("TheRegistrar");
    assertThat(
        loadByUniqueId(
            DomainResource.class, "example.foo", clock.nowUtc()).getSubordinateHosts())
            .isEmpty();
    assertDnsTasksEnqueued("ns1.example.foo");
  }

  @Test
  public void testSuccess_internalToDifferentInternal() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("foo");
    persistActiveDomain("example.foo");
    createTld("tld");
    DomainResource tldDomain = persistActiveDomain("example.tld");
    persistActiveHost(oldHostName());

    assertThat(loadByUniqueId(
        HostResource.class, oldHostName(), clock.nowUtc()).getCurrentSponsorClientId())
            .isEqualTo("TheRegistrar");
    assertThat(
        loadByUniqueId(
            DomainResource.class, "example.tld", clock.nowUtc()).getSubordinateHosts())
            .isEmpty();
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getSuperordinateDomain()).isEqualTo(Ref.create(tldDomain));
    assertThat(loadByUniqueId(
            DomainResource.class, "example.tld", clock.nowUtc()).getSubordinateHosts())
            .containsExactly("ns2.example.tld");
    assertDnsTasksEnqueued("ns2.example.tld");
    // Ensure that the client id is read off the domain because this is a subordinate host now.
    persistResource(
        tldDomain.asBuilder().setCurrentSponsorClientId("it_should_be_this").build());
    assertThat(
        renamedHost.cloneProjectedAtTime(clock.nowUtc().plusMinutes(1)).getCurrentSponsorClientId())
            .isEqualTo("it_should_be_this");
  }

  @Test
  public void testSuccess_externalToInternal() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        null);
    createTld("tld");
    DomainResource domain = persistActiveDomain("example.tld");
    persistActiveHost(oldHostName());

    assertThat(loadByUniqueId(
        HostResource.class, oldHostName(), clock.nowUtc()).getCurrentSponsorClientId())
            .isEqualTo("TheRegistrar");
    assertThat(loadByUniqueId(
            DomainResource.class, "example.tld", clock.nowUtc()).getSubordinateHosts())
            .isEmpty();
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getSuperordinateDomain()).isEqualTo(Ref.create(domain));
    assertThat(loadByUniqueId(
            DomainResource.class, "example.tld", clock.nowUtc()).getSubordinateHosts())
            .containsExactly("ns2.example.tld");
    assertDnsTasksEnqueued("ns2.example.tld");
    // Ensure that the client id is read off the domain because this is a subordinate host now.
    persistResource(
        domain.asBuilder().setCurrentSponsorClientId("it_should_be_this").build());
    assertThat(
        renamedHost.cloneProjectedAtTime(clock.nowUtc().plusMinutes(1)).getCurrentSponsorClientId())
            .isEqualTo("it_should_be_this");
  }

  @Test
  public void testSuccess_superuserClientUpdateProhibited() throws Exception {
    setEppInput("host_update_add_status.xml");
    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        readFile("host_update_response.xml"));
    assertAboutHosts().that(reloadResourceByUniqueId())
        .hasStatusValue(StatusValue.CLIENT_UPDATE_PROHIBITED).and()
        .hasStatusValue(StatusValue.SERVER_UPDATE_PROHIBITED);
  }

  @Test
  public void testSuccess_subordToSubord_lastTransferTimeFromPreviousSuperordinateWinsOut()
      throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DateTime lastTransferTime = clock.nowUtc().minusDays(5);
    DomainResource foo = newDomainResource("foo.tld").asBuilder()
        .setLastTransferTime(lastTransferTime)
        .build();
    // Set the new domain to have a last transfer time that is different than the last transfer
    // time on the host in question.
    persistResource(
        newDomainResource("example.tld").asBuilder()
            .setLastTransferTime(clock.nowUtc().minusDays(10))
            .build());

    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setSuperordinateDomain(Ref.create(foo))
            .setLastTransferTime(null)
            .build());
    persistResource(
        foo.asBuilder()
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    clock.advanceOneMilli();
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getLastTransferTime()).isEqualTo(lastTransferTime);
  }

  @Test
  public void testSuccess_subordToSubord_lastTransferTimeOnExistingHostWinsOut()
      throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainResource domain = newDomainResource("foo.tld").asBuilder()
        .setLastTransferTime(clock.nowUtc().minusDays(5))
        .build();
    // Set the new domain to have a last transfer time that is different than the last transfer
    // time on the host in question.
    persistResource(
        newDomainResource("example.tld").asBuilder()
            .setLastTransferTime(clock.nowUtc().minusDays(10))
            .build());
    HostResource host = persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setSuperordinateDomain(Ref.create(domain))
            .setLastTransferTime(clock.nowUtc().minusDays(20))
            .setLastSuperordinateChange(clock.nowUtc().minusDays(3))
            .build());
    DateTime lastTransferTime = host.getLastTransferTime();
    persistResource(
        domain.asBuilder().setSubordinateHosts(ImmutableSet.of(oldHostName())).build());
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getLastTransferTime()).isEqualTo(lastTransferTime);
  }

  @Test
  public void testSuccess_subordToSubord_lastTransferTimeOnExistingHostWinsOut_whenNullOnNewDomain()
      throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainResource foo = newDomainResource("foo.tld").asBuilder()
        .setLastTransferTime(clock.nowUtc().minusDays(5))
        .build();
    // Set the new domain to have a null last transfer time.
    persistResource(
        newDomainResource("example.tld").asBuilder().setLastTransferTime(null).build());
    DateTime lastTransferTime = clock.nowUtc().minusDays(20);

    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setSuperordinateDomain(Ref.create(foo))
            .setLastTransferTime(lastTransferTime)
            .setLastSuperordinateChange(clock.nowUtc().minusDays(3))
            .build());
    persistResource(
        foo.asBuilder()
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    clock.advanceOneMilli();
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getLastTransferTime()).isEqualTo(lastTransferTime);
  }

  @Test
  public void testSuccess_subordToSubord_lastTransferTimeOnExistingHostWins_whenNullOnBothDomains()
      throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainResource foo = newDomainResource("foo.tld").asBuilder()
        .setLastTransferTime(null)
        .build();
    // Set the new domain to have a null last transfer time.
    persistResource(
        newDomainResource("example.tld").asBuilder()
            .setLastTransferTime(null)
            .build());
    DateTime lastTransferTime = clock.nowUtc().minusDays(20);

    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setSuperordinateDomain(Ref.create(foo))
            .setLastTransferTime(lastTransferTime)
            .setLastSuperordinateChange(clock.nowUtc().minusDays(10))
            .build());
    persistResource(
        foo.asBuilder()
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    clock.advanceOneMilli();
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getLastTransferTime()).isEqualTo(lastTransferTime);
  }

  @Test
  public void testSuccess_subordToSubord_lastTransferTimeIsNull_whenNullOnBoth()
      throws Exception {
    setEppHostUpdateInput(
        "ns2.foo.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    DomainResource foo = newDomainResource("foo.tld").asBuilder()
        .setLastTransferTime(clock.nowUtc().minusDays(5))
        .build();
    // Set the new domain to have a null last transfer time.
    persistResource(
        newDomainResource("example.tld").asBuilder()
            .setLastTransferTime(null)
            .build());
    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setSuperordinateDomain(Ref.create(foo))
            .setLastTransferTime(null)
            .setLastSuperordinateChange(clock.nowUtc().minusDays(3))
            .build());
    persistResource(
        foo.asBuilder().setSubordinateHosts(ImmutableSet.of(oldHostName())).build());
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getLastTransferTime()).isNull();
  }

  @Test
  public void testSuccess_internalToExternal_lastTransferTimeFrozenWhenComingFromSuperordinate()
      throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        null,
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("foo");
    DomainResource domain = persistActiveDomain("example.foo");
    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setSuperordinateDomain(Ref.create(domain))
            .build());
    DateTime lastTransferTime = clock.nowUtc().minusDays(2);
    persistResource(
        domain.asBuilder()
            .setLastTransferTime(lastTransferTime)
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    clock.advanceOneMilli();
    HostResource renamedHost = doSuccessfulTest();
    persistResource(
        domain.asBuilder()
            .setLastTransferTime(clock.nowUtc().minusDays(1))
            .build());
    // The last transfer time should be what was on the superordinate domain at the time of the host
    // update, not what it is later changed to.
    assertThat(renamedHost.getLastTransferTime()).isEqualTo(lastTransferTime);
  }

  @Test
  public void testSuccess_internalToExternal_lastTransferTimeFrozenWhenComingFromHost()
      throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        null,
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("foo");
    DomainResource domain = persistActiveDomain("example.foo");
    DateTime lastTransferTime = clock.nowUtc().minusDays(12);

    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setSuperordinateDomain(Ref.create(domain))
            .setLastTransferTime(lastTransferTime)
            .setLastSuperordinateChange(clock.nowUtc().minusDays(4))
            .build());
    persistResource(
        domain.asBuilder()
            .setLastTransferTime(clock.nowUtc().minusDays(14))
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    clock.advanceOneMilli();
    HostResource renamedHost = doSuccessfulTest();
    // The last transfer time should be what was on the host, because the host's old superordinate
    // domain wasn't transferred more recently than when the host was changed to have that
    // superordinate domain.
    assertThat(renamedHost.getLastTransferTime()).isEqualTo(lastTransferTime);
  }

  @Test
  public void testSuccess_internalToExternal_lastTransferTimeFrozenWhenDomainOverridesHost()
      throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        null,
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("foo");
    DomainResource domain = persistActiveDomain("example.foo");
    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setSuperordinateDomain(Ref.create(domain))
            .setLastTransferTime(clock.nowUtc().minusDays(12))
            .setLastSuperordinateChange(clock.nowUtc().minusDays(4))
            .build());
    domain = persistResource(
        domain.asBuilder()
            .setLastTransferTime(clock.nowUtc().minusDays(2))
            .setSubordinateHosts(ImmutableSet.of(oldHostName()))
            .build());
    HostResource renamedHost = doSuccessfulTest();
    // The last transfer time should be what was on the superordinate domain, because the domain
    // was transferred more recently than the last time the host's superordinate domain was changed.
    assertThat(renamedHost.getLastTransferTime()).isEqualTo(domain.getLastTransferTime());
  }

  private void doExternalToInternalLastTransferTimeTest(
      DateTime hostTransferTime, @Nullable DateTime domainTransferTime) throws Exception {
    setEppHostUpdateInput(
        "ns1.example.foo",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        null);
    createTld("tld");
    persistResource(
        newDomainResource("example.tld").asBuilder()
            .setLastTransferTime(domainTransferTime)
            .build());
    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setLastTransferTime(hostTransferTime)
            .build());
    HostResource renamedHost = doSuccessfulTest();
    assertThat(renamedHost.getLastTransferTime()).isEqualTo(hostTransferTime);
  }

  @Test
  public void testSuccess_externalToSubord_lastTransferTimeNotOverridden_whenLessRecent()
      throws Exception {
    doExternalToInternalLastTransferTimeTest(
        clock.nowUtc().minusDays(2), clock.nowUtc().minusDays(1));
  }

  @Test
  public void testSuccess_externalToSubord_lastTransferTimeNotOverridden_whenMoreRecent()
      throws Exception {
    doExternalToInternalLastTransferTimeTest(
        clock.nowUtc().minusDays(2), clock.nowUtc().minusDays(3));
  }

  /** Test when the new superdordinate domain has never been transferred before. */
  @Test
  public void testSuccess_externalToSubord_lastTransferTimeNotOverridden_whenNull()
      throws Exception {
    doExternalToInternalLastTransferTimeTest(clock.nowUtc().minusDays(2), null);
  }

  @Test
  public void testFailure_superordinateMissing() throws Exception {
    createTld("tld");
    persistActiveHost(oldHostName());
    thrown.expect(
        SuperordinateDomainDoesNotExistException.class,
        "(example.tld)");
    runFlow();
  }

  @Test
  public void testFailure_neverExisted() throws Exception {
    thrown.expect(
        ResourceToMutateDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    runFlow();
  }

  @Test
  public void testFailure_neverExisted_updateWithoutNameChange() throws Exception {
    setEppInput("host_update_name_unchanged.xml");
    thrown.expect(
        ResourceToMutateDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    runFlow();
  }

  @Test
  public void testFailure_existedButWasDeleted() throws Exception {
    persistDeletedHost(oldHostName(), clock.nowUtc());
    thrown.expect(
        ResourceToMutateDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    runFlow();
  }

  @Test
  public void testFailure_renameToCurrentName() throws Exception {
    persistActiveHost(oldHostName());
    clock.advanceOneMilli();
    setEppHostUpdateInput("ns1.example.tld", "ns1.example.tld", null, null);
    thrown.expect(HostAlreadyExistsException.class, "ns1.example.tld");
    runFlow();
  }

  @Test
  public void testFailure_renameToNameOfExistingOtherHost() throws Exception {
    persistActiveHost(oldHostName());
    persistActiveHost("ns2.example.tld");
    thrown.expect(HostAlreadyExistsException.class, "ns2.example.tld");
    runFlow();
  }

  @Test
  public void testFailure_clientUpdateProhibited() throws Exception {
    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    thrown.expect(ResourceHasClientUpdateProhibitedException.class);
    runFlow();
  }

  @Test
  public void testFailure_serverUpdateProhibited() throws Exception {
    persistResource(
        newHostResource(oldHostName()).asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.SERVER_UPDATE_PROHIBITED))
            .build());
    thrown.expect(ResourceStatusProhibitsOperationException.class);
    runFlow();
  }

  @Test
  public void testFailure_subordinateNeedsIps() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.tld",
        "ns2.example.tld",
        null,
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    createTld("tld");
    persistResource(newHostResource(oldHostName()).asBuilder()
        .setSuperordinateDomain(Ref.create(persistActiveDomain("example.tld")))
        .build());
    thrown.expect(CannotRemoveSubordinateHostLastIpException.class);
    runFlow();
  }

  @Test
  public void testFailure_externalMustNotHaveIps() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.tld",
        "ns2.example.tld",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    persistActiveHost(oldHostName());
    thrown.expect(CannotAddIpToExternalHostException.class);
    runFlow();
  }

  @Test
  public void testFailure_subordinateToExternal_mustRemoveAllIps() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.tld",
        "ns2.example.com",
        null,
        null);
    createTld("tld");
    persistResource(
        newHostResource(oldHostName())
            .asBuilder()
            .setSuperordinateDomain(Ref.create(persistActiveDomain("example.tld")))
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
            .build());
    thrown.expect(RenameHostToExternalRemoveIpException.class);
    runFlow();
  }

  @Test
  public void testFailure_subordinateToExternal_cantAddAnIp() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.tld",
        "ns2.example.com",
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        null);
    createTld("tld");
    persistResource(newHostResource(oldHostName()).asBuilder()
        .setSuperordinateDomain(Ref.create(persistActiveDomain("example.tld")))
        .build());
    thrown.expect(CannotAddIpToExternalHostException.class);
    runFlow();
  }

  @Test
  public void testFailure_externalToSubordinate_mustAddAnIp() throws Exception {
    setEppHostUpdateInput(
        "ns1.example.com",
        "ns2.example.tld",
        null,
        null);
    createTld("tld");
    persistActiveDomain("example.tld");
    persistActiveHost(oldHostName());
    clock.advanceOneMilli();
    thrown.expect(RenameHostToSubordinateRequiresIpException.class);
    runFlow();
  }

  @Test
  public void testFailure_clientProhibitedStatusValue() throws Exception {
    createTld("tld");
    persistActiveDomain("example.tld");
    setEppInput("host_update_prohibited_status.xml");
    persistActiveHost(oldHostName());
    thrown.expect(StatusNotClientSettableException.class);
    runFlow();
  }

  @Test
  public void testSuccess_superuserClientProhibitedStatusValue() throws Exception {
    setEppInput("host_update_prohibited_status.xml");
    createTld("tld");
    persistActiveDomain("example.tld");
    persistActiveHost(oldHostName());

    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        readFile("host_update_response.xml"));
  }

  @Test
  public void testFailure_unauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistActiveHost(oldHostName());
    thrown.expect(ResourceNotOwnedException.class);
    runFlow();
  }

  @Test
  public void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistActiveHost(oldHostName());

    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.DRY_RUN, UserPrivileges.SUPERUSER, readFile("host_update_response.xml"));
  }

  private void doFailingHostNameTest(
      String hostName,
      Class<? extends Throwable> exception) throws Exception {
    persistActiveHost(oldHostName());
    setEppHostUpdateInput(
        "ns1.example.tld",
        hostName,
        "<host:addr ip=\"v4\">192.0.2.22</host:addr>",
        "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    thrown.expect(exception);
    runFlow();
  }

  @Test
  public void testFailure_renameToBadCharacter() throws Exception {
    doFailingHostNameTest("foo bar", InvalidHostNameException.class);
  }

  @Test
  public void testFailure_renameToTooShallowPublicSuffix() throws Exception {
    doFailingHostNameTest("example.com", HostNameTooShallowException.class);
  }

  @Test
  public void testFailure_renameToTooShallowCcTld() throws Exception {
    doFailingHostNameTest("foo.co.uk", HostNameTooShallowException.class);
  }

  @Test
  public void testFailure_renameToBarePublicSuffix() throws Exception {
    doFailingHostNameTest("com", HostNameTooShallowException.class);
  }

  @Test
  public void testFailure_renameToBareCcTld() throws Exception {
    doFailingHostNameTest("co.uk", HostNameTooShallowException.class);
  }

  @Test
  public void testFailure_renameToTooShallowNewTld() throws Exception {
    doFailingHostNameTest("example.lol", HostNameTooShallowException.class);
  }

  @Test
  public void testFailure_ccTldInBailiwick() throws Exception {
    createTld("co.uk");
    doFailingHostNameTest("foo.co.uk", HostNameTooShallowException.class);
  }

  @Test
  public void testSuccess_metadata() throws Exception {
    persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    setEppInput("host_update_metadata.xml");
    eppRequestSource = EppRequestSource.TOOL;
    runFlowAssertResponse(readFile("host_update_response.xml"));
    assertAboutHistoryEntries()
        .that(getOnlyHistoryEntryOfType(reloadResourceByUniqueId(), HistoryEntry.Type.HOST_UPDATE))
        .hasMetadataReason("host-update-test").and()
        .hasMetadataRequestedByRegistrar(false);
  }
}
