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

package google.registry.model.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import java.util.Optional;

/**
 * A {@link KmsSecretRevision} DAO for Cloud SQL.
 *
 * <p>TODO: Rename this class to KmsSecretDao after migrating to Cloud SQL.
 */
public class KmsSecretRevisionSqlDao {

  private KmsSecretRevisionSqlDao() {}

  /** Saves the given KMS secret revision. */
  public static void save(KmsSecretRevision kmsSecretRevision) {
    checkArgumentNotNull(kmsSecretRevision, "kmsSecretRevision cannot be null");
    jpaTm().assertInTransaction();
    jpaTm().put(kmsSecretRevision);
  }

  /** Returns the latest revision for the secret name given, or absent if nonexistent. */
  public static Optional<KmsSecretRevision> getLatestRevision(String secretName) {
    checkArgument(!isNullOrEmpty(secretName), "secretName cannot be null or empty");
    jpaTm().assertInTransaction();
    return jpaTm()
        .query(
            "FROM KmsSecret ks WHERE ks.revisionKey IN (SELECT MAX(revisionKey) FROM "
                + "KmsSecret subKs WHERE subKs.secretName = :secretName)",
            KmsSecretRevision.class)
        .setParameter("secretName", secretName)
        .getResultStream()
        .findFirst();
  }
}
