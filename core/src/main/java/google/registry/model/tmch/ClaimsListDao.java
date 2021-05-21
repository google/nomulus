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

import static google.registry.persistence.transaction.QueryComposer.Comparator.EQ;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableMap;

/** Data access object for {@link ClaimsList}. */
public class ClaimsListDao {

  /** Saves the given {@link ClaimsList} to Cloud SQL. */
  public static void save(ClaimsList claimsList) {
    jpaTm().transact(() -> jpaTm().insert(claimsList));
  }

  /**
   * Returns the most recent revision of the {@link ClaimsList} in SQL or an empty list if it
   * doesn't exist.
   */
  public static ClaimsList get() {
    return jpaTm()
        .transact(
            () -> {
              Long revisionId =
                  jpaTm()
                      .query("SELECT MAX(revisionId) FROM ClaimsList", Long.class)
                      .getSingleResult();
              return jpaTm()
                  .createQueryComposer(ClaimsList.class)
                  .where("revisionId", EQ, revisionId)
                  .first();
            })
        .orElse(ClaimsList.create(START_OF_TIME, ImmutableMap.of()));
  }

  private ClaimsListDao() {}
}
