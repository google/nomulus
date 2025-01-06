// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.difference;
import static google.registry.bsa.ReservedDomainsUtils.isReservedDomain;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static java.util.stream.Collectors.groupingBy;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.flogger.LazyArgs;
import google.registry.bsa.IdnChecker;
import google.registry.bsa.api.BlockLabel;
import google.registry.bsa.api.BlockLabel.LabelType;
import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.api.UnblockableDomain.Reason;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import java.util.Map;
import java.util.stream.Stream;
import org.joda.time.DateTime;

/** Applies the BSA label diffs from the latest BSA download. */
public final class LabelDiffUpdates {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Joiner DOMAIN_JOINER = Joiner.on('.');

  private LabelDiffUpdates() {}

  /**
   * Applies the label diffs to the database and collects matching domains that are in use
   * (registered or reserved) for reporting.
   *
   * @return A collection of domains in use
   */
  public static ImmutableList<UnblockableDomain> applyLabelDiff(
      ImmutableList<BlockLabel> labels,
      IdnChecker idnChecker,
      DownloadSchedule schedule,
      DateTime now) {
    ImmutableList.Builder<UnblockableDomain> nonBlockedDomains = new ImmutableList.Builder<>();
    ImmutableMap<LabelType, ImmutableList<BlockLabel>> labelsByType =
        ImmutableMap.copyOf(
            labels.stream().collect(groupingBy(BlockLabel::labelType, toImmutableList())));

    tm().transact(
            TRANSACTION_REPEATABLE_READ,
            () -> {
              for (Map.Entry<LabelType, ImmutableList<BlockLabel>> entry :
                  labelsByType.entrySet()) {
                switch (entry.getKey()) {
                  case CREATE -> {
                    // With current Cloud SQL, label upsert throughput is about 200/second. If
                    // better performance is needed, consider bulk insert in native SQL.
                    tm().putAll(
                            entry.getValue().stream()
                                .filter(label -> isValidInAtLeastOneTld(label, idnChecker))
                                .map(
                                    label ->
                                        new BsaLabel(label.label(), schedule.jobCreationTime()))
                                .collect(toImmutableList()));
                    // May not find all unblockables due to race condition: DomainCreateFlow uses
                    // cached BsaLabels. Eventually will be consistent.
                    nonBlockedDomains.addAll(
                        tallyUnblockableDomainsForNewLabels(entry.getValue(), idnChecker, now));
                  }
                  case DELETE -> {
                    ImmutableSet<String> deletedLabels =
                        entry.getValue().stream()
                            .filter(label -> isValidInAtLeastOneTld(label, idnChecker))
                            .map(BlockLabel::label)
                            .collect(toImmutableSet());
                    // Delete labels in DB. Also cascade-delete BsaUnblockableDomain.
                    int nDeleted = Queries.deleteBsaLabelByLabels(deletedLabels);
                    if (nDeleted != deletedLabels.size()) {
                      logger.atSevere().log(
                          "Only found %s entities among the %s labels: [%s]",
                          nDeleted, deletedLabels.size(), deletedLabels);
                    }
                  }
                  case NEW_ORDER_ASSOCIATION -> {
                    ImmutableSet<String> affectedLabels =
                        entry.getValue().stream()
                            .filter(label -> isValidInAtLeastOneTld(label, idnChecker))
                            .map(BlockLabel::label)
                            .collect(toImmutableSet());
                    ImmutableSet<String> labelsInDb =
                        Queries.queryBsaLabelByLabels(affectedLabels)
                            .map(BsaLabel::getLabel)
                            .collect(toImmutableSet());
                    verify(
                        labelsInDb.size() == affectedLabels.size(),
                        "Missing labels in DB: %s",
                        LazyArgs.lazy(() -> difference(affectedLabels, labelsInDb)));

                    // Reuse registered and reserved names that are already computed.
                    Queries.queryBsaUnblockableDomainByLabels(affectedLabels)
                        .map(BsaUnblockableDomain::toUnblockableDomain)
                        .forEach(nonBlockedDomains::add);
                    for (BlockLabel label : entry.getValue()) {
                      getInvalidTldsForLabel(label, idnChecker)
                          .map(tld -> UnblockableDomain.of(label.label(), tld, Reason.INVALID))
                          .forEach(nonBlockedDomains::add);
                    }
                  }
                }
              }
            });
    logger.atInfo().log("Processed %s of labels.", labels.size());
    return nonBlockedDomains.build();
  }

  static ImmutableList<UnblockableDomain> tallyUnblockableDomainsForNewLabels(
      ImmutableList<BlockLabel> labels, IdnChecker idnChecker, DateTime now) {
    ImmutableList.Builder<UnblockableDomain> nonBlockedDomains = new ImmutableList.Builder<>();

    for (BlockLabel label : labels) {
      getInvalidTldsForLabel(label, idnChecker)
          .map(tld -> UnblockableDomain.of(label.label(), tld, Reason.INVALID))
          .forEach(nonBlockedDomains::add);
    }

    ImmutableSet<String> validDomainNames =
        labels.stream()
            .map(label -> validDomainNamesForLabel(label, idnChecker))
            .flatMap(x -> x)
            .collect(toImmutableSet());
    ImmutableSet<String> registeredDomainNames =
        ImmutableSet.copyOf(ForeignKeyUtils.load(Domain.class, validDomainNames, now).keySet());
    for (String domain : registeredDomainNames) {
      nonBlockedDomains.add(new UnblockableDomain(domain, Reason.REGISTERED));
      tm().put(BsaUnblockableDomain.of(domain, BsaUnblockableDomain.Reason.REGISTERED));
    }

    ImmutableSet<String> reservedDomainNames =
        difference(validDomainNames, registeredDomainNames).stream()
            .filter(domain -> isReservedDomain(domain, now))
            .collect(toImmutableSet());
    for (String domain : reservedDomainNames) {
      nonBlockedDomains.add(new UnblockableDomain(domain, Reason.RESERVED));
      tm().put(BsaUnblockableDomain.of(domain, BsaUnblockableDomain.Reason.RESERVED));
    }
    return nonBlockedDomains.build();
  }

  static Stream<String> validDomainNamesForLabel(BlockLabel label, IdnChecker idnChecker) {
    return getValidTldsForLabel(label, idnChecker)
        .map(tld -> DOMAIN_JOINER.join(label.label(), tld));
  }

  static Stream<String> getInvalidTldsForLabel(BlockLabel label, IdnChecker idnChecker) {
    return idnChecker.getForbiddingTlds(label.idnTables()).stream().map(Tld::getTldStr);
  }

  static Stream<String> getValidTldsForLabel(BlockLabel label, IdnChecker idnChecker) {
    return idnChecker.getSupportingTlds(label.idnTables()).stream().map(Tld::getTldStr);
  }

  static boolean isValidInAtLeastOneTld(BlockLabel label, IdnChecker idnChecker) {
    return getValidTldsForLabel(label, idnChecker).findAny().isPresent();
  }
}
