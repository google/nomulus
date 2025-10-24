// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa.persistence;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.bsa.BsaTransactions.bsaQuery;
import static google.registry.bsa.BsaTransactions.bsaTransact;
import static google.registry.bsa.ReservedDomainsUtils.getAllReservedNames;
import static google.registry.bsa.ReservedDomainsUtils.isReservedDomain;
import static google.registry.bsa.persistence.Queries.batchReadUnblockables;
import static google.registry.bsa.persistence.Queries.queryBsaLabelByLabels;
import static google.registry.bsa.persistence.Queries.queryNewlyCreatedDomains;
import static google.registry.model.tld.Tld.isEnrolledWithBsa;
import static google.registry.model.tld.Tlds.getTldEntitiesOfType;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static java.util.stream.Collectors.groupingBy;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.collect.Streams;
import google.registry.bsa.BsaStringUtils;
import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.api.UnblockableDomain.Reason;
import google.registry.bsa.api.UnblockableDomainChange;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.util.BatchedStreams;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Stream;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Rechecks {@link BsaUnblockableDomain the registered/reserved domain names} in the database for
 * changes.
 *
 * <p>A registered/reserved domain name may change status in the following cases:
 *
 * <ul>
 *   <li>A domain whose reason for being unblockable is `REGISTERED` will become blockable when the
 *       domain is deregistered.
 *   <li>A domain whose reason for being unblockable is `REGISTERED` will have its reason changed to
 *       `RESERVED` if the domain is also on the reserved list.
 *   <li>A domain whose reason for being unblockable is `RESERVED` will become blockable when the
 *       domain is removed from the reserve list.
 *   <li>A domain whose reason for being unblockable is `RESERVED` will have its reason changed to
 *       `REGISTERED` if the domain is also on the reserved list.
 *   <li>A blockable domain becomes unblockable when it is added to the reserve list.
 *   <li>A blockable domain becomes unblockable when it is registered (with admin override).
 * </ul>
 *
 * <p>As a reminder, invalid domain names are not stored in the database. They change status only
 * when IDNs change in the TLDs, which rarely happens, and will be handled by dedicated procedures.
 *
 * <p>Domain blockability changes must be reported to BSA as follows:
 *
 * <ul>
 *   <li>A blockable domain becoming unblockable: an addition
 *   <li>An unblockable domain becoming blockable: a removal
 *   <li>An unblockable domain with reason change: a removal followed by an insertion.
 * </ul>
 *
 * <p>Since BSA has separate endpoints for receiving blockability changes, removals must be sent
 * before additions.
 */
public final class DomainsRefresher {

  private final DateTime prevRefreshStartTime;
  private final int transactionBatchSize;
  private final DateTime now;

  public DomainsRefresher(
      DateTime prevRefreshStartTime,
      DateTime now,
      Duration domainTxnMaxDuration,
      int transactionBatchSize) {
    this.prevRefreshStartTime = prevRefreshStartTime.minus(domainTxnMaxDuration);
    this.now = now;
    this.transactionBatchSize = transactionBatchSize;
  }

  public ImmutableList<UnblockableDomainChange> checkForBlockabilityChanges() {
    ImmutableList<UnblockableDomainChange> downgrades = refreshStaleUnblockables();
    ImmutableList<UnblockableDomainChange> upgrades = getNewUnblockables();

    ImmutableSet<String> upgradedDomains =
        upgrades.stream().map(UnblockableDomainChange::domainName).collect(toImmutableSet());
    // Upgrades are collected in its own txn after downgrades so need reconciliation. Conflicts are
    // unlikely but possible: domains may be recreated or reserved names added during the interval.
    ImmutableList<UnblockableDomainChange> trueDowngrades =
        downgrades.stream()
            .filter(c -> !upgradedDomains.contains(c.domainName()))
            .collect(toImmutableList());
    return new ImmutableList.Builder<UnblockableDomainChange>()
        .addAll(upgrades)
        .addAll(trueDowngrades)
        .build();
  }

  /**
   * Returns all changes to unblockable domains that have been reported to BSA. Please see {@link
   * UnblockableDomainChange} for types of possible changes. Note that invalid domain names are not
   * covered by this class and will be handled separately.
   *
   * <p>The number of changes are expected to be small for now. It is limited by the number of
   * domain deregistrations and the number of names added or removed from the reserved lists since
   * the previous refresh.
   */
  public ImmutableList<UnblockableDomainChange> refreshStaleUnblockables() {
    ImmutableList.Builder<UnblockableDomainChange> changes = new ImmutableList.Builder<>();
    ImmutableList<BsaUnblockableDomain> batch;
    Optional<BsaUnblockableDomain> lastRead = Optional.empty();
    do {
      batch = batchReadUnblockables(lastRead, transactionBatchSize);
      if (!batch.isEmpty()) {
        lastRead = Optional.of(batch.get(batch.size() - 1));
        changes.addAll(recheckStaleDomainsBatch(batch));
      }
    } while (batch.size() == transactionBatchSize);
    return changes.build();
  }

  ImmutableSet<UnblockableDomainChange> recheckStaleDomainsBatch(
      ImmutableList<BsaUnblockableDomain> domains) {
    ImmutableMap<String, BsaUnblockableDomain> nameToEntity =
        domains.stream().collect(toImmutableMap(BsaUnblockableDomain::domainName, d -> d));

    ImmutableSet<String> prevRegistered =
        domains.stream()
            .filter(d -> d.reason.equals(BsaUnblockableDomain.Reason.REGISTERED))
            .map(BsaUnblockableDomain::domainName)
            .collect(toImmutableSet());
    ImmutableSet<String> currRegistered =
        ImmutableSet.copyOf(
            ForeignKeyUtils.loadKeys(Domain.class, nameToEntity.keySet(), now).keySet());
    SetView<String> noLongerRegistered = Sets.difference(prevRegistered, currRegistered);
    SetView<String> newlyRegistered = Sets.difference(currRegistered, prevRegistered);

    ImmutableSet<String> prevReserved =
        domains.stream()
            .filter(d -> d.reason.equals(BsaUnblockableDomain.Reason.RESERVED))
            .map(BsaUnblockableDomain::domainName)
            .collect(toImmutableSet());
    ImmutableSet<String> currReserved =
        nameToEntity.keySet().stream()
            .filter(domain -> isReservedDomain(domain, now))
            .collect(toImmutableSet());
    SetView<String> noLongerReserved = Sets.difference(prevReserved, currReserved);

    ImmutableSet.Builder<UnblockableDomainChange> changes = new ImmutableSet.Builder<>();
    // Newly registered: reserved -> registered
    for (String domainName : newlyRegistered) {
      BsaUnblockableDomain domain = nameToEntity.get(domainName);
      UnblockableDomain unblockable =
          UnblockableDomain.of(domain.label, domain.tld, Reason.valueOf(domain.reason.name()));
      changes.add(UnblockableDomainChange.createChanged(unblockable, Reason.REGISTERED));
    }
    // No longer registered: registered -> reserved/NONE
    for (String domainName : noLongerRegistered) {
      BsaUnblockableDomain domain = nameToEntity.get(domainName);
      UnblockableDomain unblockable =
          UnblockableDomain.of(domain.label, domain.tld, Reason.valueOf(domain.reason.name()));
      changes.add(
          currReserved.contains(domainName)
              ? UnblockableDomainChange.createChanged(unblockable, Reason.RESERVED)
              : UnblockableDomainChange.createDeleted(unblockable));
    }
    // No longer reserved: reserved -> registered/None (the former duplicates with newly-registered)
    for (String domainName : noLongerReserved) {
      BsaUnblockableDomain domain = nameToEntity.get(domainName);
      UnblockableDomain unblockable =
          UnblockableDomain.of(domain.label, domain.tld, Reason.valueOf(domain.reason.name()));
      if (!currRegistered.contains(domainName)) {
        changes.add(UnblockableDomainChange.createDeleted(unblockable));
      }
    }
    return changes.build();
  }

  public ImmutableList<UnblockableDomainChange> getNewUnblockables() {
    // TODO(weiminyu): both methods below use `queryBsaLabelByLabels`. Should combine in a single
    // query.
    HashSet<String> newCreated =
        Sets.newHashSet(bsaQuery(() -> getNewlyCreatedUnblockables(prevRefreshStartTime, now)));
    // We cannot identify new reserved unblockables so must look at all of them. There are not many
    // of these.
    HashSet<String> allReserved =
        Sets.newHashSet(bsaQuery(() -> getAllReservedUnblockables(now, transactionBatchSize)));
    HashSet<String> reservedNotCreated = Sets.newHashSet(Sets.difference(allReserved, newCreated));

    ImmutableList.Builder<UnblockableDomainChange> changes = new ImmutableList.Builder<>();
    ImmutableList<BsaUnblockableDomain> batch;
    Optional<BsaUnblockableDomain> lastRead = Optional.empty();
    do {
      batch = batchReadUnblockables(lastRead, transactionBatchSize);
      if (batch.isEmpty()) {
        break;
      }
      lastRead = Optional.of(batch.get(batch.size() - 1));
      for (BsaUnblockableDomain unblockable : batch) {
        String domainName = unblockable.domainName();
        if (unblockable.reason.equals(BsaUnblockableDomain.Reason.REGISTERED)) {
          newCreated.remove(domainName);
          reservedNotCreated.remove(domainName);
        } else {
          reservedNotCreated.remove(domainName);
          if (newCreated.remove(domainName)) {
            changes.add(
                UnblockableDomainChange.createChanged(
                    unblockable.toUnblockableDomain(), Reason.REGISTERED));
          }
        }
      }
    } while (batch.size() == transactionBatchSize);

    Streams.concat(
            newCreated.stream()
                .map(name -> new UnblockableDomain(name, Reason.REGISTERED))
                .map(UnblockableDomainChange::createNew),
            reservedNotCreated.stream()
                .map(name -> new UnblockableDomain(name, Reason.RESERVED))
                .map(UnblockableDomainChange::createNew))
        .forEach(changes::add);
    return changes.build();
  }

  static ImmutableSet<String> getNewlyCreatedUnblockables(
      DateTime prevRefreshStartTime, DateTime now) {
    ImmutableSet<String> bsaEnabledTlds =
        getTldEntitiesOfType(TldType.REAL).stream()
            .filter(tld -> isEnrolledWithBsa(tld, now))
            .map(Tld::getTldStr)
            .collect(toImmutableSet());
    ImmutableSet<String> liveDomains =
        queryNewlyCreatedDomains(bsaEnabledTlds, prevRefreshStartTime, now);
    return getBlockedDomainNames(liveDomains);
  }

  static ImmutableSet<String> getAllReservedUnblockables(DateTime now, int batchSize) {
    Stream<String> allReserved = getAllReservedNames(now);
    return BatchedStreams.toBatches(allReserved, batchSize)
        .map(DomainsRefresher::getBlockedDomainNames)
        .flatMap(ImmutableSet::stream)
        .collect(toImmutableSet());
  }

  static ImmutableSet<String> getBlockedDomainNames(ImmutableCollection<String> domainNames) {
    Map<String, List<String>> labelToNames =
        domainNames.stream().collect(groupingBy(BsaStringUtils::getLabelInDomain));
    ImmutableSet<String> bsaLabels =
        queryBsaLabelByLabels(ImmutableSet.copyOf(labelToNames.keySet()))
            .map(BsaLabel::getLabel)
            .collect(toImmutableSet());
    return labelToNames.entrySet().stream()
        .filter(entry -> bsaLabels.contains(entry.getKey()))
        .map(Entry::getValue)
        .flatMap(List::stream)
        .collect(toImmutableSet());
  }

  public void applyUnblockableChanges(ImmutableList<UnblockableDomainChange> changes) {
    ImmutableMap<String, ImmutableSet<UnblockableDomainChange>> changesByType =
        ImmutableMap.copyOf(
            changes.stream()
                .collect(
                    groupingBy(
                        change -> change.isDelete() ? "remove" : "change", toImmutableSet())));
    bsaTransact(
        () -> {
          if (changesByType.containsKey("remove")) {
            tm().delete(
                    changesByType.get("remove").stream()
                        .map(c -> BsaUnblockableDomain.vKey(c.domainName()))
                        .collect(toImmutableSet()));
          }
          if (changesByType.containsKey("change")) {
            tm().putAll(
                    changesByType.get("change").stream()
                        .map(UnblockableDomainChange::newValue)
                        .map(BsaUnblockableDomain::of)
                        .collect(toImmutableSet()));
          }
        });
  }
}
