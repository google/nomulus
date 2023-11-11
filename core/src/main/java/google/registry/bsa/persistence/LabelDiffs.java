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

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.difference;
import static google.registry.flows.domain.DomainFlowUtils.isReserved;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static java.util.stream.Collectors.groupingBy;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.flogger.LazyArgs;
import com.google.common.net.InternetDomainName;
import google.registry.bsa.IdnChecker;
import google.registry.bsa.api.Label;
import google.registry.bsa.api.Label.LabelType;
import google.registry.bsa.api.NonBlockedDomain;
import google.registry.bsa.api.NonBlockedDomain.Reason;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import java.util.Map;
import java.util.stream.Stream;
import org.joda.time.DateTime;

/** Helpers for updating the BSA labels in the database according to the latest download. */
public final class LabelDiffs {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Joiner DOMAIN_JOINER = Joiner.on('.');

  private LabelDiffs() {}

  /**
   * Applies the label diffs to the database and collects matching domains that are in use
   * (registered or reserved) for reporting.
   *
   * @return A collection of domains in use
   */
  public static ImmutableList<NonBlockedDomain> applyLabelDiff(
      ImmutableList<Label> labels, IdnChecker idnChecker, DownloadSchedule schedule, DateTime now) {
    ImmutableList.Builder<NonBlockedDomain> nonBlockedDomains = new ImmutableList.Builder<>();
    ImmutableMap<LabelType, ImmutableList<Label>> labelsByType =
        ImmutableMap.copyOf(
            labels.stream().collect(groupingBy(Label::labelType, toImmutableList())));

    /**
     * New BsaLabels must be committed before unblockable domains are tallied. Otherwise we will
     * miss concurrent creations. Although we can correct them later, it is not a good user
     * experience.
     */
    // TODO(01/15/24): consider bulk-insert in native query if too slow. (Average throughput is
    // about 2.5 second per 500-label batch with current Cloud SQL settings).
    tm().transact(
            () ->
                tm().putAll(
                        labelsByType.getOrDefault(LabelType.CREATE, ImmutableList.of()).stream()
                            .map(label -> new BsaLabel(label.label(), schedule.jobCreationTime()))
                            .collect(toImmutableList())),
            TRANSACTION_REPEATABLE_READ);

    tm().transact(
            () -> {
              for (Map.Entry<LabelType, ImmutableList<Label>> entry : labelsByType.entrySet()) {
                switch (entry.getKey()) {
                  case CREATE:
                    nonBlockedDomains.addAll(
                        tallyNonBlockedDomainsForNewLabels(
                            entry.getValue(), idnChecker, schedule, now));
                    break;
                  case DELETE:
                    ImmutableSet<String> deletedLabels =
                        entry.getValue().stream().map(Label::label).collect(toImmutableSet());
                    // Delete labels in DB. Also cascade-delete BsaDomainInUse.
                    int nDeleted = Queries.deleteBsaLabelByLabels(deletedLabels);
                    if (nDeleted != deletedLabels.size()) {
                      logger.atSevere().log(
                          "Only found %s entities among the %s labels: [%s]",
                          nDeleted, deletedLabels.size(), deletedLabels);
                    }
                    break;
                  case NEW_ORDER_ASSOCIATION:
                    ImmutableSet<String> affectedLabels =
                        entry.getValue().stream().map(Label::label).collect(toImmutableSet());
                    ImmutableSet<String> labelsInDb =
                        Queries.queryBsaLabelByLabels(affectedLabels)
                            .map(BsaLabel::getLabel)
                            .collect(toImmutableSet());
                    verify(
                        labelsInDb.size() == affectedLabels.size(),
                        "Missing labels in DB: %s",
                        LazyArgs.lazy(() -> difference(affectedLabels, labelsInDb)));

                    // Reuse registered and reserved names that are already computed.
                    Queries.queryBsaDomainInUseByLabels(affectedLabels)
                        .map(BsaDomainInUse::toNonBlockedDomain)
                        .forEach(nonBlockedDomains::add);

                    for (Label label : entry.getValue()) {
                      getInvalidTldsForLabel(label, idnChecker)
                          .map(tld -> NonBlockedDomain.of(label.label(), tld, Reason.INVALID))
                          .forEach(nonBlockedDomains::add);
                    }
                    break;
                }
              }
            },
            TRANSACTION_REPEATABLE_READ);
    logger.atInfo().log("Processed %s of labels.", labels.size());
    return nonBlockedDomains.build();
  }

  static ImmutableList<NonBlockedDomain> tallyNonBlockedDomainsForNewLabels(
      ImmutableList<Label> labels, IdnChecker idnChecker, DownloadSchedule schedule, DateTime now) {
    ImmutableList.Builder<NonBlockedDomain> nonBlockedDomains = new ImmutableList.Builder<>();

    for (Label label : labels) {
      getInvalidTldsForLabel(label, idnChecker)
          .map(tld -> NonBlockedDomain.of(label.label(), tld, Reason.INVALID))
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
      nonBlockedDomains.add(NonBlockedDomain.of(domain, Reason.REGISTERED));
      tm().put(BsaDomainInUse.of(domain, BsaDomainInUse.Reason.REGISTERED));
    }

    ImmutableSet<String> reservedDomainNames =
        difference(validDomainNames, registeredDomainNames).stream()
            .filter(LabelDiffs::isReservedDomain)
            .collect(toImmutableSet());
    for (String domain : reservedDomainNames) {
      nonBlockedDomains.add(NonBlockedDomain.of(domain, Reason.RESERVED));
      tm().put(BsaDomainInUse.of(domain, BsaDomainInUse.Reason.RESERVED));
    }
    return nonBlockedDomains.build();
  }

  static Stream<String> validDomainNamesForLabel(Label label, IdnChecker idnChecker) {
    return getValidTldsForLabel(label, idnChecker)
        .map(tld -> DOMAIN_JOINER.join(label.label(), tld));
  }

  static Stream<String> getInvalidTldsForLabel(Label label, IdnChecker idnChecker) {
    return idnChecker.getForbiddingTlds(label.idnTables()).stream().map(Tld::getTldStr);
  }

  static Stream<String> getValidTldsForLabel(Label label, IdnChecker idnChecker) {
    return idnChecker.getSupportingTlds(label.idnTables()).stream().map(Tld::getTldStr);
  }

  static boolean isReservedDomain(String domain) {
    return isReserved(InternetDomainName.from(domain), /* isSunrise= */ false);
  }
}
