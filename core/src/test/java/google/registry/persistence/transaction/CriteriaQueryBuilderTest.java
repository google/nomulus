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

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import google.registry.testing.FakeClock;
import java.util.List;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.criteria.CriteriaQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link CriteriaQueryBuilder}. */
class CriteriaQueryBuilderTest {

  private final FakeClock fakeClock = new FakeClock();

  private CriteriaQueryBuilderTestEntity entity1 =
      new CriteriaQueryBuilderTestEntity("name1", "data");
  private CriteriaQueryBuilderTestEntity entity2 =
      new CriteriaQueryBuilderTestEntity("name2", "zztz");
  private CriteriaQueryBuilderTestEntity entity3 = new CriteriaQueryBuilderTestEntity("zzz", "aaa");

  @RegisterExtension
  final JpaUnitTestExtension jpaExtension =
      new JpaTestRules.Builder()
          .withClock(fakeClock)
          .withEntityClass(CriteriaQueryBuilderTestEntity.class)
          .buildUnitTestRule();

  @BeforeEach
  void beforeEach() {
    jpaTm().transact(() -> jpaTm().putAll(ImmutableList.of(entity1, entity2, entity3)));
  }

  @Test
  void testSuccess_noWhereClause() {
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        jpaTm()
                            .getEntityManager()
                            .createQuery(
                                CriteriaQueryBuilder.create(CriteriaQueryBuilderTestEntity.class)
                                    .build())
                            .getResultList()))
        .containsExactly(entity1, entity2, entity3)
        .inOrder();
  }

  @Test
  void testSuccess_where_exactlyOne() {
    List<CriteriaQueryBuilderTestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<CriteriaQueryBuilderTestEntity> query =
                      CriteriaQueryBuilder.create(CriteriaQueryBuilderTestEntity.class)
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::equal,
                              "data",
                              "zztz")
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity2);
  }

  @Test
  void testSuccess_where_like_oneResult() {
    List<CriteriaQueryBuilderTestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<CriteriaQueryBuilderTestEntity> query =
                      CriteriaQueryBuilder.create(CriteriaQueryBuilderTestEntity.class)
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::like, "data", "a%")
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity3);
  }

  @Test
  void testSuccess_where_like_twoResults() {
    List<CriteriaQueryBuilderTestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<CriteriaQueryBuilderTestEntity> query =
                      CriteriaQueryBuilder.create(CriteriaQueryBuilderTestEntity.class)
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::like, "data", "%a%")
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity1, entity3).inOrder();
  }

  @Test
  void testSuccess_multipleWheres() {
    List<CriteriaQueryBuilderTestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<CriteriaQueryBuilderTestEntity> query =
                      CriteriaQueryBuilder.create(CriteriaQueryBuilderTestEntity.class)
                          // first "where" matches 1 and 3
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::like, "data", "%a%")
                          // second "where" matches 1 and 2
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::like, "data", "%t%")
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity1);
  }

  @Test
  void testSuccess_where_in_oneResult() {
    List<CriteriaQueryBuilderTestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<CriteriaQueryBuilderTestEntity> query =
                      CriteriaQueryBuilder.create(CriteriaQueryBuilderTestEntity.class)
                          .whereFieldIsIn("data", ImmutableList.of("aaa", "bbb"))
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity3).inOrder();
  }

  @Test
  void testSuccess_where_in_twoResults() {
    List<CriteriaQueryBuilderTestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<CriteriaQueryBuilderTestEntity> query =
                      CriteriaQueryBuilder.create(CriteriaQueryBuilderTestEntity.class)
                          .whereFieldIsIn("data", ImmutableList.of("aaa", "bbb", "data"))
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity1, entity3).inOrder();
  }

  @Test
  void testSuccess_orderBy() {
    List<CriteriaQueryBuilderTestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<CriteriaQueryBuilderTestEntity> query =
                      CriteriaQueryBuilder.create(CriteriaQueryBuilderTestEntity.class)
                          .orderBy(jpaTm().getEntityManager().getCriteriaBuilder()::asc, "data")
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::like, "data", "%a%")
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity3, entity1).inOrder();
  }

  @Entity(name = "CriteriaQueryBuilderTestEntity")
  private static class CriteriaQueryBuilderTestEntity extends ImmutableObject {
    @Id private String name;

    @SuppressWarnings("unused")
    private String data;

    private CriteriaQueryBuilderTestEntity() {}

    private CriteriaQueryBuilderTestEntity(String name, String data) {
      this.name = name;
      this.data = data;
    }
  }
}
