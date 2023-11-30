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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.bsa.ReservedDomainsUtils.isReservedDomain;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import google.registry.bsa.api.NonBlockedDomain;
import google.registry.bsa.api.NonBlockedDomain.Reason;
import google.registry.bsa.api.UnblockableDomainChange;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.util.DateTimeUtils;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * Rechecks {@link BsaDomainInUse the registered/reserved domain names} in the database for changes.
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
public class DomainsRefresher {

  private static final Joiner DOMAIN_JOINER = Joiner.on('.');

  static final int QUERY_BATCH_SIZE = 500;

  private final DownloadSchedule schedule;

  DomainsRefresher(DownloadSchedule schedule) {
    this.schedule = schedule;
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
    ImmutableList<BsaDomainInUse> batch;
    Optional<BsaDomainInUse> lastRead = Optional.empty();
    do {
      batch = batchReadUnblockables(lastRead, QUERY_BATCH_SIZE);
      if (!batch.isEmpty()) {
        lastRead = Optional.of(batch.get(batch.size() - 1));
        changes.addAll(recheckStaleDomainsBatch(batch, schedule.jobCreationTime()));
      }
    } while (batch.size() == QUERY_BATCH_SIZE);
    return changes.build();
  }

  public void detectNewUnblockables() {}

  ImmutableList<UnblockableDomainChange> recheckStaleDomainsBatch(
      ImmutableList<BsaDomainInUse> domains, DateTime now) {
    ImmutableMap<String, BsaDomainInUse> registered =
        domains.stream()
            .filter(d -> d.reason.equals(BsaDomainInUse.Reason.REGISTERED))
            .collect(toImmutableMap(d -> DOMAIN_JOINER.join(d.label, d.tld), d -> d));
    ImmutableSet<String> stillRegistered =
        registered.isEmpty()
            ? ImmutableSet.of()
            : ImmutableSet.copyOf(
                ForeignKeyUtils.load(Domain.class, registered.keySet(), now).keySet());
    SetView<String> noLongerRegistered = Sets.difference(registered.keySet(), stillRegistered);

    ImmutableMap<String, BsaDomainInUse> reserved =
        domains.stream()
            .filter(d -> d.reason.equals(BsaDomainInUse.Reason.RESERVED))
            .collect(toImmutableMap(d -> DOMAIN_JOINER.join(d.label, d.tld), d -> d));
    ImmutableSet<String> stillReserved =
        reserved.keySet().stream()
            .filter(domain -> isReservedDomain(domain, now))
            .collect(toImmutableSet());
    SetView<String> noLongerReserved = Sets.difference(reserved.keySet(), stillReserved);

    ImmutableList.Builder<UnblockableDomainChange> changes = new ImmutableList.Builder<>();
    for (String domainName : noLongerReserved) {
      BsaDomainInUse domain = reserved.get(domainName);
      changes.add(
          UnblockableDomainChange.of(
              NonBlockedDomain.of(domain.label, domain.tld, Reason.valueOf(domain.reason.name())),
              registered.containsKey(domainName)
                  ? Optional.of(Reason.REGISTERED)
                  : Optional.empty()));
    }
    for (String domainName : noLongerRegistered) {
      BsaDomainInUse domain = registered.get(domainName);
      changes.add(
          UnblockableDomainChange.of(
              NonBlockedDomain.of(domain.label, domain.tld, Reason.valueOf(domain.reason.name())),
              reserved.containsKey(domainName) ? Optional.of(Reason.RESERVED) : Optional.empty()));
    }
    return changes.build();
  }

  void detectNewUnblockableDomains() {}

  private ImmutableSet<String> getDomainsCreatedSince(RefreshSchedule schedule) {
    ImmutableSet<String> candidates =
        ImmutableSet.copyOf(
            tm().getEntityManager()
                .createQuery(
                    "SELECT domainName FROM Domain WHERE creationTime >= :time ", String.class)
                .setParameter(
                    "time", schedule.prevJobCreationTime().orElse(DateTimeUtils.START_OF_TIME))
                .getResultList());
    return ImmutableSet.copyOf(
        ForeignKeyUtils.load(Domain.class, candidates, schedule.jobCreationTime()).keySet());
  }

  private static ImmutableList<BsaDomainInUse> batchReadUnblockables(
      Optional<BsaDomainInUse> exclusiveStartFrom, int batchSize) {
    return ImmutableList.copyOf(
        tm().getEntityManager()
            .createQuery(
                "FROM BsaDomainInUse d WHERE d.label > :label OR (d.label = :label AND d.tld >"
                    + " :tld) ORDER BY d.tld, d.label ")
            .setParameter("label", exclusiveStartFrom.map(d -> d.label).orElse(""))
            .setParameter("tld", exclusiveStartFrom.map(d -> d.tld).orElse(""))
            .setMaxResults(batchSize)
            .getResultList());
  }
}
