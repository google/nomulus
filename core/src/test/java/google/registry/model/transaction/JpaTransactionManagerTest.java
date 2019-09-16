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

package google.registry.model.transaction;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.testing.TestDataHelper.fileClassPath;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.persistence.PersistenceModule;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import java.math.BigInteger;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceException;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Unit tests for {@link JpaTransactionManager}. */
@RunWith(JUnit4.class)
public class JpaTransactionManagerTest {
  @Rule
  public JdbcDatabaseContainer database =
      new PostgreSQLContainer().withInitScript(fileClassPath(getClass(), "test_schema.sql"));

  private DateTime now = DateTime.now(UTC);
  private Clock clock = new FakeClock(now);
  private EntityManagerFactory emf;
  private JpaTransactionManager txnManager;

  @Before
  public void init() {
    emf =
        PersistenceModule.create(
            database.getJdbcUrl(),
            database.getUsername(),
            database.getPassword(),
            PersistenceModule.providesDefaultDatabaseConfigs());
    txnManager = new JpaTransactionManager(emf, clock);
  }

  @After
  public void clear() {
    if (emf != null) {
      emf.close();
    }
  }

  @Test
  public void inTransaction_returnsCorrespondingResult() {
    assertThat(txnManager.inTransaction()).isFalse();
    txnManager.transact(() -> assertThat(txnManager.inTransaction()).isTrue());
    assertThat(txnManager.inTransaction()).isFalse();
  }

  @Test
  public void assertInTransaction_throwsExceptionWhenNotInTransaction() {
    assertThrows(PersistenceException.class, () -> txnManager.assertInTransaction());
    txnManager.transact(() -> txnManager.assertInTransaction());
    assertThrows(PersistenceException.class, () -> txnManager.assertInTransaction());
  }

  @Test
  public void getTransactionTime_throwsExceptionWhenNotInTransaction() {
    assertThrows(PersistenceException.class, () -> txnManager.getTransactionTime());
    txnManager.transact(() -> assertThat(txnManager.getTransactionTime()).isEqualTo(now));
    assertThrows(PersistenceException.class, () -> txnManager.getTransactionTime());
  }

  @Test
  public void transact_succeeds() {
    assertPersonEmpty();
    assertCompanyEmpty();
    txnManager.transact(
        () -> {
          insertPerson(10);
          insertCompany("Foo");
          insertCompany("Bar");
        });
    assertPersonCount(1);
    assertPersonExist(10);
    assertCompanyCount(2);
    assertCompanyExist("Foo");
    assertCompanyExist("Bar");
  }

  @Test
  public void transact_hasNoEffectWithPartialSuccess() {
    assertPersonEmpty();
    assertCompanyEmpty();
    assertThrows(
        RuntimeException.class,
        () ->
            txnManager.transact(
                () -> {
                  insertPerson(10);
                  insertCompany("Foo");
                  throw new RuntimeException();
                }));
    assertPersonEmpty();
    assertCompanyEmpty();
  }

  @Test
  public void transact_reusesExistingTransaction() {
    assertPersonEmpty();
    assertCompanyEmpty();
    txnManager.transact(
        () ->
            txnManager.transact(
                () -> {
                  insertPerson(10);
                  insertCompany("Foo");
                  insertCompany("Bar");
                }));
    assertPersonCount(1);
    assertPersonExist(10);
    assertCompanyCount(2);
    assertCompanyExist("Foo");
    assertCompanyExist("Bar");
  }

  private void insertPerson(int age) {
    txnManager
        .getEntityManager()
        .createNativeQuery(String.format("INSERT INTO Person (age) VALUES (%d)", age))
        .executeUpdate();
  }

  private void insertCompany(String name) {
    txnManager
        .getEntityManager()
        .createNativeQuery(String.format("INSERT INTO Company (name) VALUES ('%s')", name))
        .executeUpdate();
  }

  private void assertPersonExist(int age) {
    txnManager.transact(
        () -> {
          EntityManager em = txnManager.getEntityManager();
          Integer maybeAge =
              (Integer)
                  em.createNativeQuery(String.format("SELECT age FROM Person WHERE age = %d", age))
                      .getSingleResult();
          assertThat(maybeAge).isEqualTo(age);
        });
  }

  private void assertCompanyExist(String name) {
    txnManager.transact(
        () -> {
          String maybeName =
              (String)
                  txnManager
                      .getEntityManager()
                      .createNativeQuery(
                          String.format("SELECT name FROM Company WHERE name = '%s'", name))
                      .getSingleResult();
          assertThat(maybeName).isEqualTo(name);
        });
  }

  private void assertPersonCount(int count) {
    assertThat(countTable("Person")).isEqualTo(count);
  }

  private void assertCompanyCount(int count) {
    assertThat(countTable("Company")).isEqualTo(count);
  }

  private void assertPersonEmpty() {
    assertPersonCount(0);
  }

  private void assertCompanyEmpty() {
    assertCompanyCount(0);
  }

  private int countTable(String tableName) {
    return txnManager.transact(
        () -> {
          BigInteger colCount =
              (BigInteger)
                  txnManager
                      .getEntityManager()
                      .createNativeQuery(String.format("SELECT COUNT(*) FROM %s", tableName))
                      .getSingleResult();
          return colCount.intValue();
        });
  }
}
