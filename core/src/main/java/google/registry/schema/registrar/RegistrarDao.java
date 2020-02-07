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

package google.registry.schema.registrar;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.model.registrar.Registrar;
import java.util.Optional;

/** Data access object for {@link Registrar}. */
public class RegistrarDao {

  private RegistrarDao() {}

  /** Persists a new or updates an existing registrar in Cloud SQL. */
  public static void saveNew(Registrar registrar) {
    checkArgumentNotNull(registrar, "registrar must be specified");
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(registrar));
  }

  /** Updates an existing registrar in Cloud SQL, throws excpetion if it does not exist. */
  public static void update(Registrar registrar) {
    checkArgumentNotNull(registrar, "registrar must be specified");
    jpaTm()
        .transact(
            () -> {
              checkArgument(
                  checkExists(registrar.getClientId()),
                  "A registrar of this id does not exist: %s.",
                  registrar.getClientId());
              jpaTm().getEntityManager().merge(registrar);
            });
  }

  /** Returns whether the registrar of the given id exists. */
  public static boolean checkExists(String clientId) {
    checkArgumentNotNull(clientId, "clientId must be specified");
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                        .getEntityManager()
                        .createQuery(
                            "SELECT 1 FROM Registrar WHERE clientIdentifier = :clientIdentifier",
                            Integer.class)
                        .setParameter("clientIdentifier", clientId)
                        .setMaxResults(1)
                        .getResultList()
                        .size()
                    > 0);
  }

  /** Loads the registrar by its id, returns empty if it doesn't exist. */
  public static Optional<Registrar> load(String clientId) {
    checkArgumentNotNull(clientId, "clientId must be specified");
    return Optional.ofNullable(
        jpaTm().transact(() -> jpaTm().getEntityManager().find(Registrar.class, clientId)));
  }
}
