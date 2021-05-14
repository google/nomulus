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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.QueryComposer.Comparator;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static org.junit.Assert.assertThrows;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import google.registry.model.ImmutableObject;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import google.registry.testing.TestSqlOnly;
import java.util.Optional;
import javax.persistence.Column;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

@DualDatabaseTest
public class QueryComposerTest {

  private final FakeClock fakeClock = new FakeClock();

  TestEntity alpha = new TestEntity("alpha", 3);
  TestEntity bravo = new TestEntity("bravo", 2);
  TestEntity charlie = new TestEntity("charlie", 1);

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withClock(fakeClock)
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestEntity.class)
          .withJpaUnitTestEntities(TestEntity.class)
          .build();

  public QueryComposerTest() {}

  @BeforeEach
  void setUp() {
    tm().transact(
            () -> {
              tm().insert(alpha);
              tm().insert(bravo);
              tm().insert(charlie);
            });
  }

  @TestOfyAndSql
  public void testFirstQueries() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.EQ, "bravo")
                        .first()
                        .get()))
        .isEqualTo(bravo);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "bravo")
                        .first()
                        .get()))
        .isEqualTo(charlie);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GTE, "charlie")
                        .first()
                        .get()))
        .isEqualTo(charlie);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LT, "bravo")
                        .first()
                        .get()))
        .isEqualTo(alpha);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LTE, "alpha")
                        .first()
                        .get()))
        .isEqualTo(alpha);
  }

  @TestOfyAndSql
  public void testCount() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GTE, "bravo")
                        .count()))
        .isEqualTo(2L);
  }

  @TestOfyAndSql
  public void testGetSingleResult() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.EQ, "alpha")
                        .getSingleResult()))
        .isEqualTo(alpha);
  }

  @TestOfyAndSql
  public void testGetSingleResult_noResults() {
    assertThrows(
        NoResultException.class,
        () ->
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.EQ, "ziggy")
                        .getSingleResult()));
  }

  @TestOfyAndSql
  public void testGetSingleResult_nonUniqueResult() {
    assertThrows(
        NonUniqueResultException.class,
        () ->
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "alpha")
                        .getSingleResult()));
  }

  @TestOfyAndSql
  public void testStreamQueries() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.EQ, "alpha")
                        .stream()
                        .collect(toImmutableList())))
        .containsExactly(alpha);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "alpha")
                        .stream()
                        .collect(toImmutableList())))
        .containsExactly(bravo, charlie);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GTE, "bravo")
                        .stream()
                        .collect(toImmutableList())))
        .containsExactly(bravo, charlie);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LT, "charlie")
                        .stream()
                        .collect(toImmutableList())))
        .containsExactly(alpha, bravo);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LTE, "bravo")
                        .stream()
                        .collect(toImmutableList())))
        .containsExactly(alpha, bravo);
  }

  @TestOfyAndSql
  public void testListQueries() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "alpha")
                        .list()))
        .containsExactly(bravo, charlie);
  }

  @TestOfyAndSql
  public void testNonPrimaryKey() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("val", Comparator.EQ, 2)
                        .first()
                        .get()))
        .isEqualTo(bravo);
  }

  @TestOfyAndSql
  public void testOrderBy() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("val", Comparator.GT, 1)
                        .orderBy("val")
                        .stream()
                        .collect(toImmutableList())))
        .containsExactly(bravo, alpha);
  }

  @TestOfyAndSql
  public void testEmptyQueries() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "foxtrot")
                        .first()))
        .isEqualTo(Optional.empty());
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "foxtrot")
                        .stream()
                        .collect(toImmutableList())))
        .isEmpty();
  }

  @TestOfyOnly
  void testMultipleInequalities_failsDatastore() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("val", Comparator.GT, 1)
                        .where("name", Comparator.LT, "b")
                        .list()))
        .hasMessageThat()
        .isEqualTo(
            "Datastore cannot handle inequality queries on multiple fields, we found 2 fields.");
  }

  @TestSqlOnly
  void testMultipleInequalities_succeedsSql() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("val", Comparator.GT, 1)
                        .where("name", Comparator.LT, "b")
                        .list()))
        .containsExactly(alpha);
  }

  @TestSqlOnly
  public void testLikeQueries() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LIKE, "%harl%")
                        .stream()
                        .collect(toImmutableList())))
        .containsExactly(charlie);

    // Verify that full matches work.
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LIKE, "alpha")
                        .stream()
                        .collect(toImmutableList())))
        .containsExactly(alpha);

    // verify that we don't do partial matches.
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LIKE, "%harl")
                        .stream()
                        .collect(toImmutableList())))
        .isEmpty();
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LIKE, "harl%")
                        .stream()
                        .collect(toImmutableList())))
        .isEmpty();
  }

  @TestOfyOnly
  public void testLikeQueries_failsOnOfy() {
    UnsupportedOperationException thrown =
        assertThrows(
            UnsupportedOperationException.class,
            () ->
                tm()
                    .createQueryComposer(TestEntity.class)
                    .where("name", Comparator.LIKE, "%")
                    .stream());
    assertThat(thrown)
        .hasMessageThat()
        .contains("The LIKE operation is not supported on Datastore.");
  }

  @javax.persistence.Entity
  @Entity(name = "QueryComposerTestEntity")
  private static class TestEntity extends ImmutableObject {
    @javax.persistence.Id @Id private String name;

    @Index
    // Renaming this implicitly verifies that property names work for hibernate queries.
    @Column(name = "some_value")
    private int val;

    public TestEntity() {}

    public TestEntity(String name, int val) {
      this.name = name;
      this.val = val;
    }

    public int getVal() {
      return val;
    }

    public String getName() {
      return name;
    }
  }
}
