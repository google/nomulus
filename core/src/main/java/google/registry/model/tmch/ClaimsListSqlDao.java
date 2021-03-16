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

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import java.util.Optional;
import javax.persistence.EntityManager;

/** Data access object for {@link ClaimsListShard}. */
public class ClaimsListSqlDao {

  /** Saves the given {@link ClaimsListShard} to Cloud SQL. */
  static void save(ClaimsListShard claimsList) {
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(claimsList));
  }

  /**
   * Returns the most recent revision of the {@link ClaimsListShard} in SQL or an empty list if it
   * doesn't exist.
   */
  static Optional<ClaimsListShard> get() {
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

  private ClaimsListSqlDao() {}
}
