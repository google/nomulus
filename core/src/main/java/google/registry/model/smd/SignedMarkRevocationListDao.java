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

package google.registry.model.smd;

import static google.registry.model.CacheUtils.memoizeWithShortExpiration;
import static google.registry.model.DatabaseMigrationUtils.suppressExceptionUnlessInTest;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.base.Supplier;
import com.google.common.flogger.FluentLogger;
import java.util.Optional;
import javax.persistence.EntityManager;

public class SignedMarkRevocationListDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Supplier<Optional<SignedMarkRevocationList>> CACHE =
      memoizeWithShortExpiration(SignedMarkRevocationListDao::getLatestRevision);

  /** Returns the most recent revision of the {@link SignedMarkRevocationList}, from cache. */
  public static Optional<SignedMarkRevocationList> getLatestRevisionCached() {
    return CACHE.get();
  }

  public static Optional<SignedMarkRevocationList> getLatestRevision() {
    return jpaTm()
        .transact(
            () -> {
              EntityManager em = jpaTm().getEntityManager();
              Long revisionId =
                  em.createQuery("SELECT MAX(revisionId) FROM SignedMarkRevocationList", Long.class)
                      .getSingleResult();
              return em.createQuery(
                      "FROM SignedMarkRevocationList smrl LEFT JOIN FETCH smrl.revokes "
                          + "WHERE smrl.revisionId = :revisionId",
                      SignedMarkRevocationList.class)
                  .setParameter("revisionId", revisionId)
                  .getResultStream()
                  .findFirst();
            });
  }

  /**
   * Try to save the given {@link SignedMarkRevocationList} into Cloud SQL. If the save fails, the
   * error will be logged but no exception will be thrown.
   *
   * <p>This method is used during the dual-write phase of database migration as Datastore is still
   * the authoritative database.
   */
  static void trySave(SignedMarkRevocationList signedMarkRevocationList) {
    suppressExceptionUnlessInTest(
        () -> {
          SignedMarkRevocationListDao.save(signedMarkRevocationList);
          logger.atInfo().log(
              "Inserted %,d signed mark revocations into Cloud SQL.",
              signedMarkRevocationList.revokes.size());
        },
        "Error inserting signed mark revocations into Cloud SQL.");
  }

  private static void save(SignedMarkRevocationList signedMarkRevocationList) {
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(signedMarkRevocationList));
  }
}
