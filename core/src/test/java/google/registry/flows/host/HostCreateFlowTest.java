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
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.newHost;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HostSubject.assertAboutHosts;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.IpAddressVersionMismatchException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.exceptions.ResourceAlreadyExistsForThisClientException;
import google.registry.flows.exceptions.ResourceCreateContentionException;
import google.registry.flows.host.HostCreateFlow.SubordinateHostMustHaveIpException;
import google.registry.flows.host.HostCreateFlow.UnexpectedExternalHostIpException;
import google.registry.flows.host.HostFlowUtils.BadHostNameCharacterException;
import google.registry.flows.host.HostFlowUtils.HostNameNotLowerCaseException;
import google.registry.flows.host.HostFlowUtils.HostNameNotNormalizedException;
import google.registry.flows.host.HostFlowUtils.HostNameNotPunyCodedException;
import google.registry.flows.host.HostFlowUtils.HostNameTooLongException;
import google.registry.flows.host.HostFlowUtils.HostNameTooShallowException;
import google.registry.flows.host.HostFlowUtils.InvalidHostNameException;
import google.registry.flows.host.HostFlowUtils.LoopbackIpNotValidForHostException;
import google.registry.flows.host.HostFlowUtils.SuperordinateDomainDoesNotExistException;
import google.registry.flows.host.HostFlowUtils.SuperordinateDomainInPendingDeleteException;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.DatabaseHelper;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HostCreateFlow}. */
class HostCreateFlowTest extends ResourceFlowTestCase<HostCreateFlow, Host> {

  private void setEppHostCreateInput(String hostName, String hostAddrs) {
    setEppInput(
        "host_create.xml",
        ImmutableMap.of("HOSTNAME", hostName, "HOSTADDRS", (hostAddrs == null) ? "" : hostAddrs));
  }

  private void setEppHostCreateInputWithIps(String hostName) {
    setEppHostCreateInput(
        hostName,
        """
        <host:addr ip="v4">192.0.2.2</host:addr>
        <host:addr ip="v4">192.0.2.29</host:addr>
        <host:addr ip="v6">1080:0:0:0:8:800:200C:417A</host:addr>\
        """);
  }

  HostCreateFlowTest() {
    setEppHostCreateInput("ns1.example.tld", null);
    clock.setTo(DateTime.parse("1999-04-03T22:00:00.0Z"));
  }

  private void doSuccessfulTest() throws Exception {
    doSuccessfulTest("host_create_response.xml", ImmutableMap.of());
  }

  private void doSuccessfulTest(String responseFile, ImmutableMap<String, String> substitutions)
      throws Exception {
    clock.advanceOneMilli();
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile(responseFile, substitutions));
    Host host = reloadResourceByForeignKey();
    // Check that the host was created and persisted with a history entry.
    assertAboutHosts()
        .that(host)
        .hasLastSuperordinateChange(null)
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.HOST_CREATE);
    assertNoBillingEvents();
    assertLastHistoryContainsResource(host);
  }

  private void doSuccessfulInternalTest(String tld) throws Exception {
    setEppHostCreateInputWithIps("ns1.example.tld");
    createTld(tld);
    persistActiveDomain("example.tld");
    doSuccessfulTest();
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testDryRun() throws Exception {
    dryRunFlowAssertResponse(loadFile("host_create_response.xml"));
  }

  @Test
  void testSuccess_externalNeverExisted() throws Exception {
    doSuccessfulTest();
    assertAboutHosts().that(reloadResourceByForeignKey()).hasSuperordinateDomain(null);
    assertNoDnsRequests();
  }

  @Test
  void testSuccess_internalNeverExisted() throws Exception {
    doSuccessfulInternalTest("tld");
    Host host = reloadResourceByForeignKey();
    Domain superordinateDomain =
        ForeignKeyUtils.loadResource(Domain.class, "example.tld", clock.nowUtc()).get();
    assertAboutHosts().that(host).hasSuperordinateDomain(superordinateDomain.createVKey());
    assertThat(superordinateDomain.getSubordinateHosts()).containsExactly("ns1.example.tld");
    assertHostDnsRequests("ns1.example.tld");
  }

  @Test
  void testSuccess_tldWithHyphenOn3And4() throws Exception {
    setEppHostCreateInput("ns1.example.zz--main-2262", null);
    doSuccessfulTest(
        "host_create_response_wildcard.xml",
        ImmutableMap.of("HOSTNAME", "ns1.example.zz--main-2262"));
  }

  @Test
  void testFailure_domainWithHyphenOn3And4() throws Exception {
    setEppHostCreateInput("ns1.zz--main-2262.tld", null);
    EppException thrown = assertThrows(InvalidHostNameException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_hostnameWithHyphenOn3And4() throws Exception {
    setEppHostCreateInput("zz--ns1.domain.tld", null);
    EppException thrown = assertThrows(InvalidHostNameException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_multipartTLDsAndInvalidHost() {
    createTlds("bar.tld", "tld");

    setEppHostCreateInputWithIps("ns1.bar.tld");
    EppException thrown = assertThrows(HostNameTooShallowException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_externalExistedButWasDeleted() throws Exception {
    persistDeletedHost(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    doSuccessfulTest();
    assertAboutHosts().that(reloadResourceByForeignKey()).hasSuperordinateDomain(null);
    assertNoDnsRequests();
  }

  @Test
  void testSuccess_internalExistedButWasDeleted() throws Exception {
    persistDeletedHost(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    doSuccessfulInternalTest("tld");
    Host host = reloadResourceByForeignKey();
    Domain superordinateDomain =
        ForeignKeyUtils.loadResource(Domain.class, "example.tld", clock.nowUtc()).get();
    assertAboutHosts().that(host).hasSuperordinateDomain(superordinateDomain.createVKey());
    assertThat(superordinateDomain.getSubordinateHosts()).containsExactly("ns1.example.tld");
    assertHostDnsRequests("ns1.example.tld");
  }

  @Test
  void testFailure_subordinateNeedsIps() {
    setEppHostCreateInput("ns1.example.tld", null);
    createTld("tld");
    persistActiveDomain("example.tld");
    EppException thrown = assertThrows(SubordinateHostMustHaveIpException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_externalMustNotHaveIps() {
    setEppHostCreateInputWithIps("ns1.example.external");
    createTld("tld");
    persistActiveDomain("example.tld");
    EppException thrown = assertThrows(UnexpectedExternalHostIpException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_superordinateMissing() {
    setEppHostCreateInput("ns1.example.tld", null);
    createTld("tld");
    SuperordinateDomainDoesNotExistException thrown =
        assertThrows(SuperordinateDomainDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(example.tld)");
  }

  @Test
  void testFailure_superordinateInPendingDelete() {
    setEppHostCreateInputWithIps("ns1.example.tld");
    createTld("tld");
    persistResource(
        DatabaseHelper.newDomain("example.tld")
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
  void testFailure_alreadyExists() throws Exception {
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
  void testFailure_resourceContention() throws Exception {
    setEppHostCreateInput("ns1.example.tld", null);
    String targetId = getUniqueIdFromCommand();
    persistResource(
        newHost(targetId)
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
            .build());
    ResourceCreateContentionException thrown =
        assertThrows(ResourceCreateContentionException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format("Object with given ID (%s) already exists", targetId));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_nonLowerCaseHostname() {
    setEppHostCreateInput("ns1.EXAMPLE.tld", null);
    EppException thrown = assertThrows(HostNameNotLowerCaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_nonPunyCodedHostname() {
    setEppHostCreateInput("ns1.çauçalito.みんな", null);
    HostNameNotPunyCodedException thrown =
        assertThrows(HostNameNotPunyCodedException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected ns1.xn--aualito-txac.xn--q9jyb4c");
  }

  @Test
  void testFailure_nonCanonicalHostname() {
    setEppHostCreateInput("ns1.example.tld.", null);
    EppException thrown = assertThrows(HostNameNotNormalizedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_longHostName() {
    setEppHostCreateInputWithIps("a" + ".labelpart".repeat(25) + ".tld");
    EppException thrown = assertThrows(HostNameTooLongException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_ip4AddressWithIp6Declaration() {
    setEppHostCreateInput(
        "ns1.example.tld",
        """
        <host:addr ip="v4">192.0.2.2</host:addr>
        <host:addr ip="v6">192.0.2.29</host:addr>
        <host:addr ip="v6">1080:0:0:0:8:800:200C:417A</host:addr>\
        """);
    EppException thrown = assertThrows(IpAddressVersionMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void doFailingHostNameTest(String hostName, Class<? extends EppException> exception) {
    setEppHostCreateInputWithIps(hostName);
    EppException thrown = assertThrows(exception, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_badCharacter() {
    doFailingHostNameTest("foo bar", BadHostNameCharacterException.class);
  }

  @Test
  void testFailure_tooShallowPublicSuffix() {
    doFailingHostNameTest("example.tld", HostNameTooShallowException.class);
  }

  @Test
  void testFailure_tooShallowCcTld() {
    doFailingHostNameTest("foo.co.uk", HostNameTooShallowException.class);
  }

  @Test
  void testFailure_barePublicSuffix() {
    doFailingHostNameTest("com", HostNameTooShallowException.class);
  }

  @Test
  void testFailure_bareCcTld() {
    doFailingHostNameTest("co.uk", HostNameTooShallowException.class);
  }

  @Test
  void testFailure_tooShallowNewTld() {
    doFailingHostNameTest("example.lol", HostNameTooShallowException.class);
  }

  @Test
  void testFailure_ccTldInBailiwick() {
    createTld("co.uk");
    setEppHostCreateInputWithIps("foo.co.uk");
    EppException thrown = assertThrows(HostNameTooShallowException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_localhostInetAddress_ipv4() {
    createTld("tld");
    persistActiveDomain("example.tld");
    setEppHostCreateInput("ns1.example.tld", "<host:addr ip=\"v4\">127.0.0.1</host:addr>");
    assertAboutEppExceptions()
        .that(assertThrows(LoopbackIpNotValidForHostException.class, this::runFlow))
        .marshalsToXml();
  }

  @Test
  void testFailure_localhostInetAddress_ipv6() {
    createTld("tld");
    persistActiveDomain("example.tld");
    setEppHostCreateInput("ns1.example.tld", "<host:addr ip=\"v6\">::1</host:addr>");
    assertAboutEppExceptions()
        .that(assertThrows(LoopbackIpNotValidForHostException.class, this::runFlow))
        .marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-host-create");
  }
}
