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
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.testing.TestDataHelper.fileClassPath;

import google.registry.testing.FakeClock;
import java.math.BigInteger;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link JpaTransactionManagerImpl}. */
@RunWith(JUnit4.class)
public class JpaTransactionManagerImplTest {
  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder()
          .withInitScript(fileClassPath(getClass(), "test_schema.sql"))
          .build();

  @Test
  public void inTransaction_returnsCorrespondingResult() {
    assertThat(jpaTm().inTransaction()).isFalse();
    jpaTm().transact(() -> assertThat(jpaTm().inTransaction()).isTrue());
    assertThat(jpaTm().inTransaction()).isFalse();
  }

  @Test
  public void assertInTransaction_throwsExceptionWhenNotInTransaction() {
    assertThrows(PersistenceException.class, () -> jpaTm().assertInTransaction());
    jpaTm().transact(() -> jpaTm().assertInTransaction());
    assertThrows(PersistenceException.class, () -> jpaTm().assertInTransaction());
  }

  @Test
  public void getTransactionTime_throwsExceptionWhenNotInTransaction() {
    FakeClock txnClock = jpaTmRule.getTxnClock();
    txnClock.advanceOneMilli();
    assertThrows(PersistenceException.class, () -> jpaTm().getTransactionTime());
    jpaTm().transact(() -> assertThat(jpaTm().getTransactionTime()).isEqualTo(txnClock.nowUtc()));
    assertThrows(PersistenceException.class, () -> jpaTm().getTransactionTime());
  }

  @Test
  public void transact_succeeds() {
    assertPersonEmpty();
    assertCompanyEmpty();
    jpaTm()
        .transact(
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
            jpaTm()
                .transact(
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
    jpaTm()
        .transact(
            () -> {
              insertPerson(10);
              insertCompany("Foo");
              jpaTm()
                  .transact(
                      () -> {
                        // The inner transact() can see the new entry because
                        // it actually reuses the same transaction created by
                        // the outer transact().
                        assertPersonCount(1);
                        assertPersonExist(10);
                        insertCompany("Bar");
                      });
            });
    assertPersonCount(1);
    assertPersonExist(10);
    assertCompanyCount(2);
    assertCompanyExist("Foo");
    assertCompanyExist("Bar");
  }

  @Test
  public void transactNew_bothInnerAndOuterTransactionsSucceed() {
    assertPersonEmpty();
    assertCompanyEmpty();
    jpaTm()
        .transactNew(
            () -> {
              insertPerson(10);
              insertCompany("Foo");
              jpaTm()
                  .transactNew(
                      () -> {
                        insertPerson(5);
                        insertCompany("Bar");
                      });
            });
    assertPersonCount(2);
    assertPersonExist(5);
    assertPersonExist(10);
    assertCompanyCount(2);
    assertCompanyExist("Foo");
    assertCompanyExist("Bar");
  }

  @Test
  public void transactNew_alwaysStartsNewTransaction() {
    jpaTm()
        .transactNew(
            () -> {
              insertCompany("transactNew");
              jpaTm()
                  .transactNew(
                      () -> {
                        // The inner transactNew() cannot see the new entry because
                        // the actual transaction created by the outer transactNew()
                        // has not been committed yet. This proves that transactNew()
                        // always starts a new transaction instead of reusing the existing
                        // one.
                        assertCompanyEmpty();
                      });
            });
    assertCompanyExist("transactNew");
    assertCompanyCount(1);
  }

  @Test
  public void transactNew_OuterTransactionSucceedsWhileInnerTransactionFails() {
    jpaTm()
        .transactNew(
            () -> {
              insertPerson(10);
              assertThrows(
                  PersistenceException.class,
                  () ->
                      jpaTm()
                          .transactNew(
                              () -> {
                                insertPerson(5);
                                insertCompany("Bar");
                                throw new RuntimeException();
                              }));
              insertCompany("Foo");
            });
    assertPersonCount(1);
    assertPersonExist(10);
    assertCompanyCount(1);
    assertCompanyExist("Foo");
  }

  @Test
  public void transactNew_InnerTransactionSucceedsWhileOuterTransactionFails() {
    assertThrows(
        PersistenceException.class,
        () ->
            jpaTm()
                .transactNew(
                    () -> {
                      insertPerson(5);
                      insertCompany("Bar");
                      jpaTm()
                          .transactNew(
                              () -> {
                                insertPerson(10);
                                insertCompany("Foo");
                              });
                      throw new RuntimeException();
                    }));
    assertPersonCount(1);
    assertPersonExist(10);
    assertCompanyCount(1);
    assertCompanyExist("Foo");
  }

  private void insertPerson(int age) {
    jpaTm()
        .getEntityManager()
        .createNativeQuery(String.format("INSERT INTO Person (age) VALUES (%d)", age))
        .executeUpdate();
  }

  private void insertCompany(String name) {
    jpaTm()
        .getEntityManager()
        .createNativeQuery(String.format("INSERT INTO Company (name) VALUES ('%s')", name))
        .executeUpdate();
  }

  private void assertPersonExist(int age) {
    jpaTm()
        .transact(
            () -> {
              EntityManager em = jpaTm().getEntityManager();
              Integer maybeAge =
                  (Integer)
                      em.createNativeQuery(
                              String.format("SELECT age FROM Person WHERE age = %d", age))
                          .getSingleResult();
              assertThat(maybeAge).isEqualTo(age);
            });
  }

  private void assertCompanyExist(String name) {
    jpaTm()
        .transact(
            () -> {
              String maybeName =
                  (String)
                      jpaTm()
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
    return jpaTm()
        .transact(
            () -> {
              BigInteger colCount =
                  (BigInteger)
                      jpaTm()
                          .getEntityManager()
                          .createNativeQuery(String.format("SELECT COUNT(*) FROM %s", tableName))
                          .getSingleResult();
              return colCount.intValue();
            });
  }
}
