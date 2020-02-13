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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.DomainBase;
import google.registry.model.registry.RegistryLockDao;
import google.registry.model.reporting.HistoryEntry;
import google.registry.schema.domain.RegistryLock;
import google.registry.tools.CommandWithRemoteApi;
import google.registry.tools.ConfirmingCommand;
import google.registry.util.Clock;
import google.registry.util.StringGenerator;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.DateTime;

/**
 * Scrap tool to backfill {@link RegistryLock}s for domains previously locked.
 *
 * <p>This will save new objects for all existing domains that are locked but don't have any
 * corresponding lock objects already in the database.
 */
@Parameters(
    separators = " =",
    commandDescription =
        "Backfills RegistryLock objects for specified domain resource IDs that are locked but don't"
            + " already have a corresponding RegistryLock object.")
public class BackfillRegistryLocksCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int VERIFICATION_CODE_LENGTH = 32;

  @Parameter(
      names = {"--domain_roids"},
      description = "Comma-separated list of domain roids to check")
  protected List<String> roids;

  // Inject here so that we can create the command automatically for tests
  @Inject Clock clock;

  @Inject
  @Config("registryAdminClientId")
  String registryAdminClientId;

  @Inject
  @Named("base58StringGenerator")
  StringGenerator stringGenerator;

  private DateTime now;
  private ImmutableList<DomainBase> lockedDomains;

  @Override
  protected String prompt() {
    checkArgument(
        roids != null && !roids.isEmpty(), "Must provide non-empty domain_roids argument");
    now = clock.nowUtc();
    lockedDomains = getLockedDomainsWithoutLocks();
    ImmutableList<String> lockedDomainNames =
        lockedDomains.stream()
            .map(DomainBase::getFullyQualifiedDomainName)
            .collect(toImmutableList());
    return String.format(
        "Locked domains for which there does not exist a RegistryLock object: %s",
        lockedDomainNames);
  }

  @Override
  protected String execute() {
    ImmutableSet.Builder<DomainBase> failedDomainsBuilder = new ImmutableSet.Builder<>();
    for (DomainBase domainBase : lockedDomains) {
      try {
        RegistryLockDao.save(
            new RegistryLock.Builder()
                .isSuperuser(true)
                .setRegistrarId(registryAdminClientId)
                .setRepoId(domainBase.getRepoId())
                .setDomainName(domainBase.getFullyQualifiedDomainName())
                .setLockCompletionTimestamp(getLockCompletionTimestamp(domainBase, now))
                .setVerificationCode(stringGenerator.createString(VERIFICATION_CODE_LENGTH))
                .build());
      } catch (Throwable t) {
        Throwable rootCause = Throwables.getRootCause(t);
        logger.atSevere().withCause(rootCause).log(
            "Error when creating lock object for domain %s.",
            domainBase.getFullyQualifiedDomainName());
        failedDomainsBuilder.add(domainBase);
      }
    }
    ImmutableSet<DomainBase> failedDomains = failedDomainsBuilder.build();
    if (failedDomains.isEmpty()) {
      return String.format(
          "Successfully created lock objects for %d domains.", lockedDomains.size());
    } else {
      return String.format(
          "Successfully created lock objects for %d domains. We failed to create locks "
              + "for the following domains: %s",
          lockedDomains.size() - failedDomains.size(), lockedDomains);
    }
  }

  private DateTime getLockCompletionTimestamp(DomainBase domainBase, DateTime now) {
    // Best-effort, if a domain was URS-locked we should use that time
    // If we can't find that, return now.
    return ofy().load().type(HistoryEntry.class).ancestor(domainBase).list().stream()
        // sort by modification time descending so we get the most recent one if it was locked twice
        .sorted(Comparator.comparing(HistoryEntry::getModificationTime).reversed())
        .filter(entry -> entry.getReason().equals("Uniform Rapid Suspension"))
        .findFirst()
        .map(HistoryEntry::getModificationTime)
        .orElse(now);
  }

  private ImmutableList<DomainBase> getLockedDomainsWithoutLocks() {
    return ImmutableList.copyOf(
        ofy().load()
            .keys(
                roids.stream()
                    .map(roid -> Key.create(DomainBase.class, roid))
                    .collect(toImmutableList()))
            .values().stream()
            .filter(d -> d.getDeletionTime().isAfter(now))
            .filter(d -> d.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES))
            .filter(d -> !RegistryLockDao.getMostRecentByRepoId(d.getRepoId()).isPresent())
            .collect(toImmutableList()));
  }
}
