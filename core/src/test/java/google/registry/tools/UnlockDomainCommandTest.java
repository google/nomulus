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
import static google.registry.model.eppcommon.StatusValue.SERVER_DELETE_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistNewRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.SqlHelper.getMostRecentRegistryLockByRepoId;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.batch.AsyncTaskEnqueuerTest;
import google.registry.model.domain.DomainBase;
import google.registry.model.registrar.Registrar.Type;
import google.registry.schema.domain.RegistryLock;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.StringGenerator.Alphabets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link UnlockDomainCommand}.
 */
public class UnlockDomainCommandTest extends CommandTestCase<UnlockDomainCommand> {

  @Before
  public void before() {
    persistNewRegistrar("adminreg", "Admin Registrar", Type.REAL, 693L);
    createTld("tld");
    command.registryAdminClientId = "adminreg";
    command.domainLockUtils =
        new DomainLockUtils(
            new DeterministicStringGenerator(Alphabets.BASE_58),
            "adminreg",
            AsyncTaskEnqueuerTest.createForTesting(
                mock(AppEngineServiceUtils.class), fakeClock, Duration.ZERO));
  }

  private DomainBase persistLockedDomain(String domainName, String registrarId) {
    DomainBase domain = persistResource(newDomainBase(domainName));
    RegistryLock lock =
        command.domainLockUtils.saveNewRegistryLockRequest(domainName, registrarId, null, true);
    command.domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true);
    return reloadResource(domain);
  }

  @Test
  public void testSuccess_unlocksDomain() throws Exception {
    DomainBase domain = persistLockedDomain("example.tld", "TheRegistrar");
    runCommandForced("--client=TheRegistrar", "example.tld");
    assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  public void testSuccess_partiallyUpdatesStatuses() throws Exception {
    DomainBase domain = persistLockedDomain("example.tld", "TheRegistrar");
    domain =
        persistResource(
            domain
                .asBuilder()
                .setStatusValues(
                    ImmutableSet.of(SERVER_DELETE_PROHIBITED, SERVER_UPDATE_PROHIBITED))
                .build());
    runCommandForced("--client=TheRegistrar", "example.tld");
    assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  public void testSuccess_manyDomains() throws Exception {
    // Create 26 domains -- one more than the number of entity groups allowed in a transaction (in
    // case that was going to be the failure point).
    List<DomainBase> domains = new ArrayList<>();
    for (int n = 0; n < 26; n++) {
      String domain = String.format("domain%d.tld", n);
      domains.add(persistLockedDomain(domain, "TheRegistrar"));
    }
    runCommandForced(
        ImmutableList.<String>builder()
            .add("--client=TheRegistrar")
            .addAll(domains.stream().map(DomainBase::getDomainName).collect(Collectors.toList()))
            .build());
    for (DomainBase domain : domains) {
      assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
    }
  }

  @Test
  public void testFailure_domainDoesntExist() throws Exception {
    runCommandForced("--client=NewRegistrar", "missing.tld");
    assertInStdout("Failed domains:\n[missing.tld (Unknown domain missing.tld)]");
  }

  @Test
  public void testSuccess_alreadyUnlockedDomain_staysUnlocked() throws Exception {
    DomainBase domain = persistActiveDomain("example.tld");
    runCommandForced("--client=TheRegistrar", "example.tld");
    assertThat(reloadResource(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  public void testSuccess_defaultsToAdminRegistrar_ifUnspecified() throws Exception {
    DomainBase domain = persistLockedDomain("example.tld", "TheRegistrar");
    runCommandForced("example.tld");
    assertThat(getMostRecentRegistryLockByRepoId(domain.getRepoId()).get().getRegistrarId())
        .isEqualTo("adminreg");
  }

  @Test
  public void testFailure_duplicateDomainsAreSpecified() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--client=TheRegistrar", "dupe.tld", "dupe.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Duplicate domain arguments found: 'dupe.tld'");
  }
}
