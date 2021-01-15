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

package google.registry.model.tmch;

import static google.registry.config.RegistryConfig.getDomainLabelListCacheDuration;
import static google.registry.model.CacheUtils.tryMemoizeWithExpiration;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.base.Supplier;
import com.google.common.flogger.FluentLogger;
import google.registry.util.NonFinalForTesting;
import java.util.Optional;
import javax.persistence.EntityManager;

/** Data access object for {@link ClaimsListShard}. */
public class ClaimsListDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** In-memory cache for claims list. */
  @NonFinalForTesting
  private static Supplier<Optional<ClaimsListShard>> cacheClaimsList =
      tryMemoizeWithExpiration(getDomainLabelListCacheDuration(), ClaimsListDao::getLatestRevision);

  private static void save(ClaimsListShard claimsList) {
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(claimsList));
  }

  /**
   * Try to save the given {@link ClaimsListShard} into Cloud SQL. If the save fails, the error will
   * be logged but no exception will be thrown.
   *
   * <p>This method is used during the dual-write phase of database migration as Datastore is still
   * the authoritative database.
   */
  static void trySave(ClaimsListShard claimsList) {
    try {
      ClaimsListDao.save(claimsList);
      logger.atInfo().log(
          "Inserted %,d claims into Cloud SQL, created at %s",
          claimsList.getLabelsToKeys().size(), claimsList.getTmdbGenerationTime());
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log("Error inserting claims into Cloud SQL");
    }
  }

  /**
   * Returns the most recent revision of the {@link ClaimsListShard} in Cloud SQL, if it exists.
   * TODO(b/177569979): Change this method to package level access after dual-read phase.
   * ClaimsListShard uses this method to retrieve claims list in Cloud SQL for the comparison, and
   * ClaimsListShard is not in this package.
   */
  public static Optional<ClaimsListShard> getLatestRevision() {
    return jpaTm()
        .transact(
            () -> {
              EntityManager em = jpaTm().getEntityManager();
              Long revisionId =
                  em.createQuery("SELECT MAX(revisionId) FROM ClaimsList", Long.class)
                      .getSingleResult();
              return em.createQuery(
                      "FROM ClaimsList cl LEFT JOIN FETCH cl.labelsToKeys WHERE cl.revisionId ="
                          + " :revisionId",
                      ClaimsListShard.class)
                  .setParameter("revisionId", revisionId)
                  .getResultStream()
                  .findFirst();
            });
  }

  /** Returns the most recent revision of the {@link ClaimsListShard}, from cache. */
  public static Optional<ClaimsListShard> getLatestRevisionCached() {
    return cacheClaimsList.get();
  }

  private ClaimsListDao() {}
}
