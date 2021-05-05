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

import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import google.registry.model.DatabaseMigrationUtils;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ReservedList} DAO that does dual-write and dual-read against Datastore and Cloud SQL.
 *
 * <p>TODO(b/160993806): Delete this DAO and switch to use the SQL only DAO after migrating to Cloud
 * SQL.
 */
public class ReservedListDualDatabaseDao {

  private ReservedListDualDatabaseDao() {}

  /** Persist a new reserved list to the database. */
  public static void save(ReservedList reservedList) {
      ReservedListSqlDao.save(reservedList);
      DatabaseMigrationUtils.suppressExceptionUnlessInTest(
          () -> ReservedListDatastoreDao.save(reservedList),
          "Error saving the reserved list to Datastore.");
  }

  /** Delete a reserved list from both databases. */
  public static void delete(ReservedList reservedList) {
      ReservedListSqlDao.delete(reservedList);
      DatabaseMigrationUtils.suppressExceptionUnlessInTest(
          () -> ReservedListDatastoreDao.delete(reservedList),
          "Error deleting the reserved list from Datastore.");
  }

  /**
   * Returns the most recent revision of the {@link ReservedList} with the specified name, if it
   * exists.
   */
  public static Optional<ReservedList> getLatestRevision(String reservedListName) {
    Optional<ReservedList> maybePrimaryList =
        ReservedListSqlDao.getLatestRevision(reservedListName);
    DatabaseMigrationUtils.suppressExceptionUnlessInTest(
        () -> maybePrimaryList.ifPresent(primaryList -> loadAndCompare(primaryList)),
        "Error comparing reserved lists.");
    return maybePrimaryList;
  }

  private static void loadAndCompare(ReservedList primaryList) {
    Optional<ReservedList> maybeSecondaryList =
        ReservedListDatastoreDao.getLatestRevision(primaryList.getName());
    if (!maybeSecondaryList.isPresent()) {
      throw new IllegalStateException("Reserved list in Datastore is empty.");
    }
    Map<String, ReservedListEntry> labelsToReservations =
        primaryList.reservedListMap.entrySet().parallelStream()
            .collect(
                toImmutableMap(
                    Map.Entry::getKey,
                    entry ->
                        ReservedListEntry.create(
                            entry.getKey(),
                            entry.getValue().reservationType,
                            entry.getValue().comment)));

    ReservedList secondaryList = maybeSecondaryList.get();
    MapDifference<String, ReservedListEntry> diff =
        Maps.difference(labelsToReservations, secondaryList.reservedListMap);
      if (!diff.areEqual()) {
        if (diff.entriesDiffering().size() > 10) {
        throw new IllegalStateException(
            String.format(
                "Unequal reserved lists detected, Datastore list with revision"
                    + " id %d has %d different records than the current"
                    + " Cloud SQL list.",
                secondaryList.getRevisionId(), diff.entriesDiffering().size()));
      }
          StringBuilder diffMessage = new StringBuilder("Unequal reserved lists detected:\n");
      diff.entriesDiffering().entrySet().stream()
          .forEach(
              entry -> {
                String label = entry.getKey();
                ValueDifference<ReservedListEntry> valueDiff = entry.getValue();
                diffMessage.append(
                    String.format(
                        "Domain label %s has entry %s in Cloud SQL and entry"
                            + " %s in the Datastore.\n",
                        label, valueDiff.leftValue(), valueDiff.rightValue()));
              });
      diff.entriesOnlyOnLeft().entrySet().stream()
          .forEach(
              entry -> {
                String label = entry.getKey();
                diffMessage.append(
                    String.format(
                        "Domain label %s has entry in Cloud SQL, but not in Datastore.\n", label));
              });
      diff.entriesOnlyOnRight().entrySet().stream()
          .forEach(
              entry -> {
                String label = entry.getKey();
                diffMessage.append(
                    String.format(
                        "Domain label %s has entry in Datastore, but not in Cloud SQL.\n", label));
              });
          throw new IllegalStateException(diffMessage.toString());
      }
  }
}
