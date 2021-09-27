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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.RegistryLock;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.model.tld.RegistryLockDao;
import google.registry.persistence.VKey;
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

  private ImmutableList<DomainBase> lockedDomains;

  @Override
  protected String prompt() {
    checkArgument(
        roids != null && !roids.isEmpty(), "Must provide non-empty domain_roids argument");
    lockedDomains =
        jpaTm().transact(() -> getLockedDomainsWithoutLocks(jpaTm().getTransactionTime()));
    ImmutableList<String> lockedDomainNames =
        lockedDomains.stream().map(DomainBase::getDomainName).collect(toImmutableList());
    return String.format(
        "Locked domains for which there does not exist a RegistryLock object: %s",
        lockedDomainNames);
  }

  @Override
  protected String execute() {
    ImmutableSet.Builder<String> failedDomainsBuilder = new ImmutableSet.Builder<>();
    jpaTm()
        .transact(
            () -> {
              for (DomainBase domainBase : lockedDomains) {
                try {
                  RegistryLockDao.save(
                      new RegistryLock.Builder()
                          .isSuperuser(true)
                          .setRegistrarId(registryAdminClientId)
                          .setRepoId(domainBase.getRepoId())
                          .setDomainName(domainBase.getDomainName())
                          .setLockCompletionTime(
                              getLockCompletionTimestamp(domainBase, jpaTm().getTransactionTime()))
                          .setVerificationCode(
                              stringGenerator.createString(VERIFICATION_CODE_LENGTH))
                          .build());
                } catch (Throwable t) {
                  logger.atSevere().withCause(t).log(
                      "Error when creating lock object for domain '%s'.",
                      domainBase.getDomainName());
                  failedDomainsBuilder.add(domainBase.getDomainName());
                }
              }
            });
    ImmutableSet<String> failedDomains = failedDomainsBuilder.build();
    if (failedDomains.isEmpty()) {
      return String.format(
          "Successfully created lock objects for %d domains.", lockedDomains.size());
    } else {
      return String.format(
          "Successfully created lock objects for %d domains. We failed to create locks "
              + "for the following domains: %s",
          lockedDomains.size() - failedDomains.size(), failedDomains);
    }
  }

  private DateTime getLockCompletionTimestamp(DomainBase domainBase, DateTime now) {
    // Best-effort, if a domain was URS-locked we should use that time
    // If we can't find that, return now.
    return HistoryEntryDao.loadHistoryObjectsForResource(domainBase.createVKey()).stream()
        // sort by modification time descending so we get the most recent one if it was locked twice
        .sorted(Comparator.comparing(HistoryEntry::getModificationTime).reversed())
        .filter(entry -> "Uniform Rapid Suspension".equals(entry.getReason()))
        .findFirst()
        .map(HistoryEntry::getModificationTime)
        .orElse(now);
  }

  private ImmutableList<DomainBase> getLockedDomainsWithoutLocks(DateTime now) {
    ImmutableList<VKey<DomainBase>> domainKeys =
        roids.stream().map(roid -> VKey.create(DomainBase.class, roid)).collect(toImmutableList());
    ImmutableCollection<DomainBase> domains =
        transactIfJpaTm(() -> tm().loadByKeys(domainKeys)).values();
    return domains.stream()
        .filter(d -> d.getDeletionTime().isAfter(now))
        .filter(d -> d.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES))
        .filter(d -> !RegistryLockDao.getMostRecentByRepoId(d.getRepoId()).isPresent())
        .collect(toImmutableList());
  }
}
