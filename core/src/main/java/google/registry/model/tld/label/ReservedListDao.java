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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.flogger.FluentLogger;
import java.util.Optional;

/** A {@link ReservedList} DAO for Cloud SQL. */
public class ReservedListDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ReservedListDao() {}

  /**
   * Persists a new reserved list to Cloud SQL and returns the persisted entity.
   *
   * <p>Note that the input parameter is untouched. Use the returned object if metadata fields like
   * {@code revisionId} are needed.
   */
  public static ReservedList save(ReservedList reservedList) {
    checkArgumentNotNull(reservedList, "Must specify reservedList");
    logger.atInfo().log("Saving reserved list %s to Cloud SQL.", reservedList.getName());
    var persisted =
        tm().transact(
                () -> {
                  var entity = reservedList.asBuilder().build();
                  tm().insert(entity);
                  return entity;
                });
    logger.atInfo().log(
        "Saved reserved list %s with %d entries to Cloud SQL.",
        reservedList.getName(), reservedList.getReservedListEntries().size());
    return persisted;
  }

  /** Deletes a reserved list from Cloud SQL. */
  public static void delete(ReservedList reservedList) {
    tm().transact(() -> tm().delete(reservedList));
  }

  /**
   * Returns the most recent revision of the {@link ReservedList} with the specified name, if it
   * exists.
   */
  public static Optional<ReservedList> getLatestRevision(String reservedListName) {
    return tm().reTransact(
            () ->
                tm().query(
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
    return tm().transact(
            () ->
                !tm().query("SELECT 1 FROM ReservedList WHERE name = :name", Integer.class)
                    .setParameter("name", reservedListName)
                    .setMaxResults(1)
                    .getResultList()
                    .isEmpty());
  }
}
