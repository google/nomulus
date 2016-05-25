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

package google.registry.flows.async;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadByUniqueId;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.HostResourceSubject.assertAboutHosts;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableSet;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Ref;

import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.request.HttpException.BadRequestException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DeleteHostResourceAction}. */
@RunWith(JUnit4.class)
public class DeleteHostResourceActionTest
    extends DeleteEppResourceActionTestCase<DeleteHostResourceAction> {

  HostResource hostUnused;

  @Before
  public void setup() throws Exception {
    setupDeleteEppResourceAction(new DeleteHostResourceAction());
    hostUnused = persistActiveHost("ns2.example.tld");
  }

  @Test
  public void testSuccess_host_referencedByActiveDomain_doesNotGetDeleted() throws Exception {
    hostUsed = persistResource(
        hostUsed.asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    runMapreduceWithKeyParam(Key.create(hostUsed).getString());
    hostUsed = loadByUniqueId(HostResource.class, "ns1.example.tld", now);
    assertAboutHosts().that(hostUsed).doesNotHaveStatusValue(StatusValue.PENDING_DELETE)
        .and().hasDeletionTime(END_OF_TIME);
    domain = loadByUniqueId(DomainResource.class, "example.tld", now);
    assertThat(domain.getNameservers()).contains(Ref.create(hostUsed));
    HistoryEntry historyEntry =
        getOnlyHistoryEntryOfType(hostUsed, HistoryEntry.Type.HOST_DELETE_FAILURE);
    assertPollMessageFor(
        historyEntry,
        "TheRegistrar",
        "Can't delete host ns1.example.tld because it is referenced by a domain.");
  }

  @Test
  public void testSuccess_host_notReferenced_getsDeleted() throws Exception {
    hostUnused = persistResource(
        hostUnused.asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    runMapreduceWithKeyParam(Key.create(hostUnused).getString());
    assertThat(loadByUniqueId(HostResource.class, "ns2.example.tld", now)).isNull();
    HostResource hostBeforeDeletion =
        loadByUniqueId(HostResource.class, "ns2.example.tld", now.minusDays(1));
    assertAboutHosts().that(hostBeforeDeletion).hasDeletionTime(now)
        .and().hasExactlyStatusValues(StatusValue.OK)
        // Note that there will be another history entry of HOST_PENDING_DELETE, but this is
        // added by the flow and not the mapreduce itself.
        .and().hasOnlyOneHistoryEntryWhich().hasType(HistoryEntry.Type.HOST_DELETE);
    HistoryEntry historyEntry =
        getOnlyHistoryEntryOfType(hostBeforeDeletion, HistoryEntry.Type.HOST_DELETE);
    assertPollMessageFor(historyEntry, "TheRegistrar", "Deleted host ns2.example.tld.");
  }

  @Test
  public void testSuccess_host_referencedByDeletedDomain_getsDeleted() throws Exception {
    hostUsed = persistResource(
        hostUsed.asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    domain = persistResource(
        domain.asBuilder()
            .setDeletionTime(now.minusDays(3))
            .build());
    runMapreduceWithKeyParam(Key.create(hostUsed).getString());
    assertThat(loadByUniqueId(HostResource.class, "ns1.example.tld", now)).isNull();
    HostResource hostBeforeDeletion =
        loadByUniqueId(HostResource.class, "ns1.example.tld", now.minusDays(1));
    assertAboutHosts().that(hostBeforeDeletion).hasDeletionTime(now)
        .and().hasExactlyStatusValues(StatusValue.OK)
        // Note that there will be another history entry of HOST_PENDING_DELETE, but this is
        // added by the flow and not the mapreduce itself.
        .and().hasOnlyOneHistoryEntryWhich().hasType(HistoryEntry.Type.HOST_DELETE);
    HistoryEntry historyEntry =
        getOnlyHistoryEntryOfType(hostBeforeDeletion, HistoryEntry.Type.HOST_DELETE);
    assertPollMessageFor(historyEntry, "TheRegistrar", "Deleted host ns1.example.tld.");
  }

  @Test
  public void testSuccess_subordinateHost_getsDeleted() throws Exception {
    domain = persistResource(
        newDomainResource("example.tld").asBuilder()
            .setSubordinateHosts(ImmutableSet.of("ns2.example.tld"))
            .build());
    persistResource(
        hostUnused.asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .setSuperordinateDomain(Ref.create(domain))
            .build());
    runMapreduceWithKeyParam(Key.create(hostUnused).getString());
    // Check that the host is deleted as of now.
    assertThat(loadByUniqueId(HostResource.class, "ns2.example.tld", clock.nowUtc()))
        .isNull();
    assertNoBillingEvents();
    assertThat(loadByUniqueId(DomainResource.class, "example.tld", clock.nowUtc())
        .getSubordinateHosts())
            .isEmpty();
    assertDnsTasksEnqueued("ns2.example.tld");
    HostResource hostBeforeDeletion =
        loadByUniqueId(HostResource.class, "ns2.example.tld", now.minusDays(1));
    assertAboutHosts().that(hostBeforeDeletion).hasDeletionTime(now)
        .and().hasExactlyStatusValues(StatusValue.OK)
        .and().hasOnlyOneHistoryEntryWhich().hasType(HistoryEntry.Type.HOST_DELETE);
    HistoryEntry historyEntry =
        getOnlyHistoryEntryOfType(hostBeforeDeletion, HistoryEntry.Type.HOST_DELETE);
    assertPollMessageFor(historyEntry, "TheRegistrar", "Deleted host ns2.example.tld.");
  }

  @Test
  public void testFailure_notPendingDelete() throws Exception {
    thrown.expect(
        IllegalStateException.class, "Resource ns2.example.tld is not set as PENDING_DELETE");
    runMapreduceWithKeyParam(Key.create(hostUnused).getString());
    assertThat(
        loadByUniqueId(HostResource.class, "ns2.example.tld", now)).isEqualTo(hostUnused);
  }

  @Test
  public void testSuccess_notRequestedByOwner_doesNotGetDeleted() throws Exception {
    hostUnused = persistResource(
        hostUnused.asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    Key<HostResource> key = Key.create(hostUnused);
    runMapreduceWithParams(key.getString(), "OtherRegistrar", false);
    hostUnused = loadByUniqueId(HostResource.class, "ns2.example.tld", now);
    assertAboutHosts().that(hostUnused).doesNotHaveStatusValue(StatusValue.PENDING_DELETE)
        .and().hasDeletionTime(END_OF_TIME);
    domain = loadByUniqueId(DomainResource.class, "example.tld", now);
    HistoryEntry historyEntry =
        getOnlyHistoryEntryOfType(hostUnused, HistoryEntry.Type.HOST_DELETE_FAILURE);
    assertPollMessageFor(
        historyEntry,
        "OtherRegistrar",
        "Can't delete host ns2.example.tld because it was transferred prior to deletion.");
  }

  @Test
  public void testSuccess_notRequestedByOwner_isSuperuser_getsDeleted() throws Exception {
    hostUnused = persistResource(
        hostUnused.asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    Key<HostResource> key = Key.create(hostUnused);
    runMapreduceWithParams(key.getString(), "OtherRegistrar", true);
    assertThat(loadByUniqueId(HostResource.class, "ns2.example.tld", now)).isNull();
    HostResource hostBeforeDeletion =
        loadByUniqueId(HostResource.class, "ns2.example.tld", now.minusDays(1));
    assertAboutHosts().that(hostBeforeDeletion).hasDeletionTime(now)
        .and().hasExactlyStatusValues(StatusValue.OK)
        // Note that there will be another history entry of HOST_PENDING_DELETE, but this is
        // added by the flow and not the mapreduce itself.
        .and().hasOnlyOneHistoryEntryWhich().hasType(HistoryEntry.Type.HOST_DELETE);
    HistoryEntry historyEntry =
        getOnlyHistoryEntryOfType(hostBeforeDeletion, HistoryEntry.Type.HOST_DELETE);
    assertPollMessageFor(historyEntry, "OtherRegistrar", "Deleted host ns2.example.tld.");
  }

  @Test
  public void testFailure_targetResourceDoesntExist() throws Exception {
    createTld("tld");
    HostResource notPersisted = newHostResource("ns1.example.tld");
    thrown.expect(
        BadRequestException.class,
        "Could not load resource for key: Key<?>(HostResource(\"7-ROID\"))");
    runMapreduceWithKeyParam(Key.create(notPersisted).getString());
  }

  @Test
  public void testFailure_hostAlreadyDeleted() throws Exception {
    HostResource hostDeleted = persistResource(
        newHostResource("ns3.example.tld").asBuilder()
            .setCreationTime(clock.nowUtc().minusDays(2))
            .setDeletionTime(clock.nowUtc().minusDays(1))
            .build());
    thrown.expect(
        IllegalStateException.class,
        "Resource ns3.example.tld is already deleted.");
    runMapreduceWithKeyParam(Key.create(hostDeleted).getString());
  }
}
