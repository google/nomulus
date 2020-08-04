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

package google.registry.schema.replay;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.config.RegistryConfig;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class ReplicateToDatastoreActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestEntity.class)
          .withJpaUnitTestEntities(TestEntity.class)
          .build();

  ReplicateToDatastoreAction task = new ReplicateToDatastoreAction();

  public ReplicateToDatastoreActionTest() {}

  @BeforeEach
  public void setUp() {
    RegistryConfig.overrideCloudSqlReplicateTransactions(true);
  }

  @AfterEach
  public void tearDown() {
    RegistryConfig.overrideCloudSqlReplicateTransactions(false);
  }

  @Test
  public void testReplication() {
    TestEntity foo = new TestEntity("foo");
    TestEntity bar = new TestEntity("bar");
    TestEntity baz = new TestEntity("baz");

    jpaTm()
        .transact(
            () -> {
              jpaTm().saveNew(foo);
              jpaTm().saveNew(bar);
            });
    task.run();

    assertThat(ofyTm().transact(() -> ofyTm().load(foo.key()))).isEqualTo(foo);
    assertThat(ofyTm().transact(() -> ofyTm().load(bar.key()))).isEqualTo(bar);
    assertThat(ofyTm().transact(() -> ofyTm().maybeLoad(baz.key())).isPresent()).isFalse();

    jpaTm()
        .transact(
            () -> {
              jpaTm().delete(bar.key());
              jpaTm().saveNew(baz);
            });
    task.run();

    assertThat(ofyTm().transact(() -> ofyTm().maybeLoad(bar.key()).isPresent())).isFalse();
    assertThat(ofyTm().transact(() -> ofyTm().load(baz.key()))).isEqualTo(baz);
  }

  @Test
  public void testReplayFromLastTxn() {
    TestEntity foo = new TestEntity("foo");
    TestEntity bar = new TestEntity("bar");

    // Write a transaction containing "foo".
    jpaTm().transact(() -> jpaTm().saveNew(foo));
    task.run();

    // Verify that it propagated to datastore, then remove "foo" directly from datastore.
    assertThat(ofyTm().transact(() -> ofyTm().load(foo.key()))).isEqualTo(foo);
    ofyTm().transact(() -> ofyTm().delete(foo.key()));

    // Write "bar"
    jpaTm().transact(() -> jpaTm().saveNew(bar));
    task.run();

    // If we replayed only the most recent transaction, we should have "bar" but not "foo".
    assertThat(ofyTm().transact(() -> ofyTm().load(bar.key()))).isEqualTo(bar);
    assertThat(ofyTm().transact(() -> ofyTm().maybeLoad(foo.key()).isPresent())).isFalse();
  }

  @Entity(name = "ReplicationTestEntity")
  @javax.persistence.Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {
    @Id @javax.persistence.Id private String name;

    private TestEntity() {}

    private TestEntity(String name) {
      this.name = name;
    }

    public VKey<TestEntity> key() {
      return VKey.create(TestEntity.class, name, Key.create(this));
    }
  }
}
