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

package google.registry.model.registry.label;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.persistence.VKey;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ReservedList} DAO that does dual-write and dual-read against Datastore and Cloud SQL. It
 * still uses Datastore as the primary storage and suppresses any exception thrown by Cloud SQL.
 *
 * <p>TODO(b/160993806): Delete this DAO and switch to use the SQL only DAO after migrating to Cloud
 * SQL.
 */
public class ReservedListDualWriteDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ReservedListDualWriteDao() {}

  /** Persist a new reserved list to Cloud SQL. */
  public static void save(ReservedList reservedList) {
    ofyTm().transact(() -> ofyTm().saveNewOrUpdate(reservedList));
    try {
      logger.atInfo().log("Saving reserved list %s to Cloud SQL", reservedList.getName());
      ReservedListSqlDao.save(reservedList);
      logger.atInfo().log(
          "Saved reserved list %s with %d entries to Cloud SQL",
          reservedList.getName(), reservedList.getReservedListEntries().size());
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("Error saving the reserved list to Cloud SQL.");
    }
  }

  /**
   * Returns the most recent revision of the {@link ReservedList} with the specified name, if it
   * exists.
   */
  public static Optional<ReservedList> getLatestRevision(String reservedListName) {
    Optional<ReservedList> maybeDatastoreList =
        ofyTm()
            .maybeLoad(
                VKey.createOfy(
                    ReservedList.class,
                    Key.create(getCrossTldKey(), ReservedList.class, reservedListName)));
    try {
      // Also load the list from Cloud SQL, compare the two lists, and log if different.
      maybeDatastoreList.ifPresent(ReservedListDualWriteDao::loadAndCompareCloudSqlList);
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("Error comparing reserved lists.");
    }
    return maybeDatastoreList;
  }

  private static void loadAndCompareCloudSqlList(ReservedList datastoreList) {
    Optional<ReservedList> maybeCloudSqlList =
        ReservedListSqlDao.getLatestRevision(datastoreList.getName());
    if (maybeCloudSqlList.isPresent()) {
      Map<String, ReservedListEntry> datastoreLabelsToReservations =
          datastoreList.reservedListMap.entrySet().parallelStream()
              .collect(
                  toImmutableMap(
                      Map.Entry::getKey,
                      entry ->
                          ReservedListEntry.create(
                              entry.getKey(),
                              entry.getValue().reservationType,
                              entry.getValue().comment)));

      ReservedList cloudSqlList = maybeCloudSqlList.get();
      MapDifference<String, ReservedListEntry> diff =
          Maps.difference(datastoreLabelsToReservations, cloudSqlList.reservedListMap);
      if (!diff.areEqual()) {
        if (diff.entriesDiffering().size() > 10) {
          logger.atWarning().log(
              String.format(
                  "Unequal reserved lists detected, Cloud SQL list with revision"
                      + " id %d has %d different records than the current"
                      + " Datastore list.",
                  cloudSqlList.getRevisionId(), diff.entriesDiffering().size()));
        } else {
          StringBuilder diffMessage = new StringBuilder("Unequal reserved lists detected:\n");
          diff.entriesDiffering().entrySet().stream()
              .forEach(
                  entry -> {
                    String label = entry.getKey();
                    ValueDifference<ReservedListEntry> valueDiff = entry.getValue();
                    diffMessage.append(
                        String.format(
                            "Domain label %s has entry %s in Datastore and entry"
                                + " %s in Cloud SQL.\n",
                            label, valueDiff.leftValue(), valueDiff.rightValue()));
                  });
          logger.atWarning().log(diffMessage.toString());
        }
      }
    } else {
      logger.atWarning().log("Reserved list in Cloud SQL is empty.");
    }
  }
}
