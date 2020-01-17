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

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/** Data access object class for {@link ReservedList} */
public class ReservedListDao {

  /** Persist a new reserved list to Cloud SQL. */
  public static void save(ReservedList reservedList) {
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(reservedList));
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
                        .getEntityManager()
                        .createQuery("SELECT 1 FROM ReservedList WHERE name = :name", Integer.class)
                        .setParameter("name", reservedListName)
                        .setMaxResults(1)
                        .getResultList()
                        .size()
                    > 0);
  }

  /**
   * Returns the most recent revision of the {@link ReservedList} with the specified name, if it
   * exists. TODO(shicong): Change this method to package level access after dual-read phase.
   */
  public static Optional<ReservedList> getLatestRevision(String reservedListName) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery(
                        "FROM ReservedList rl LEFT JOIN FETCH rl.labelsToReservations WHERE"
                            + " rl.revisionId IN (SELECT MAX(revisionId) FROM ReservedList subrl"
                            + " WHERE subrl.name = :name)",
                        ReservedList.class)
                    .setParameter("name", reservedListName)
                    .getResultStream()
                    .findFirst());
  }

  /**
   * Returns the most recent revision of the {@link ReservedList} with the specified name, from
   * cache.
   */
  public static Optional<ReservedList> getLatestRevisionCached(String reservedListName) {
    try {
      return ReservedListCache.cacheReservedLists.get(reservedListName);
    } catch (ExecutionException e) {
      throw new UncheckedExecutionException(
          "Could not retrieve reserved list named " + reservedListName, e);
    }
  }

  private ReservedListDao() {}
}
