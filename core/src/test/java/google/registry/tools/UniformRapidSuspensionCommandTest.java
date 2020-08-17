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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UniformRapidSuspensionCommand}. */
class UniformRapidSuspensionCommandTest
    extends EppToolCommandTestCase<UniformRapidSuspensionCommand> {

  private HostResource ns1;
  private HostResource ns2;
  private HostResource urs1;
  private HostResource urs2;

  @BeforeEach
  void beforeEach() {
    // Since the command's history client ID must be CharlestonRoad, resave TheRegistrar that way.
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setClientId("CharlestonRoad").build());
    ns1 = persistActiveHost("ns1.example.com");
    ns2 = persistActiveHost("ns2.example.com");
    urs1 = persistActiveHost("urs1.example.com");
    urs2 = persistActiveHost("urs2.example.com");
  }

  private void persistDomainWithHosts(HostResource... hosts) {
    ImmutableSet.Builder<VKey<HostResource>> hostRefs = new ImmutableSet.Builder<>();
    for (HostResource host : hosts) {
      hostRefs.add(host.createVKey());
    }
    persistResource(newDomainBase("evil.tld").asBuilder()
        .setNameservers(hostRefs.build())
        .setDsData(ImmutableSet.of(
            DelegationSignerData.create(1, 2, 3, new HexBinaryAdapter().unmarshal("dead")),
            DelegationSignerData.create(4, 5, 6, new HexBinaryAdapter().unmarshal("beef"))))
        .build());
  }

  @Test
  void testCommand_addsLocksReplacesHostsAndDsDataPrintsUndo() throws Exception {
    persistDomainWithHosts(ns1, ns2);
    runCommandForced(
        "--domain_name=evil.tld",
        "--hosts=urs1.example.com,urs2.example.com",
        "--dsdata=1 1 1 abcd");
    eppVerifier
        .expectClientId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension.xml");
    assertInStdout("uniform_rapid_suspension --undo");
    assertInStdout("--domain_name evil.tld");
    assertInStdout("--hosts ns1.example.com,ns2.example.com");
    assertInStdout("--dsdata 1 2 3 DEAD,4 5 6 BEEF");
    assertNotInStdout("--locks_to_preserve");
    assertNotInStdout("--restore_client_hold");
  }

  @Test
  void testCommand_respectsExistingHost() throws Exception {
    persistDomainWithHosts(urs2, ns1);
    runCommandForced("--domain_name=evil.tld", "--hosts=urs1.example.com,urs2.example.com");
    eppVerifier
        .expectClientId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_existing_host.xml");
    assertInStdout("uniform_rapid_suspension --undo ");
    assertInStdout("--domain_name evil.tld");
    assertInStdout("--hosts ns1.example.com,urs2.example.com");
    assertNotInStdout("--locks_to_preserve");
  }

  @Test
  void testCommand_generatesUndoForUndelegatedDomain() throws Exception {
    persistActiveDomain("evil.tld");
    runCommandForced("--domain_name=evil.tld", "--hosts=urs1.example.com,urs2.example.com");
    eppVerifier.verifySentAny();
    assertInStdout("uniform_rapid_suspension --undo");
    assertInStdout("--domain_name evil.tld");
    assertNotInStdout("--locks_to_preserve");
  }

  @Test
  void testCommand_generatesUndoWithLocksToPreserve() throws Exception {
    persistResource(
        newDomainBase("evil.tld").asBuilder()
          .addStatusValue(StatusValue.SERVER_DELETE_PROHIBITED)
          .build());
    runCommandForced("--domain_name=evil.tld");
    eppVerifier.verifySentAny();
    assertInStdout("uniform_rapid_suspension --undo");
    assertInStdout("--domain_name evil.tld");
    assertInStdout("--locks_to_preserve serverDeleteProhibited");
  }

  @Test
  void testCommand_removeClientHold() throws Exception {
    persistResource(
        newDomainBase("evil.tld")
            .asBuilder()
            .addStatusValue(StatusValue.CLIENT_HOLD)
            .addNameserver(ns1.createVKey())
            .addNameserver(ns2.createVKey())
            .build());
    runCommandForced(
        "--domain_name=evil.tld",
        "--hosts=urs1.example.com,urs2.example.com",
        "--dsdata=1 1 1 abcd");
    eppVerifier
        .expectClientId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_with_client_hold.xml");
    assertInStdout("uniform_rapid_suspension --undo");
    assertInStdout("--domain_name evil.tld");
    assertInStdout("--hosts ns1.example.com,ns2.example.com");
    assertInStdout("--restore_client_hold");
  }

  @Test
  void testUndo_removesLocksReplacesHostsAndDsData() throws Exception {
    persistDomainWithHosts(urs1, urs2);
    runCommandForced(
        "--domain_name=evil.tld", "--undo", "--hosts=ns1.example.com,ns2.example.com");
    eppVerifier
        .expectClientId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_undo.xml");
    assertNotInStdout("--undo");  // Undo shouldn't print a new undo command.
  }

  @Test
  void testUndo_respectsLocksToPreserveFlag() throws Exception {
    persistDomainWithHosts(urs1, urs2);
    runCommandForced(
        "--domain_name=evil.tld",
        "--undo",
        "--locks_to_preserve=serverDeleteProhibited",
        "--hosts=ns1.example.com,ns2.example.com");
    eppVerifier
        .expectClientId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_undo_preserve.xml");
    assertNotInStdout("--undo");  // Undo shouldn't print a new undo command.
  }

  @Test
  void testUndo_restoresClientHolds() throws Exception {
    persistDomainWithHosts(urs1, urs2);
    runCommandForced(
        "--domain_name=evil.tld",
        "--undo",
        "--hosts=ns1.example.com,ns2.example.com",
        "--restore_client_hold");
    eppVerifier
        .expectClientId("CharlestonRoad")
        .expectSuperuser()
        .verifySent("uniform_rapid_suspension_undo_client_hold.xml");
    assertNotInStdout("--undo"); // Undo shouldn't print a new undo command.
  }

  @Test
  void testFailure_locksToPreserveWithoutUndo() {
    persistActiveDomain("evil.tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--domain_name=evil.tld", "--locks_to_preserve=serverDeleteProhibited"));
    assertThat(thrown).hasMessageThat().contains("--undo");
  }

  @Test
  void testFailure_domainNameRequired() {
    persistActiveDomain("evil.tld");
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommandForced("--hosts=urs1.example.com,urs2.example.com"));
    assertThat(thrown).hasMessageThat().contains("--domain_name");
  }

  @Test
  void testFailure_extraFieldInDsData() {
    persistActiveDomain("evil.tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--domain_name=evil.tld", "--dsdata=1 1 1 abc 1"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("dsRecord 1 1 1 abc 1 should have 4 parts, but has 5");
  }

  @Test
  void testFailure_missingFieldInDsData() {
    persistActiveDomain("evil.tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--domain_name=evil.tld", "--dsdata=1 1 1"));
    assertThat(thrown).hasMessageThat().contains("dsRecord 1 1 1 should have 4 parts, but has 3");
  }

  @Test
  void testFailure_malformedDsData() {
    persistActiveDomain("evil.tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--domain_name=evil.tld", "--dsdata=1,2,3"));
    assertThat(thrown).hasMessageThat().contains("dsRecord 1 should have 4 parts, but has 1");
  }
}
