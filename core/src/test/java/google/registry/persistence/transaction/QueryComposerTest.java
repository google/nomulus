// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static org.junit.Assert.assertThrows;

import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NoResultException;
import jakarta.persistence.NonUniqueResultException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class QueryComposerTest {

  private final FakeClock fakeClock = new FakeClock();

  TestEntity alpha = new TestEntity("alpha", 3);
  TestEntity bravo = new TestEntity("bravo", 2);
  TestEntity charlie = new TestEntity("charlie", 1);

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder()
          .withClock(fakeClock)
          .withEntityClass(TestEntity.class)
          .buildUnitTestExtension();

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

  @Test
  public void testFirstQueries() {
    assertThat(
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("name", Comparator.GT, "bravo")
                            .first()
                            .map(DatabaseHelper::assertDetachedFromEntityManager)
                            .get()))
        .isEqualTo(charlie);
    assertThat(
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("name", Comparator.GTE, "charlie")
                            .first()
                            .map(DatabaseHelper::assertDetachedFromEntityManager)
                            .get()))
        .isEqualTo(charlie);
    assertThat(
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("name", Comparator.LT, "bravo")
                            .first()
                            .map(DatabaseHelper::assertDetachedFromEntityManager)
                            .get()))
        .isEqualTo(alpha);
    assertThat(
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("name", Comparator.LTE, "alpha")
                            .first()
                            .map(DatabaseHelper::assertDetachedFromEntityManager)
                            .get()))
        .isEqualTo(alpha);
  }

  @Test
  public void testCount() {
    assertThat(
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("name", Comparator.GTE, "bravo")
                            .count()))
        .isEqualTo(2L);
  }

  @Test
  public void testGetSingleResult() {
    assertThat(
            tm().transact(
                    () ->
                        DatabaseHelper.assertDetachedFromEntityManager(
                            tm().createQueryComposer(TestEntity.class)
                                .where("name", Comparator.EQ, "alpha")
                                .getSingleResult())))
        .isEqualTo(alpha);
  }

  @Test
  public void testGetSingleResult_noResults() {
    assertThrows(
        NoResultException.class,
        () ->
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("name", Comparator.EQ, "ziggy")
                            .getSingleResult()));
  }

  @Test
  public void testGetSingleResult_nonUniqueResult() {
    assertThrows(
        NonUniqueResultException.class,
        () ->
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("name", Comparator.GT, "alpha")
                            .getSingleResult()));
  }

  @Test
  public void testStreamQueries() {
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("name", Comparator.EQ, "alpha")
                            .stream()
                            .collect(toImmutableList())))
        .containsExactly(alpha);
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("name", Comparator.GT, "alpha")
                            .stream()
                            .map(DatabaseHelper::assertDetachedFromEntityManager)
                            .collect(toImmutableList())))
        .containsExactly(bravo, charlie);
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("name", Comparator.GTE, "bravo")
                            .stream()
                            .map(DatabaseHelper::assertDetachedFromEntityManager)
                            .collect(toImmutableList())))
        .containsExactly(bravo, charlie);
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("name", Comparator.LT, "charlie")
                            .stream()
                            .map(DatabaseHelper::assertDetachedFromEntityManager)
                            .collect(toImmutableList())))
        .containsExactly(alpha, bravo);
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("name", Comparator.LTE, "bravo")
                            .stream()
                            .map(DatabaseHelper::assertDetachedFromEntityManager)
                            .collect(toImmutableList())))
        .containsExactly(alpha, bravo);
  }

  @Test
  public void testListQueries() {
    assertThat(
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("name", Comparator.GT, "alpha")
                            .list()))
        .containsExactly(bravo, charlie);
  }

  @Test
  public void testNonPrimaryKey() {
    assertThat(
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("val", Comparator.EQ, 2)
                            .first()
                            .map(DatabaseHelper::assertDetachedFromEntityManager)
                            .get()))
        .isEqualTo(bravo);
  }

  @Test
  public void testOrderBy() {
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("val", Comparator.GT, 1)
                            .orderBy("val")
                            .stream()
                            .map(DatabaseHelper::assertDetachedFromEntityManager)
                            .collect(toImmutableList())))
        .containsExactly(bravo, alpha);
  }

  @Test
  public void testEmptyQueries() {
    assertThat(
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("name", Comparator.GT, "foxtrot")
                            .first()))
        .isEqualTo(Optional.empty());
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("name", Comparator.GT, "foxtrot")
                            .stream()
                            .collect(toImmutableList())))
        .isEmpty();
  }

  @Test
  void testMultipleInequalities_succeedsSql() {
    assertThat(
            tm().transact(
                    () ->
                        tm().createQueryComposer(TestEntity.class)
                            .where("val", Comparator.GT, 1)
                            .where("name", Comparator.LT, "b")
                            .list()))
        .containsExactly(alpha);
  }

  @Test
  public void testLikeQueries() {
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("name", Comparator.LIKE, "%harl%")
                            .stream()
                            .collect(toImmutableList())))
        .containsExactly(charlie);

    // Verify that full matches work.
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("name", Comparator.LIKE, "alpha")
                            .stream()
                            .collect(toImmutableList())))
        .containsExactly(alpha);

    // verify that we don't do partial matches.
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("name", Comparator.LIKE, "%harl")
                            .stream()
                            .collect(toImmutableList())))
        .isEmpty();
    assertThat(
            tm().transact(
                    () ->
                        tm()
                            .createQueryComposer(TestEntity.class)
                            .where("name", Comparator.LIKE, "harl%")
                            .stream()
                            .collect(toImmutableList())))
        .isEmpty();
  }

  @Entity
  private static class TestEntity extends ImmutableObject {
    @Id private String name;

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
