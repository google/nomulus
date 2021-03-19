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

package google.registry.model.registry;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableList;
import google.registry.schema.domain.RegistryLock;
import java.util.Optional;

/** Data access object for {@link google.registry.schema.domain.RegistryLock}. */
public final class RegistryLockDao {

  /** Returns the {@link RegistryLock} referred to by this revision ID, or empty if none exists. */
  public static Optional<RegistryLock> getByRevisionId(long revisionId) {
    jpaTm().assertInTransaction();
    return Optional.ofNullable(jpaTm().getEntityManager().find(RegistryLock.class, revisionId));
  }

  /** Returns the most recent version of the {@link RegistryLock} referred to by the code. */
  public static Optional<RegistryLock> getByVerificationCode(String verificationCode) {
    jpaTm().assertInTransaction();
    Long revisionId =
        jpaTm()
            .query(
                "SELECT MAX(revisionId) FROM RegistryLock WHERE verificationCode ="
                    + " :verificationCode",
                Long.class)
            .setParameter("verificationCode", verificationCode)
            .getSingleResult();
    return Optional.ofNullable(revisionId)
        .map(revision -> jpaTm().getEntityManager().find(RegistryLock.class, revision));
  }

  /** Returns all lock objects that this registrar has created, including pending locks. */
  public static ImmutableList<RegistryLock> getLocksByRegistrarId(String registrarId) {
    jpaTm().assertInTransaction();
    return ImmutableList.copyOf(
        jpaTm()
            .query(
                "SELECT lock FROM RegistryLock lock"
                    + " WHERE lock.registrarId = :registrarId"
                    + " AND lock.unlockCompletionTimestamp IS NULL"
                    + " ORDER BY lock.domainName ASC",
                RegistryLock.class)
            .setParameter("registrarId", registrarId)
            .getResultList());
  }

  /**
   * Returns the most recent lock object for a given domain specified by repo ID.
   *
   * <p>Returns empty if this domain hasn't been locked before.
   */
  public static Optional<RegistryLock> getMostRecentByRepoId(String repoId) {
    jpaTm().assertInTransaction();
    return jpaTm()
        .query(
            "SELECT lock FROM RegistryLock lock WHERE lock.repoId = :repoId"
                + " ORDER BY lock.revisionId DESC",
            RegistryLock.class)
        .setParameter("repoId", repoId)
        .setMaxResults(1)
        .getResultStream()
        .findFirst();
  }

  /**
   * Returns the most recent verified lock object for a given domain specified by repo ID.
   *
   * <p>Returns empty if no lock has ever been finalized for this domain. This is different from
   * {@link #getMostRecentByRepoId(String)} in that it only returns verified locks.
   */
  public static Optional<RegistryLock> getMostRecentVerifiedLockByRepoId(String repoId) {
    jpaTm().assertInTransaction();
    return jpaTm()
        .query(
            "SELECT lock FROM RegistryLock lock WHERE lock.repoId = :repoId AND"
                + " lock.lockCompletionTimestamp IS NOT NULL AND"
                + " lock.unlockCompletionTimestamp IS NULL ORDER BY lock.revisionId"
                + " DESC",
            RegistryLock.class)
        .setParameter("repoId", repoId)
        .setMaxResults(1)
        .getResultStream()
        .findFirst();
  }

  /**
   * Returns the most recent verified unlock for a given domain specified by repo ID.
   *
   * <p>Returns empty if no unlock has ever been finalized for this domain. This is different from
   * {@link #getMostRecentByRepoId(String)} in that it only returns verified unlocks.
   */
  public static Optional<RegistryLock> getMostRecentVerifiedUnlockByRepoId(String repoId) {
    jpaTm().assertInTransaction();
    return jpaTm()
        .query(
            "SELECT lock FROM RegistryLock lock WHERE lock.repoId = :repoId AND"
                + " lock.unlockCompletionTimestamp IS NOT NULL ORDER BY lock.revisionId"
                + " DESC",
            RegistryLock.class)
        .setParameter("repoId", repoId)
        .setMaxResults(1)
        .getResultStream()
        .findFirst();
  }

  public static RegistryLock save(RegistryLock registryLock) {
    jpaTm().assertInTransaction();
    checkNotNull(registryLock, "Null registry lock cannot be saved");
    return jpaTm().getEntityManager().merge(registryLock);
  }
}
