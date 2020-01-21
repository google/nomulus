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

package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaUnitTestRule;
import java.util.List;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.RollbackException;
import org.hibernate.annotations.Columns;
import org.hibernate.annotations.Type;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link StreetUserType}. */
@RunWith(JUnit4.class)
public class StreetUserTypeTest {
  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  public void roundTripConversion_returnsSameStringList() {
    List<String> streets = ImmutableList.of("Street Line 1", "Street Line 2", "Street Line 3");
    TestEntity testEntity = new TestEntity(streets);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.streets)
        .containsExactly("Street Line 1", "Street Line 2", "Street Line 3");
  }

  @Test
  public void insertsLessLines_returnsExpandedList() {
    List<String> streets = ImmutableList.of("Street Line 1", "Street Line 2");
    TestEntity testEntity = new TestEntity(streets);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.streets).containsExactly("Street Line 1", "Street Line 2", "");
  }

  @Test
  public void insertsMoreLines_throwsException() {
    List<String> streets =
        ImmutableList.of("Street Line 1", "Street Line 2", "Street Line 3", "Street Line 4");
    TestEntity testEntity = new TestEntity(streets);
    Throwable thrown =
        assertThrows(
            RollbackException.class,
            () -> jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity)));
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Size of the list [4] exceeded the allowed maximum number of address lines [3].");
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(type = "google.registry.persistence.StreetUserType")
    @Columns(
        columns = {
          @Column(name = "street_line1"),
          @Column(name = "street_line2"),
          @Column(name = "street_line3")
        })
    List<String> streets;

    private TestEntity() {}

    private TestEntity(List<String> streets) {
      this.streets = streets;
    }
  }
}
