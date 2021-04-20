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

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import java.util.Optional;

public class SignedMarkRevocationListDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Loads the {@link SignedMarkRevocationList}. */
  static SignedMarkRevocationList load() {
    Optional<SignedMarkRevocationList> smdrl =
        jpaTm()
            .transact(
                () -> {
                  Long revisionId =
                      jpaTm()
                          .query("SELECT MAX(revisionId) FROM SignedMarkRevocationList", Long.class)
                          .getSingleResult();
                  return jpaTm()
                      .query(
                          "FROM SignedMarkRevocationList smrl LEFT JOIN FETCH smrl.revokes "
                              + "WHERE smrl.revisionId = :revisionId",
                          SignedMarkRevocationList.class)
                      .setParameter("revisionId", revisionId)
                      .getResultStream()
                      .findFirst();
                });
    return smdrl.orElseGet(() -> SignedMarkRevocationList.create(START_OF_TIME, ImmutableMap.of()));
  }

  /** Save the given {@link SignedMarkRevocationList} */
  static void save(SignedMarkRevocationList signedMarkRevocationList) {
    jpaTm().transact(() -> jpaTm().insert(signedMarkRevocationList));
    logger.atInfo().log(
        "Inserted %,d signed mark revocations into Cloud SQL.",
        signedMarkRevocationList.revokes.size());
  }
}
