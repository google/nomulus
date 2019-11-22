// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.tld;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.schema.tld.PremiumListCache.cachePremiumEntries;
import static google.registry.schema.tld.PremiumListCache.cachePremiumLists;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.util.concurrent.UncheckedExecutionException;
import google.registry.model.registry.Registry;
import google.registry.schema.tld.PremiumListCache.RevisionIdAndLabel;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.joda.money.Money;

/** Data access object class for {@link PremiumList}. */
public class PremiumListDao {

  /** Returns the most recent revision of the PremiumList with the specified name, if it exists. */
  static Optional<PremiumList> getLatestRevision(String premiumListName) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery(
                        "SELECT pl FROM PremiumList pl WHERE pl.name = :name ORDER BY"
                            + " pl.revisionId DESC",
                        PremiumList.class)
                    .setParameter("name", premiumListName)
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst());
  }

  static Optional<BigDecimal> getPriceForLabel(RevisionIdAndLabel revisionIdAndLabel) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery(
                        "SELECT pe.price FROM PremiumEntry pe WHERE pe.revisionId = :revisionId"
                            + " AND pe.domainLabel = :label",
                        BigDecimal.class)
                    .setParameter("revisionId", revisionIdAndLabel.revisionId())
                    .setParameter("label", revisionIdAndLabel.label())
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst());
  }

  /** Returns the most recent revision of the PremiumList with the specified name, from cache. */
  public static Optional<PremiumList> getLatestRevisionCached(String premiumListName) {
    try {
      return cachePremiumLists.get(premiumListName);
    } catch (ExecutionException e) {
      throw new UncheckedExecutionException(
          "Could not retrieve premium list named " + premiumListName, e);
    }
  }

  /** Persist a new premium list to Cloud SQL. */
  public static void saveNew(PremiumList premiumList) {
    jpaTm()
        .transact(
            () -> {
              checkArgument(
                  !checkExists(premiumList.getName()),
                  "A premium list of this name already exists: %s.",
                  premiumList.getName());
              jpaTm().getEntityManager().persist(premiumList);
            });
  }

  /**
   * Returns whether the premium list of the given name exists.
   *
   * <p>This means that at least one premium list revision must exist for the given name.
   */
  public static boolean checkExists(String premiumListName) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                        .getEntityManager()
                        .createQuery("SELECT 1 FROM PremiumList WHERE name = :name", Integer.class)
                        .setParameter("name", premiumListName)
                        .setMaxResults(1)
                        .getResultList()
                        .size()
                    > 0);
  }

  /**
   * Returns the premium price for the specified label and registry, or absent if the label is not
   * premium.
   */
  public static Optional<Money> getPremiumPrice(String label, Registry registry) {
    // If the registry has no configured premium list, then no labels are premium.
    if (registry.getPremiumList() == null) {
      return Optional.empty();
    }
    String premiumListName = registry.getPremiumList().getName();
    PremiumList premiumList =
        getLatestRevisionCached(premiumListName)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        String.format("Could not load premium list '%s'", premiumListName)));
    return getPremiumPrice(label, premiumList);
  }

  private static Optional<Money> getPremiumPrice(String label, PremiumList premiumList) {
    // Consult the bloom filter and immediately return if the label definitely isn't premium.
    if (!premiumList.getBloomFilter().mightContain(label)) {
      return Optional.empty();
    }
    RevisionIdAndLabel revisionIdAndLabel =
        RevisionIdAndLabel.create(premiumList.getRevisionId(), label);
    try {
      Optional<BigDecimal> price = cachePremiumEntries.get(revisionIdAndLabel);
      return price.map(p -> Money.of(premiumList.getCurrency(), p));
    } catch (InvalidCacheLoadException | ExecutionException e) {
      throw new RuntimeException(
          String.format(
              "Could not load premium entry %s for list %s",
              revisionIdAndLabel, premiumList.getName()),
          e);
    }
  }

  private PremiumListDao() {}
}
