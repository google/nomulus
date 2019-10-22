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
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableList;
import google.registry.schema.domain.RegistryLock;
import javax.persistence.EntityManager;

/** Data access object for {@link google.registry.schema.domain.RegistryLock}. */
public final class RegistryLockDao {

  /**
   * Returns the most recent version of the {@link RegistryLock} referred to by the verification
   * code (there may be two instances of the same code in the database--one after lock object
   * creation and one after verification.
   */
  public static RegistryLock getByVerificationCode(String verificationCode) {
    return jpaTm()
        .transact(
            () -> {
              EntityManager em = jpaTm().getEntityManager();
              Long revisionId =
                  em.createQuery(
                          "SELECT MAX(revisionId) FROM RegistryLock WHERE verificationCode ="
                              + " :verificationCode",
                          Long.class)
                      .setParameter("verificationCode", verificationCode)
                      .getSingleResult();
              checkNotNull(revisionId, "No registry lock with this code");
              return em.find(RegistryLock.class, revisionId);
            });
  }

  public static ImmutableList<RegistryLock> getByRegistrarId(String registrarId) {
    return jpaTm()
        .transact(
            () ->
                ImmutableList.copyOf(
                    jpaTm()
                        .getEntityManager()
                        .createQuery(
                            "SELECT lock FROM RegistryLock lock WHERE"
                                + " lock.registrarId = :registrarId",
                            RegistryLock.class)
                        .setParameter("registrarId", registrarId)
                        .getResultList()));
  }

  public static RegistryLock save(RegistryLock registryLock) {
    checkNotNull(registryLock, "Null registry lock cannot be saved");
    return jpaTm().transact(() -> jpaTm().getEntityManager().merge(registryLock));
  }
}
