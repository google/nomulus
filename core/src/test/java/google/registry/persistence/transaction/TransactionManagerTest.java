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

package google.registry.persistence.transaction;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.DatastoreTransactionManager;
import google.registry.model.ofy.Ofy;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for common APIs in {@link DatastoreTransactionManager} and {@link
 * JpaTransactionManagerImpl}.
 */
@DualDatabaseTest
public class TransactionManagerTest {

  private final FakeClock fakeClock = new FakeClock();

  private final TestEntity theEntity = new TestEntity("theEntity", "foo");
  private final ImmutableList<TestEntity> moreEntities =
      ImmutableList.of(
          new TestEntity("entity1", "foo"),
          new TestEntity("entity2", "bar"),
          new TestEntity("entity3", "qux"));

  @RegisterExtension public InjectRule inject = new InjectRule();

  @RegisterExtension
  public final AppEngineRule appEngine =
      AppEngineRule.builder()
          .withClock(fakeClock)
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestEntity.class)
          .withJpaUnitTestEntities(TestEntity.class)
          .build();

  public TransactionManagerTest() {}

  @BeforeEach
  public void setUp() {
    inject.setStaticField(Ofy.class, "clock", fakeClock);
  }

  @TestTemplate
  public void inTransaction_returnsCorrespondingResult() {
    assertThat(tm().inTransaction()).isFalse();
    tm().transact(() -> assertThat(tm().inTransaction()).isTrue());
    assertThat(tm().inTransaction()).isFalse();
  }

  @TestTemplate
  public void assertInTransaction_throwsExceptionWhenNotInTransaction() {
    assertThrows(IllegalStateException.class, () -> tm().assertInTransaction());
    tm().transact(() -> tm().assertInTransaction());
    assertThrows(IllegalStateException.class, () -> tm().assertInTransaction());
  }

  @TestTemplate
  public void getTransactionTime_throwsExceptionWhenNotInTransaction() {
    FakeClock txnClock = fakeClock;
    txnClock.advanceOneMilli();
    assertThrows(IllegalStateException.class, () -> tm().getTransactionTime());
    tm().transact(() -> assertThat(tm().getTransactionTime()).isEqualTo(txnClock.nowUtc()));
    assertThrows(IllegalStateException.class, () -> tm().getTransactionTime());
  }

  @TestTemplate
  public void transact_hasNoEffectWithPartialSuccess() {
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
    assertThrows(
        RuntimeException.class,
        () ->
            tm()
                .transact(
                    () -> {
                      tm().saveNew(theEntity);
                      throw new RuntimeException();
                    }));
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
  }

  @TestTemplate
  public void transact_reusesExistingTransaction() {
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().transact(() -> tm().saveNew(theEntity)));
    fakeClock.advanceOneMilli();
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isTrue();
  }

  @TestTemplate
  public void saveNew_succeeds() {
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveNew(theEntity));
    fakeClock.advanceOneMilli();
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isTrue();
    fakeClock.advanceOneMilli();
    assertThat(tm().transact(() -> tm().load(theEntity.key()))).isEqualTo(theEntity);
  }

  @TestTemplate
  public void saveAllNew_succeeds() {
    moreEntities.forEach(
        entity -> assertThat(tm().transact(() -> tm().checkExists(entity))).isFalse());
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveAllNew(moreEntities));
    fakeClock.advanceOneMilli();
    moreEntities.forEach(
        entity -> assertThat(tm().transact(() -> tm().checkExists(entity))).isTrue());
  }

  @TestTemplate
  public void saveNewOrUpdate_persistsNewEntity() {
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveNewOrUpdate(theEntity));
    fakeClock.advanceOneMilli();
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isTrue();
    fakeClock.advanceOneMilli();
    assertThat(tm().transact(() -> tm().load(theEntity.key()))).isEqualTo(theEntity);
  }

  @TestTemplate
  public void saveNewOrUpdate_updatesExistingEntity() {
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveNew(theEntity));
    fakeClock.advanceOneMilli();
    TestEntity persisted = tm().transact(() -> tm().load(theEntity.key()));
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    tm().transact(() -> tm().saveNewOrUpdate(theEntity));
    fakeClock.advanceOneMilli();
    persisted = tm().transact(() -> tm().load(theEntity.key()));
    fakeClock.advanceOneMilli();
    assertThat(persisted.data).isEqualTo("bar");
  }

  @TestTemplate
  public void saveNewOrUpdateAll_succeeds() {
    moreEntities.forEach(
        entity -> assertThat(tm().transact(() -> tm().checkExists(entity))).isFalse());
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveNewOrUpdateAll(moreEntities));
    fakeClock.advanceOneMilli();
    moreEntities.forEach(
        entity -> assertThat(tm().transact(() -> tm().checkExists(entity))).isTrue());
  }

  @TestTemplate
  public void update_succeeds() {
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveNew(theEntity));
    fakeClock.advanceOneMilli();
    TestEntity persisted =
        tm().transact(
                () ->
                    tm().load(
                            VKey.create(TestEntity.class, theEntity.name, Key.create(theEntity))));
    fakeClock.advanceOneMilli();
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    tm().transact(() -> tm().update(theEntity));
    fakeClock.advanceOneMilli();
    persisted = tm().transact(() -> tm().load(theEntity.key()));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @TestTemplate
  public void load_succeeds() {
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveNew(theEntity));
    fakeClock.advanceOneMilli();
    TestEntity persisted = tm().transact(() -> tm().load(theEntity.key()));
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @TestTemplate
  public void load_throwsOnMissingElement() {
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
    fakeClock.advanceOneMilli();
    assertThrows(
        NoSuchElementException.class, () -> tm().transact(() -> tm().load(theEntity.key())));
  }

  @TestTemplate
  public void maybeLoad_succeeds() {
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveNew(theEntity));
    fakeClock.advanceOneMilli();
    TestEntity persisted = tm().transact(() -> tm().maybeLoad(theEntity.key()).get());
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @TestTemplate
  public void maybeLoad_nonExistentObject() {
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
    fakeClock.advanceOneMilli();
    assertThat(tm().transact(() -> tm().maybeLoad(theEntity.key())).isPresent()).isFalse();
  }

  @TestTemplate
  public void delete_succeeds() {
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveNew(theEntity));
    fakeClock.advanceOneMilli();
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isTrue();
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().delete(theEntity.key()));
    fakeClock.advanceOneMilli();
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
  }

  @TestTemplate
  public void delete_returnsZeroWhenNoEntity() {
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().delete(theEntity.key()));
    fakeClock.advanceOneMilli();
    assertThat(tm().transact(() -> tm().checkExists(theEntity))).isFalse();
  }

  @Entity(name = "TestEntity")
  @javax.persistence.Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {
    @Id @javax.persistence.Id private String name;

    private String data;

    private TestEntity() {}

    private TestEntity(String name, String data) {
      this.name = name;
      this.data = data;
    }

    public VKey<TestEntity> key() {
      return VKey.create(TestEntity.class, name, Key.create(this));
    }
  }
}
