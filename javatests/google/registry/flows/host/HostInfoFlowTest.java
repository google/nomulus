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
import static google.registry.model.EppResourceUtils.isDeleted;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.persistResource;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;

import com.googlecode.objectify.Ref;

import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceQueryFlow.ResourceToQueryDoesNotExistException;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

import javax.annotation.Nullable;

/** Unit tests for {@link HostInfoFlow}. */
public class HostInfoFlowTest extends ResourceFlowTestCase<HostInfoFlow, HostResource> {

  public HostInfoFlowTest() {
    setEppInput("host_info.xml");
  }

  @Before
  public void initHostTest() {
    createTld("foobar");
  }

  private HostResource persistHostResource(boolean active) throws Exception {
    HostResource host = persistResource(
        new HostResource.Builder()
            .setFullyQualifiedHostName(getUniqueIdFromCommand())
            .setRepoId("1FF-FOOBAR")
            .setDeletionTime(active ? null : DateTime.now().minusDays(1))
            .setCurrentSponsorClientId("my sponsor")
            .setStatusValues(
                ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .setInetAddresses(ImmutableSet.of(
                InetAddresses.forString("192.0.2.2"),
                InetAddresses.forString("1080:0:0:0:8:800:200C:417A"),
                InetAddresses.forString("192.0.2.29")))
            .setCurrentSponsorClientId("TheRegistrar")
            .setCreationClientId("NewRegistrar")
            .setLastEppUpdateClientId("NewRegistrar")
            .setCreationTime(DateTime.parse("1999-04-03T22:00:00.0Z"))
            .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
            .setLastTransferTime(DateTime.parse("2000-04-08T09:00:00.0Z"))
            .build());
    assertThat(isDeleted(host, DateTime.now())).isNotEqualTo(active);
    return host;
  }

  @Test
  public void testSuccess() throws Exception {
    persistHostResource(true);
    assertTransactionalFlow(false);
    // Check that the persisted host info was returned.
    runFlowAssertResponse(
        readFile("host_info_response.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @Test
  public void testSuccess_linked() throws Exception {
    persistHostResource(true);
    persistResource(
        newDomainResource("example.foobar").asBuilder()
          .addNameservers(
              ImmutableSet.of(Ref.<HostResource>create(persistHostResource(true))))
          .build());
    assertTransactionalFlow(false);
    // Check that the persisted host info was returned.
    runFlowAssertResponse(
        readFile("host_info_response_linked.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  private void runTest_superordinateDomain(DateTime domainTransferTime,
      @Nullable DateTime lastSuperordinateChange) throws Exception {
    DomainResource domain = persistResource(
        newDomainResource("parent.foobar").asBuilder()
            .setRepoId("BEEF-FOOBAR")
            .setLastTransferTime(domainTransferTime)
            .setCurrentSponsorClientId("superclientid")
            .build());
    persistResource(
        persistHostResource(true).asBuilder()
            .setRepoId("CEEF-FOOBAR")
            .setSuperordinateDomain(Ref.create(domain))
            .setLastSuperordinateChange(lastSuperordinateChange)
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(
        readFile("host_info_response_superordinate_clientid.xml"),
        // We use a different roid scheme than the samples so ignore it.
        "epp.response.resData.infData.roid");
    assertNoHistory();
    assertNoBillingEvents();
  }

  @Test
  public void testSuccess_withSuperordinateDomain_hostMovedAfterDomainTransfer()
      throws Exception {
    runTest_superordinateDomain(DateTime.parse("2000-01-08T09:00:00.0Z"),
        DateTime.parse("2000-03-01T01:00:00.0Z"));
  }

  @Test
  public void testSuccess_withSuperordinateDomain_hostMovedBeforeDomainTransfer()
      throws Exception {
    runTest_superordinateDomain(DateTime.parse("2000-04-08T09:00:00.0Z"),
        DateTime.parse("2000-02-08T09:00:00.0Z"));
  }

  @Test
  public void testSuccess_withSuperordinateDomain() throws Exception {
    runTest_superordinateDomain(DateTime.parse("2000-04-08T09:00:00.0Z"), null);
  }

  @Test
  public void testFailure_neverExisted() throws Exception {
    thrown.expect(
        ResourceToQueryDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    runFlow();
  }

  @Test
  public void testFailure_existedButWasDeleted() throws Exception {
    thrown.expect(
        ResourceToQueryDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    persistHostResource(false);
    runFlow();
  }

  // Extra methods so the test runner doesn't produce empty shards.

  @Test public void testNothing1() {}
}
