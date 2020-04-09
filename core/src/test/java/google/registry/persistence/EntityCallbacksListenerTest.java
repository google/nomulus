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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link EntityCallbacksListener}. */
@RunWith(JUnit4.class)
public class EntityCallbacksListenerTest {

  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  public void verifyAllCallbacksWork() {
    TestEntity testEntity = new TestEntity();
    jpaTm().transact(() -> jpaTm().saveNew(testEntity));
    assertEqualToOne(
        testEntity.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPostPersist);
    jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery("UPDATE TestEntity SET foo = 1 WHERE name = 'id'")
                    .executeUpdate());

    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().load(VKey.createSql(TestEntity.class, "id"))).get();
    assertEqualToOne(persisted.entityPostLoad);
    assertEqualToOne(persisted.entityEmbedded.entityEmbeddedPostLoad);
    assertEqualToOne(persisted.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPostLoad);
    assertEqualToOne(persisted.entityEmbedded.entityEmbeddedParentPostLoad);

    assertEqualToOne(persisted.parentPostLoad);
    assertEqualToOne(persisted.parentEmbedded.parentEmbeddedPostLoad);
    assertEqualToOne(persisted.parentEmbedded.parentEmbeddedNested.parentEmbeddedNestedPostLoad);
    assertEqualToOne(persisted.parentEmbedded.parentEmbeddedParentPostLoad);

    assertEqualToOne(persisted.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPrePersist);
    assertEqualToOne(persisted.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPreUpdate);
    assertEqualToOne(persisted.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPostUpdate);

    TestEntity deleted =
        jpaTm()
            .transact(
                () -> {
                  TestEntity merged = jpaTm().getEntityManager().merge(persisted);
                  jpaTm().getEntityManager().remove(merged);
                  return merged;
                });
    assertEqualToOne(deleted.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPreRemove);
    assertEqualToOne(deleted.entityEmbedded.entityEmbeddedNested.entityEmbeddedNestedPostRemove);
  }

  private void assertEqualToOne(int executeTimes) {
    assertThat(executeTimes).isEqualTo(1);
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends ParentEntity {
    @Id String name = "id";
    int foo = 0;

    int entityPostLoad = 0;

    @Embedded EntityEmbedded entityEmbedded = new EntityEmbedded();

    @PostLoad
    void entityPostLoad() {
      entityPostLoad++;
    }
  }

  @Embeddable
  private static class EntityEmbedded extends EntityEmbeddedParent {
    @Embedded EntityEmbeddedNested entityEmbeddedNested = new EntityEmbeddedNested();

    int entityEmbeddedPostLoad = 0;

    @PostLoad
    void entityEmbeddedPrePersist() {
      entityEmbeddedPostLoad++;
    }
  }

  @MappedSuperclass
  private static class EntityEmbeddedParent {
    int entityEmbeddedParentPostLoad = 0;

    @PostLoad
    void entityEmbeddedParentPostLoad() {
      entityEmbeddedParentPostLoad++;
    }
  }

  @Embeddable
  private static class EntityEmbeddedNested {
    int entityEmbeddedNestedPrePersist = 0;
    int entityEmbeddedNestedPreRemove = 0;
    int entityEmbeddedNestedPostPersist = 0;
    int entityEmbeddedNestedPostRemove = 0;
    int entityEmbeddedNestedPreUpdate = 0;
    int entityEmbeddedNestedPostUpdate = 0;
    int entityEmbeddedNestedPostLoad = 0;

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
    int parentPostLoad = 0;

    @PostLoad
    void parentPostLoad() {
      parentPostLoad++;
    }
  }

  @Embeddable
  private static class ParentEmbedded extends ParentEmbeddedParent {
    int parentEmbeddedPostLoad = 0;

    @Embedded ParentEmbeddedNested parentEmbeddedNested = new ParentEmbeddedNested();

    @PostLoad
    void parentEmbeddedPostLoad() {
      parentEmbeddedPostLoad++;
    }
  }

  @Embeddable
  private static class ParentEmbeddedNested {
    int parentEmbeddedNestedPostLoad = 0;

    @PostLoad
    void parentEmbeddedNestedPostLoad() {
      parentEmbeddedNestedPostLoad++;
    }
  }

  @MappedSuperclass
  private static class ParentEmbeddedParent {
    int parentEmbeddedParentPostLoad = 0;

    @PostLoad
    void parentEmbeddedParentPostLoad() {
      parentEmbeddedParentPostLoad++;
    }
  }
}
