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

package google.registry.persistence.transaction;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.Assert.assertThrows;

import google.registry.model.registrar.Registrar;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestSqlOnly;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ReadOnlyTransactionManager}. */
@DualDatabaseTest
public class ReadOnlyTransactionManagerTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private TransactionManager readOnlyTm;
  private VKey<Registrar> registrarKey;

  @BeforeEach
  void beforeEach() {
    readOnlyTm = new ReadOnlyTransactionManager(tm());
    registrarKey = AppEngineExtension.makeRegistrar2().createVKey();
  }

  @TestOfyAndSql
  void testRead_succeeds() {
    assertThat(readOnlyTm.transact(() -> readOnlyTm.loadByKey(registrarKey).getEmailAddress()))
        .isEqualTo("the.registrar@example.com");
  }

  @TestOfyAndSql
  void testWrite_fails() {
    readOnlyTm.transact(
        () -> {
          Registrar registrar = readOnlyTm.loadByKey(registrarKey);
          Registrar modified = registrar.asBuilder().setEmailAddress("foo@bar.com").build();
          assertThat(
                  assertThrows(UnsupportedOperationException.class, () -> readOnlyTm.put(modified)))
              .hasMessageThat()
              .isEqualTo("Transaction manager currently in read-only mode");
        });
  }

  @TestSqlOnly
  void testWrite_usingEntityManager_fails() {
    ReadOnlyJpaTransactionManager readOnlyJpaTm = new ReadOnlyJpaTransactionManager(jpaTm());
    readOnlyJpaTm.transact(
        () -> {
          assertThat(readOnlyJpaTm.loadByKey(registrarKey).getEmailAddress())
              .isEqualTo("the.registrar@example.com");
          assertThat(
                  readOnlyJpaTm
                      .query(
                          "FROM Registrar WHERE registrarName = 'The Registrar'", Registrar.class)
                      .getSingleResult()
                      .getEmailAddress())
              .isEqualTo("the.registrar@example.com");
          assertThat(
                  assertThrows(
                      UnsupportedOperationException.class,
                      () ->
                          readOnlyJpaTm
                              .query("DELETE FROM Registrar WHERE registrarName = 'The Registrar'")
                              .executeUpdate()))
              .hasMessageThat()
              .isEqualTo("Transaction manager currently in read-only mode");
        });
  }
}
