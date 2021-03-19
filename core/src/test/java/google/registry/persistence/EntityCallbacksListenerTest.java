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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableSet;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import java.lang.reflect.Method;
import javax.persistence.Embeddable;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;
import javax.persistence.PostLoad;
import javax.persistence.PostPersist;
import javax.persistence.PostRemove;
import javax.persistence.PostUpdate;
import javax.persistence.PrePersist;
import javax.persistence.PreRemove;
import javax.persistence.PreUpdate;
import javax.persistence.Transient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link EntityCallbacksListener}. */
class EntityCallbacksListenerTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  void verifyAllCallbacks_executedExpectedTimes() {
    TestEntity testPersist = new TestEntity();
    jpaTm().transact(() -> jpaTm().insert(testPersist));
    checkAll(testPersist, 1, 0, 0, 0);

    TestEntity testUpdate = new TestEntity();
    TestEntity updated =
        jpaTm()
            .transact(
                () -> {
                  TestEntity merged = jpaTm().getEntityManager().merge(testUpdate);
                  merged.foo++;
                  jpaTm().getEntityManager().flush();
                  return merged;
                });
    // Note that when we get the merged entity, its @PostLoad callbacks are also invoked
    checkAll(updated, 0, 1, 0, 1);

    TestEntity testLoad =
        jpaTm().transact(() -> jpaTm().loadByKey(VKey.createSql(TestEntity.class, "id")));
    checkAll(testLoad, 0, 0, 0, 1);

    TestEntity testRemove =
        jpaTm()
            .transact(
                () -> {
                  TestEntity removed = jpaTm().loadByKey(VKey.createSql(TestEntity.class, "id"));
                  jpaTm().delete(removed);
                  return removed;
                });
    checkAll(testRemove, 0, 0, 1, 1);
  }

  @Test
  void verifyAllManagedEntities_haveNoMethodWithEmbedded() {
    ImmutableSet<Class> violations =
        PersistenceXmlUtility.getManagedClasses().stream()
            .filter(clazz -> clazz.isAnnotationPresent(Entity.class))
            .filter(EntityCallbacksListenerTest::hasMethodAnnotatedWithEmbedded)
            .collect(toImmutableSet());
    assertWithMessage(
            "Found entity classes having methods annotated with @Embedded. EntityCallbacksListener"
                + " only supports annotating fields with @Embedded.")
        .that(violations)
        .isEmpty();
  }

  @Test
  void verifyHasMethodAnnotatedWithEmbedded_work() {
    assertThat(hasMethodAnnotatedWithEmbedded(ViolationEntity.class)).isTrue();
  }

  private static boolean hasMethodAnnotatedWithEmbedded(Class<?> entityType) {
    boolean result = false;
    Class<?> parentType = entityType.getSuperclass();
    if (parentType != null && parentType.isAnnotationPresent(MappedSuperclass.class)) {
      result = hasMethodAnnotatedWithEmbedded(parentType);
    }
    for (Method method : entityType.getDeclaredMethods()) {
      if (method.isAnnotationPresent(Embedded.class)) {
        result = true;
        break;
      }
    }
    return result;
  }

  private static void checkAll(
      TestEntity testEntity,
      int expectedPersist,
      int expectedUpdate,
      int expectedRemove,
      int expectedLoad) {
    assertThat(testEntity.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPostPersist)
        .isEqualTo(expectedPersist);
    assertThat(testEntity.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPrePersist)
        .isEqualTo(expectedPersist);

    assertThat(testEntity.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPreUpdate)
        .isEqualTo(expectedUpdate);
    assertThat(testEntity.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPostUpdate)
        .isEqualTo(expectedUpdate);

    assertThat(testEntity.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPreRemove)
        .isEqualTo(expectedRemove);
    assertThat(testEntity.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPostRemove)
        .isEqualTo(expectedRemove);

    assertThat(testEntity.entityPostLoad).isEqualTo(expectedLoad);
    assertThat(testEntity.entityEmbedded.entityEmbeddedPostLoad).isEqualTo(expectedLoad);
    assertThat(testEntity.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPostLoad)
        .isEqualTo(expectedLoad);
    assertThat(testEntity.entityEmbedded.entityEmbeddedParentPostLoad).isEqualTo(expectedLoad);

    assertThat(testEntity.parentPostLoad).isEqualTo(expectedLoad);
    assertThat(testEntity.parentEmbedded.parentEmbeddedPostLoad).isEqualTo(expectedLoad);
    assertThat(testEntity.parentEmbedded.parentEmbeddedNested.parentEmbeddedNestedPostLoad)
        .isEqualTo(expectedLoad);
    assertThat(testEntity.parentEmbedded.parentEmbeddedParentPostLoad).isEqualTo(expectedLoad);
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends ParentEntity {
    @Id String name = "id";
    int foo = 0;

    @Transient int entityPostLoad = 0;

    @Embedded EntityEmbedded entityEmbedded = new EntityEmbedded();

    @PostLoad
    void entityPostLoad() {
      entityPostLoad++;
    }
  }

  @Embeddable
  private static class EntityEmbedded extends EntityEmbeddedParent {
    @Embedded EntityEmbeddedNested entityEmbeddedNested = new EntityEmbeddedNested();

    @Transient int entityEmbeddedPostLoad = 0;

    String entityEmbedded = "placeholder";

    @PostLoad
    void entityEmbeddedPrePersist() {
      entityEmbeddedPostLoad++;
    }
  }

  @MappedSuperclass
  private static class EntityEmbeddedParent {
    @Transient int entityEmbeddedParentPostLoad = 0;

    String entityEmbeddedParent = "placeholder";

    @PostLoad
    void entityEmbeddedParentPostLoad() {
      entityEmbeddedParentPostLoad++;
    }
  }

  @Embeddable
  private static class EntityEmbeddedNested {
    @Transient int entityEmbeddedNestedPrePersist = 0;
    @Transient int entityEmbeddedNestedPreRemove = 0;
    @Transient int entityEmbeddedNestedPostPersist = 0;
    @Transient int entityEmbeddedNestedPostRemove = 0;
    @Transient int entityEmbeddedNestedPreUpdate = 0;
    @Transient int entityEmbeddedNestedPostUpdate = 0;
    @Transient int entityEmbeddedNestedPostLoad = 0;

    String entityEmbeddedNested = "placeholder";

    @PrePersist
    void entityEmbeddedNestedPrePersist() {
      entityEmbeddedNestedPrePersist++;
    }

    @PreRemove
    void entityEmbeddedNestedPreRemove() {
      entityEmbeddedNestedPreRemove++;
    }

    @PostPersist
    void entityEmbeddedNestedPostPersist() {
      entityEmbeddedNestedPostPersist++;
    }

    @PostRemove
    void entityEmbeddedNestedPostRemove() {
      entityEmbeddedNestedPostRemove++;
    }

    @PreUpdate
    void entityEmbeddedNestedPreUpdate() {
      entityEmbeddedNestedPreUpdate++;
    }

    @PostUpdate
    void entityEmbeddedNestedPostUpdate() {
      entityEmbeddedNestedPostUpdate++;
    }

    @PostLoad
    void entityEmbeddedNestedPostLoad() {
      entityEmbeddedNestedPostLoad++;
    }
  }

  @MappedSuperclass
  private static class ParentEntity {
    @Embedded ParentEmbedded parentEmbedded = new ParentEmbedded();
    @Transient int parentPostLoad = 0;

    String parentEntity = "placeholder";

    @PostLoad
    void parentPostLoad() {
      parentPostLoad++;
    }
  }

  @Embeddable
  private static class ParentEmbedded extends ParentEmbeddedParent {
    @Transient int parentEmbeddedPostLoad = 0;

    String parentEmbedded = "placeholder";

    @Embedded ParentEmbeddedNested parentEmbeddedNested = new ParentEmbeddedNested();

    @PostLoad
    void parentEmbeddedPostLoad() {
      parentEmbeddedPostLoad++;
    }
  }

  @Embeddable
  private static class ParentEmbeddedNested {
    @Transient int parentEmbeddedNestedPostLoad = 0;

    String parentEmbeddedNested = "placeholder";

    @PostLoad
    void parentEmbeddedNestedPostLoad() {
      parentEmbeddedNestedPostLoad++;
    }
  }

  @MappedSuperclass
  private static class ParentEmbeddedParent {
    @Transient int parentEmbeddedParentPostLoad = 0;

    String parentEmbeddedParent = "placeholder";

    @PostLoad
    void parentEmbeddedParentPostLoad() {
      parentEmbeddedParentPostLoad++;
    }
  }

  @Entity
  private static class ViolationEntity {

    @Embedded
    EntityEmbedded getEntityEmbedded() {
      return new EntityEmbedded();
    }
  }
}
