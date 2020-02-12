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
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import google.registry.model.registrar.RegistrarContact;
import java.util.Optional;

/** Data access object for {@link RegistrarContact}. */
public class RegistrarPocDao {
  private RegistrarPocDao() {}

  /** Persists a new registrar POC in Cloud SQL. */
  public static void saveNew(RegistrarContact registrarPoc) {
    checkArgumentNotNull(registrarPoc, "registrarPoc must be specified");
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(registrarPoc));
  }

  /** Updates an existing registrar POC in Cloud SQL, throws exception if it does not exist. */
  public static void update(RegistrarContact registrarPoc) {
    checkArgumentNotNull(registrarPoc, "registrarPoc must be specified");
    jpaTm()
        .transact(
            () -> {
              checkArgument(
                  checkExists(registrarPoc.getEmailAddress()),
                  "A registrar POC of this email address does not exist: %s.",
                  registrarPoc.getEmailAddress());
              jpaTm().getEntityManager().merge(registrarPoc);
            });
  }

  /** Returns whether the registrar POC of the given email address exists. */
  public static boolean checkExists(String emailAddress) {
    checkArgumentNotNull(emailAddress, "emailAddress must be specified");
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                        .getEntityManager()
                        .createQuery(
                            "SELECT 1 FROM RegistrarContact WHERE emailAddress = :emailAddress",
                            Integer.class)
                        .setParameter("emailAddress", emailAddress)
                        .setMaxResults(1)
                        .getResultList()
                        .size()
                    > 0);
  }

  /** Loads the registrar POC by its id, returns empty if it doesn't exist. */
  public static Optional<RegistrarContact> load(String emailAddress) {
    checkArgumentNotNull(emailAddress, "emailAddress must be specified");
    return Optional.ofNullable(
        jpaTm()
            .transact(() -> jpaTm().getEntityManager().find(RegistrarContact.class, emailAddress)));
  }

  /** Deletes the registrar POC by its id, throws exception if it doesn't exist. */
  public static void delete(String emailAddress) {
    checkArgumentNotNull(emailAddress, "emailAddress must be specified");
    jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .remove(
                        checkArgumentPresent(
                            load(emailAddress),
                            "A registrar POC of this email address does not exist: %s.",
                            emailAddress)));
  }
}
