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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.StatusValue.SERVER_DELETE_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_TRANSFER_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistNewRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.DomainBase;
import google.registry.model.registrar.Registrar.Type;
import google.registry.model.registry.RegistryLockDao;
import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.schema.domain.RegistryLock;
import google.registry.schema.domain.RegistryLock.Action;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.tools.server.ToolsTestData;
import google.registry.util.Retrier;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link UnlockDomainCommand}. */
public class UnlockDomainCommandTest extends EppToolCommandTestCase<UnlockDomainCommand> {

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

  @Before
  public void before() {
    eppVerifier.expectSuperuser();
    persistNewRegistrar("adminreg", "Admin Registrar", Type.REAL, 693L);
    command.registryAdminClientId = "adminreg";
    command.retrier = new Retrier(new FakeSleeper(new FakeClock()), 3);
  }

  private static DomainBase persistLockedDomain(String domainName) {
    return persistResource(
        newDomainBase(domainName)
            .asBuilder()
            .addStatusValues(
                ImmutableSet.of(
                    SERVER_DELETE_PROHIBITED, SERVER_TRANSFER_PROHIBITED, SERVER_UPDATE_PROHIBITED))
            .build());
  }

  @Test
  public void testSuccess_sendsCorrectEppXml() throws Exception {
    persistLockedDomain("example.tld");
    runCommandForced("--client=NewRegistrar", "example.tld");
    eppVerifier.verifySent("domain_unlock.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testSuccess_writesUnlockObject() throws Exception {
    when(eppVerifier.getConnection().sendPostRequest(any(), any(), any(), any()))
        .thenReturn(ToolsTestData.loadFile("epp_success_example.xml"));

    DomainBase domainBase = persistLockedDomain("example.tld");
    runCommandForced("--client=NewRegistrar", "example.tld");
    eppVerifier.verifySent("domain_unlock.xml", ImmutableMap.of("DOMAIN", "example.tld"));

    RegistryLock lock =
        RegistryLockDao.getMostRecentByRepoId(domainBase.getRepoId())
            .orElseThrow(() -> new IllegalStateException("There should be a lock object saved"));

    assertThat(lock.isVerified()).isTrue();
    assertThat(lock.getAction()).isEqualTo(Action.UNLOCK);
    assertThat(lock.getDomainName()).isEqualTo("example.tld");
    assertThat(lock.isSuperuser()).isTrue();
    assertThat(lock.getRegistrarId()).isEqualTo("NewRegistrar");
  }

  @Test
  public void testSuccess_partiallyUpdatesStatuses() throws Exception {
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .addStatusValues(ImmutableSet.of(SERVER_DELETE_PROHIBITED, SERVER_UPDATE_PROHIBITED))
            .build());
    runCommandForced("--client=NewRegistrar", "example.tld");
    eppVerifier.verifySent("domain_unlock_partial_statuses.xml");
  }

  @Test
  public void testSuccess_manyDomains() throws Exception {
    when(eppVerifier.getConnection().sendPostRequest(any(), any(), any(), any()))
        .thenReturn(ToolsTestData.loadFile("epp_success_example.xml"));

    // Create 26 domains -- one more than the number of entity groups allowed in a transaction (in
    // case that was going to be the failure point).
    List<String> domains = new ArrayList<>();
    for (int n = 0; n < 26; n++) {
      String domain = String.format("domain%d.tld", n);
      persistLockedDomain(domain);
      domains.add(domain);
    }
    runCommandForced(
        ImmutableList.<String>builder().add("--client=NewRegistrar").addAll(domains).build());
    for (String domain : domains) {
      eppVerifier.verifySent("domain_unlock.xml", ImmutableMap.of("DOMAIN", domain));
    }
    assertThat(
            RegistryLockDao.getByRegistrarId("NewRegistrar").stream()
                .map(RegistryLock::getDomainName)
                .collect(toImmutableList()))
        .isEqualTo(domains);
  }

  @Test
  public void testFailure_domainDoesntExist() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--client=NewRegistrar", "missing.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Domain 'missing.tld' does not exist or is deleted");
  }

  @Test
  public void testSuccess_alreadyUnlockedDomain_performsNoAction() throws Exception {
    persistActiveDomain("example.tld");
    runCommandForced("--client=NewRegistrar", "example.tld");
    assertThat(RegistryLockDao.getByRegistrarId("NewRegistrar")).isEmpty();
  }

  @Test
  public void testSuccess_defaultsToAdminRegistrar_ifUnspecified() throws Exception {
    persistLockedDomain("example.tld");
    runCommandForced("example.tld");
    eppVerifier
        .expectClientId("adminreg")
        .verifySent("domain_unlock.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  public void testFailure_duplicateDomainsAreSpecified() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--client=NewRegistrar", "dupe.tld", "dupe.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Duplicate domain arguments found: 'dupe.tld'");
  }

  @Test
  public void testFailure_doesNotWriteUnlockObject_onEppFailure() throws Exception {
    // If the EPP fails due to some unexpected reason, don't write an unlock object
    when(eppVerifier.getConnection().sendPostRequest(any(), any(), any(), any()))
        .thenReturn(ToolsTestData.loadFile("epp_failure_example.xml"));
    persistLockedDomain("example.tld");
    runCommandForced("--client=NewRegistrar", "example.tld");
    eppVerifier.verifySent("domain_unlock.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    assertThat(RegistryLockDao.getByRegistrarId("NewRegistrar")).isEmpty();
  }
}
