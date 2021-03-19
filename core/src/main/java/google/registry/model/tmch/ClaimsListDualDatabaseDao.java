// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.tmch;

import static google.registry.config.RegistryConfig.getDomainLabelListCacheDuration;
import static google.registry.model.CacheUtils.tryMemoizeWithExpiration;
import static google.registry.model.DatabaseMigrationUtils.isDatastore;
import static google.registry.model.DatabaseMigrationUtils.suppressExceptionUnlessInTest;
import static google.registry.model.common.DatabaseTransitionSchedule.TransitionId.CLAIMS_LIST;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import google.registry.util.NonFinalForTesting;
import java.util.Optional;

/**
 * DAO for {@link ClaimsListShard} objects that handles the branching paths for SQL and Datastore.
 *
 * <p>For write actions, this class will perform the action against the primary database then, after
 * * that success or failure, against the secondary database. If the secondary database fails, an
 * error is logged (but not thrown).
 *
 * <p>For read actions, we will log if the primary and secondary databases * have different values
 * (or if the retrieval from the second database fails).
 */
public class ClaimsListDualDatabaseDao {

  /** In-memory cache for claims list. */
  @NonFinalForTesting
  private static Supplier<ClaimsListShard> claimsListCache =
      tryMemoizeWithExpiration(
          getDomainLabelListCacheDuration(), ClaimsListDualDatabaseDao::getUncached);

  /**
   * Saves the given {@link ClaimsListShard} to both the primary and secondary databases, logging
   * and skipping errors in the secondary DB.
   */
  public static void save(ClaimsListShard claimsList) {
    if (isDatastore(CLAIMS_LIST)) {
      claimsList.saveToDatastore();
      suppressExceptionUnlessInTest(
          () -> ClaimsListSqlDao.save(claimsList), "Error saving ClaimsList to SQL.");
    } else {
      ClaimsListSqlDao.save(claimsList);
      suppressExceptionUnlessInTest(
          claimsList::saveToDatastore, "Error saving ClaimsListShard to Datastore.");
    }
  }

  /** Returns the most recent revision of the {@link ClaimsListShard}, from cache. */
  public static ClaimsListShard get() {
    return claimsListCache.get();
  }

  /** Retrieves and compares the latest revision from the databases. */
  private static ClaimsListShard getUncached() {
    Optional<ClaimsListShard> primaryResult;
    if (isDatastore(CLAIMS_LIST)) {
      primaryResult = ClaimsListShard.getFromDatastore();
      suppressExceptionUnlessInTest(
          () -> {
            Optional<ClaimsListShard> secondaryResult = ClaimsListSqlDao.get();
            compareClaimsLists(primaryResult, secondaryResult);
          },
          "Error loading ClaimsList from SQL.");
    } else {
      primaryResult = ClaimsListSqlDao.get();
      suppressExceptionUnlessInTest(
          () -> {
            Optional<ClaimsListShard> secondaryResult = ClaimsListShard.getFromDatastore();
            compareClaimsLists(primaryResult, secondaryResult);
          },
          "Error loading ClaimsListShard from Datastore.");
    }
    return primaryResult.orElse(ClaimsListShard.create(START_OF_TIME, ImmutableMap.of()));
  }

  private static void compareClaimsLists(
      Optional<ClaimsListShard> maybePrimary, Optional<ClaimsListShard> maybeSecondary) {
    if (maybePrimary.isPresent() && !maybeSecondary.isPresent()) {
      throw new IllegalStateException("Claims list found in primary DB but not in secondary DB.");
    }
    if (!maybePrimary.isPresent() && maybeSecondary.isPresent()) {
      throw new IllegalStateException("Claims list found in secondary DB but not in primary DB.");
    }
    if (!maybePrimary.isPresent()) {
      return;
    }
    ClaimsListShard primary = maybePrimary.get();
    ClaimsListShard secondary = maybeSecondary.get();
    MapDifference<String, String> diff =
        Maps.difference(primary.labelsToKeys, secondary.getLabelsToKeys());
    if (!diff.areEqual()) {
      if (diff.entriesDiffering().size()
              + diff.entriesOnlyOnRight().size()
              + diff.entriesOnlyOnLeft().size()
          > 10) {
        throw new IllegalStateException(
            String.format(
                "Unequal claims lists detected, secondary list with revision id %d has %d"
                    + " different records than the current primary list.",
                secondary.getRevisionId(), diff.entriesDiffering().size()));
      } else {
        StringBuilder diffMessage = new StringBuilder("Unequal claims lists detected:\n");
        diff.entriesDiffering()
            .forEach(
                (label, valueDiff) ->
                    diffMessage.append(
                        String.format(
                            "Domain label %s has key %s in the primary DB and key %s "
                                + "in the secondary DB.\n",
                            label, valueDiff.leftValue(), valueDiff.rightValue())));
        diff.entriesOnlyOnLeft()
            .forEach(
                (label, valueDiff) ->
                    diffMessage.append(
                        String.format(
                            "Domain label %s with key %s only appears in the primary DB.\n",
                            label, valueDiff)));
        diff.entriesOnlyOnRight()
            .forEach(
                (label, valueDiff) ->
                    diffMessage.append(
                        String.format(
                            "Domain label %s with key %s only appears in the secondary DB.\n",
                            label, valueDiff)));
        throw new IllegalStateException(diffMessage.toString());
      }
    }
  }

  private ClaimsListDualDatabaseDao() {}
}
