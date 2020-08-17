// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.javascrap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistNewRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.SqlHelper.getMostRecentRegistryLockByRepoId;
import static google.registry.testing.SqlHelper.getMostRecentVerifiedRegistryLockByRepoId;
import static google.registry.testing.SqlHelper.getRegistryLocksByRegistrarId;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.truth.Truth8;
import google.registry.model.domain.DomainBase;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.HistoryEntry;
import google.registry.schema.domain.RegistryLock;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.tools.CommandTestCase;
import google.registry.util.StringGenerator.Alphabets;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BackfillRegistryLocksCommand}. */
class BackfillRegistryLocksCommandTest extends CommandTestCase<BackfillRegistryLocksCommand> {

  @BeforeEach
  void beforeEach() {
    persistNewRegistrar("adminreg", "Admin Registrar", Registrar.Type.REAL, 693L);
    createTld("tld");
    command.registryAdminClientId = "adminreg";
    command.clock = fakeClock;
    command.stringGenerator = new DeterministicStringGenerator(Alphabets.BASE_58);
  }

  @Test
  void testSimpleBackfill() throws Exception {
    DomainBase domain = persistLockedDomain("example.tld");
    Truth8.assertThat(getMostRecentRegistryLockByRepoId(domain.getRepoId())).isEmpty();

    runCommandForced("--domain_roids", domain.getRepoId());

    Optional<RegistryLock> lockOptional = getMostRecentRegistryLockByRepoId(domain.getRepoId());
    Truth8.assertThat(lockOptional).isPresent();
    Truth8.assertThat(lockOptional.get().getLockCompletionTimestamp()).isPresent();
  }

  @Test
  void testBackfill_onlyLockedDomains() throws Exception {
    DomainBase neverLockedDomain = persistActiveDomain("neverlocked.tld");
    DomainBase previouslyLockedDomain = persistLockedDomain("unlocked.tld");
    persistResource(previouslyLockedDomain.asBuilder().setStatusValues(ImmutableSet.of()).build());
    DomainBase lockedDomain = persistLockedDomain("locked.tld");

    runCommandForced(
        "--domain_roids",
        String.format(
            "%s,%s,%s",
            neverLockedDomain.getRepoId(),
            previouslyLockedDomain.getRepoId(),
            lockedDomain.getRepoId()));

    ImmutableList<RegistryLock> locks = getRegistryLocksByRegistrarId("adminreg");
    assertThat(locks).hasSize(1);
    assertThat(Iterables.getOnlyElement(locks).getDomainName()).isEqualTo("locked.tld");
  }

  @Test
  void testBackfill_skipsDeletedDomains() throws Exception {
    DomainBase domain = persistDeletedDomain("example.tld", fakeClock.nowUtc());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    fakeClock.advanceBy(Duration.standardSeconds(1));
    runCommandForced("--domain_roids", domain.getRepoId());
    Truth8.assertThat(getMostRecentRegistryLockByRepoId(domain.getRepoId())).isEmpty();
  }

  @Test
  void testBackfill_skipsDomains_ifLockAlreadyExists() throws Exception {
    DomainBase domain = persistLockedDomain("example.tld");

    RegistryLock previousLock =
        saveRegistryLock(
            new RegistryLock.Builder()
                .isSuperuser(true)
                .setRegistrarId("adminreg")
                .setRepoId(domain.getRepoId())
                .setDomainName(domain.getDomainName())
                .setLockCompletionTimestamp(fakeClock.nowUtc())
                .setVerificationCode(command.stringGenerator.createString(32))
                .build());

    fakeClock.advanceBy(Duration.standardDays(1));
    runCommandForced("--domain_roids", domain.getRepoId());

    assertThat(
            getMostRecentRegistryLockByRepoId(domain.getRepoId())
                .get()
                .getLockCompletionTimestamp())
        .isEqualTo(previousLock.getLockCompletionTimestamp());
  }

  @Test
  void testBackfill_usesUrsTime_ifExists() throws Exception {
    DateTime ursTime = fakeClock.nowUtc();
    DomainBase ursDomain = persistLockedDomain("urs.tld");
    HistoryEntry historyEntry =
        new HistoryEntry.Builder()
            .setBySuperuser(true)
            .setClientId("adminreg")
            .setModificationTime(ursTime)
            .setParent(ursDomain)
            .setReason("Uniform Rapid Suspension")
            .setType(HistoryEntry.Type.DOMAIN_UPDATE)
            .setRequestedByRegistrar(false)
            .build();
    persistResource(historyEntry);
    DomainBase nonUrsDomain = persistLockedDomain("nonurs.tld");

    fakeClock.advanceBy(Duration.standardDays(10));
    runCommandForced(
        "--domain_roids", String.format("%s,%s", ursDomain.getRepoId(), nonUrsDomain.getRepoId()));

    RegistryLock ursLock = getMostRecentVerifiedRegistryLockByRepoId(ursDomain.getRepoId()).get();
    assertThat(ursLock.getLockCompletionTimestamp().get()).isEqualTo(ursTime);
    RegistryLock nonUrsLock =
        getMostRecentVerifiedRegistryLockByRepoId(nonUrsDomain.getRepoId()).get();
    assertThat(nonUrsLock.getLockCompletionTimestamp().get()).isEqualTo(fakeClock.nowUtc());
  }

  @Test
  void testFailure_mustProvideDomainRoids() {
    assertThat(assertThrows(IllegalArgumentException.class, () -> runCommandForced()))
        .hasMessageThat()
        .isEqualTo("Must provide non-empty domain_roids argument");
  }

  private static DomainBase persistLockedDomain(String domainName) {
    DomainBase domain = persistActiveDomain(domainName);
    return persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
  }
}
