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
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistDeletedHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HostResourceSubject.assertAboutHosts;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoDnsTasksEnqueued;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.IpAddressVersionMismatchException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.exceptions.ResourceAlreadyExistsForThisClientException;
import google.registry.flows.exceptions.ResourceCreateContentionException;
import google.registry.flows.host.HostCreateFlow.SubordinateHostMustHaveIpException;
import google.registry.flows.host.HostCreateFlow.UnexpectedExternalHostIpException;
import google.registry.flows.host.HostFlowUtils.HostNameNotLowerCaseException;
import google.registry.flows.host.HostFlowUtils.HostNameNotNormalizedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotPunyCodedException;
import google.registry.flows.host.HostFlowUtils.HostNameTooLongException;
import google.registry.flows.host.HostFlowUtils.HostNameTooShallowException;
import google.registry.flows.host.HostFlowUtils.InvalidHostNameException;
import google.registry.flows.host.HostFlowUtils.SuperordinateDomainDoesNotExistException;
import google.registry.flows.host.HostFlowUtils.SuperordinateDomainInPendingDeleteException;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import org.joda.time.DateTime;
import org.junit.Test;

/** Unit tests for {@link HostCreateFlow}. */
public class HostCreateFlowTest extends ResourceFlowTestCase<HostCreateFlow, HostResource> {

  private void setEppHostCreateInput(String hostName, String hostAddrs) {
    setEppInput(
        "host_create.xml",
        ImmutableMap.of("HOSTNAME", hostName, "HOSTADDRS", (hostAddrs == null) ? "" : hostAddrs));
  }

  private void setEppHostCreateInputWithIps(String hostName) {
    setEppHostCreateInput(
        hostName,
        "<host:addr ip=\"v4\">192.0.2.2</host:addr>\n"
            + "<host:addr ip=\"v4\">192.0.2.29</host:addr>\n"
            + "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
  }

  public HostCreateFlowTest() {
    setEppHostCreateInput("ns1.example.tld", null);
    clock.setTo(DateTime.parse("1999-04-03T22:00:00.0Z"));
  }

  private void doSuccessfulTest() throws Exception {
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("host_create_response.xml"));
    // Check that the host was created and persisted with a history entry.
    assertAboutHosts()
        .that(reloadResourceByForeignKey())
        .hasLastSuperordinateChange(null)
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.HOST_CREATE);
    assertNoBillingEvents();
    assertEppResourceIndexEntityFor(reloadResourceByForeignKey());
  }

  private void doSuccessfulInternalTest(String tld) throws Exception {
    setEppHostCreateInputWithIps("ns1.example.tld");
    createTld(tld);
    persistActiveDomain("example.tld");
    doSuccessfulTest();
  }

  @Test
  public void testDryRun() throws Exception {
    dryRunFlowAssertResponse(loadFile("host_create_response.xml"));
  }

  @Test
  public void testSuccess_externalNeverExisted() throws Exception {
    doSuccessfulTest();
    assertAboutHosts().that(reloadResourceByForeignKey()).hasSuperordinateDomain(null);
    assertNoDnsTasksEnqueued();
  }

  @Test
  public void testSuccess_internalNeverExisted() throws Exception {
    doSuccessfulInternalTest("tld");
    HostResource host = reloadResourceByForeignKey();
    DomainBase superordinateDomain =
        loadByForeignKey(DomainBase.class, "example.tld", clock.nowUtc()).get();
    assertAboutHosts().that(host).hasSuperordinateDomain(superordinateDomain.createVKey());
    assertThat(superordinateDomain.getSubordinateHosts()).containsExactly("ns1.example.tld");
    assertDnsTasksEnqueued("ns1.example.tld");
  }

  @Test
  public void testFailure_multipartTLDsAndInvalidHost() {
    createTlds("bar.tld", "tld");

    setEppHostCreateInputWithIps("ns1.bar.tld");
    EppException thrown = assertThrows(HostNameTooShallowException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_externalExistedButWasDeleted() throws Exception {
    persistDeletedHost(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    doSuccessfulTest();
    assertAboutHosts().that(reloadResourceByForeignKey()).hasSuperordinateDomain(null);
    assertNoDnsTasksEnqueued();
  }

  @Test
  public void testSuccess_internalExistedButWasDeleted() throws Exception {
    persistDeletedHost(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    doSuccessfulInternalTest("tld");
    HostResource host = reloadResourceByForeignKey();
    DomainBase superordinateDomain =
        loadByForeignKey(DomainBase.class, "example.tld", clock.nowUtc()).get();
    assertAboutHosts().that(host).hasSuperordinateDomain(superordinateDomain.createVKey());
    assertThat(superordinateDomain.getSubordinateHosts()).containsExactly("ns1.example.tld");
    assertDnsTasksEnqueued("ns1.example.tld");
  }

  @Test
  public void testFailure_subordinateNeedsIps() {
    setEppHostCreateInput("ns1.example.tld", null);
    createTld("tld");
    persistActiveDomain("example.tld");
    EppException thrown = assertThrows(SubordinateHostMustHaveIpException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_externalMustNotHaveIps() {
    setEppHostCreateInputWithIps("ns1.example.external");
    createTld("tld");
    persistActiveDomain("example.tld");
    EppException thrown = assertThrows(UnexpectedExternalHostIpException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_superordinateMissing() {
    setEppHostCreateInput("ns1.example.tld", null);
    createTld("tld");
    SuperordinateDomainDoesNotExistException thrown =
        assertThrows(SuperordinateDomainDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(example.tld)");
  }

  @Test
  public void testFailure_superordinateInPendingDelete() {
    setEppHostCreateInputWithIps("ns1.example.tld");
    createTld("tld");
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(35))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    clock.advanceOneMilli();
    SuperordinateDomainInPendingDeleteException thrown =
        assertThrows(SuperordinateDomainInPendingDeleteException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Superordinate domain for this hostname is in pending delete");
  }

  @Test
  public void testFailure_alreadyExists() throws Exception {
    setEppHostCreateInput("ns1.example.tld", null);
    persistActiveHost(getUniqueIdFromCommand());
    ResourceAlreadyExistsForThisClientException thrown =
        assertThrows(ResourceAlreadyExistsForThisClientException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            String.format("Object with given ID (%s) already exists", getUniqueIdFromCommand()));
  }

  @Test
  public void testFailure_resourceContention() throws Exception {
    setEppHostCreateInput("ns1.example.tld", null);
    String targetId = getUniqueIdFromCommand();
    persistResource(
        newHostResource(targetId)
            .asBuilder()
            .setPersistedCurrentSponsorClientId("NewRegistrar")
            .build());
    ResourceCreateContentionException thrown =
        assertThrows(ResourceCreateContentionException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format("Object with given ID (%s) already exists", targetId));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_nonLowerCaseHostname() {
    setEppHostCreateInput("ns1.EXAMPLE.tld", null);
    EppException thrown = assertThrows(HostNameNotLowerCaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_nonPunyCodedHostname() {
    setEppHostCreateInput("ns1.çauçalito.みんな", null);
    HostNameNotPunyCodedException thrown =
        assertThrows(HostNameNotPunyCodedException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected ns1.xn--aualito-txac.xn--q9jyb4c");
  }

  @Test
  public void testFailure_nonCanonicalHostname() {
    setEppHostCreateInput("ns1.example.tld.", null);
    EppException thrown = assertThrows(HostNameNotNormalizedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_longHostName() {
    setEppHostCreateInputWithIps("a" + Strings.repeat(".labelpart", 25) + ".tld");
    EppException thrown = assertThrows(HostNameTooLongException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_ip4AddressWithIp6Declaration() {
    setEppHostCreateInput(
        "ns1.example.tld",
        "<host:addr ip=\"v4\">192.0.2.2</host:addr>\n"
            + "<host:addr ip=\"v6\">192.0.2.29</host:addr>\n"
            + "<host:addr ip=\"v6\">1080:0:0:0:8:800:200C:417A</host:addr>");
    EppException thrown = assertThrows(IpAddressVersionMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void doFailingHostNameTest(String hostName, Class<? extends EppException> exception) {
    setEppHostCreateInputWithIps(hostName);
    EppException thrown = assertThrows(exception, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_badCharacter() {
    doFailingHostNameTest("foo bar", InvalidHostNameException.class);
  }

  @Test
  public void testFailure_tooShallowPublicSuffix() {
    doFailingHostNameTest("example.tld", HostNameTooShallowException.class);
  }

  @Test
  public void testFailure_tooShallowCcTld() {
    doFailingHostNameTest("foo.co.uk", HostNameTooShallowException.class);
  }

  @Test
  public void testFailure_barePublicSuffix() {
    doFailingHostNameTest("com", HostNameTooShallowException.class);
  }

  @Test
  public void testFailure_bareCcTld() {
    doFailingHostNameTest("co.uk", HostNameTooShallowException.class);
  }

  @Test
  public void testFailure_tooShallowNewTld() {
    doFailingHostNameTest("example.lol", HostNameTooShallowException.class);
  }

  @Test
  public void testFailure_ccTldInBailiwick() {
    createTld("co.uk");
    setEppHostCreateInputWithIps("foo.co.uk");
    EppException thrown = assertThrows(HostNameTooShallowException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-host-create");
  }
}
