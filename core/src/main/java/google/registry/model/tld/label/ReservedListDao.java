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

package google.registry.model.tld.label;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.flogger.FluentLogger;
import java.util.Optional;

/** A {@link ReservedList} DAO for Cloud SQL. */
public class ReservedListDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ReservedListDao() {}

  /** Persist a new reserved list to Cloud SQL. */
  public static void save(ReservedList reservedList) {
    checkArgumentNotNull(reservedList, "Must specify reservedList");
    logger.atInfo().log("Saving reserved list %s to Cloud SQL.", reservedList.getName());
    jpaTm().transact(() -> jpaTm().insert(reservedList));
    logger.atInfo().log(
        "Saved reserved list %s with %d entries to Cloud SQL.",
        reservedList.getName(), reservedList.getReservedListEntries().size());
  }

  /** Deletes a reserved list from Cloud SQL. */
  public static void delete(ReservedList reservedList) {
    jpaTm().transact(() -> jpaTm().delete(reservedList));
  }

  /**
   * Returns the most recent revision of the {@link ReservedList} with the specified name, if it
   * exists.
   */
  public static Optional<ReservedList> getLatestRevision(String reservedListName) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .query(
                        "FROM ReservedList WHERE revisionId IN "
                            + "(SELECT MAX(revisionId) FROM ReservedList WHERE name = :name)",
                        ReservedList.class)
                    .setParameter("name", reservedListName)
                    .getResultStream()
                    .findFirst());
  }

  /**
   * Returns whether the reserved list of the given name exists.
   *
   * <p>This means that at least one reserved list revision must exist for the given name.
   */
  public static boolean checkExists(String reservedListName) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                        .query("SELECT 1 FROM ReservedList WHERE name = :name", Integer.class)
                        .setParameter("name", reservedListName)
                        .setMaxResults(1)
                        .getResultList()
                        .size()
                    > 0);
  }
}
