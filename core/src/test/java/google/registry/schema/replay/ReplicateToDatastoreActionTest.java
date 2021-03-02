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
import static google.registry.testing.LogsSubject.assertAboutLogs;

import com.google.common.testing.TestLogHandler;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.config.RegistryConfig;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.TransactionEntity;
import google.registry.testing.AppEngineExtension;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
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

  TestLogHandler logHandler;

  public ReplicateToDatastoreActionTest() {}

  @BeforeEach
  public void setUp() {
    RegistryConfig.overrideCloudSqlReplicateTransactions(true);
    logHandler = new TestLogHandler();
    Logger.getLogger(ReplicateToDatastoreAction.class.getCanonicalName()).addHandler(logHandler);
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
              jpaTm().insert(foo);
              jpaTm().insert(bar);
            });
    task.run();

    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(foo.key()))).isEqualTo(foo);
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(bar.key()))).isEqualTo(bar);
    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(baz.key())).isPresent()).isFalse();

    jpaTm()
        .transact(
            () -> {
              jpaTm().delete(bar.key());
              jpaTm().insert(baz);
            });
    task.run();

    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(bar.key()).isPresent())).isFalse();
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(baz.key()))).isEqualTo(baz);
  }

  @Test
  public void testReplayFromLastTxn() {
    TestEntity foo = new TestEntity("foo");
    TestEntity bar = new TestEntity("bar");

    // Write a transaction containing "foo".
    jpaTm().transact(() -> jpaTm().insert(foo));
    task.run();

    // Verify that it propagated to datastore, then remove "foo" directly from datastore.
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(foo.key()))).isEqualTo(foo);
    ofyTm().transact(() -> ofyTm().delete(foo.key()));

    // Write "bar"
    jpaTm().transact(() -> jpaTm().insert(bar));
    task.run();

    // If we replayed only the most recent transaction, we should have "bar" but not "foo".
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(bar.key()))).isEqualTo(bar);
    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(foo.key()).isPresent())).isFalse();
  }

  @Test
  public void testUnintentionalConcurrency() {
    TestEntity foo = new TestEntity("foo");
    TestEntity bar = new TestEntity("bar");

    // Write a transaction and run just the batch fetch.
    jpaTm().transact(() -> jpaTm().insert(foo));
    List<TransactionEntity> txns1 = task.getTransactionBatch();
    assertThat(txns1).hasSize(1);

    // Write a second transaction and do another batch fetch.
    jpaTm().transact(() -> jpaTm().insert(bar));
    List<TransactionEntity> txns2 = task.getTransactionBatch();
    assertThat(txns2).hasSize(2);

    // Apply the first batch.
    assertThat(task.applyTransaction(txns1.get(0))).isFalse();

    // Remove the foo record so we can ensure that this transaction doesn't get doublle-played.
    ofyTm().transact(() -> ofyTm().delete(foo.key()));

    // Apply the second batch.
    for (TransactionEntity txn : txns2) {
      assertThat(task.applyTransaction(txn)).isFalse();
    }

    // Verify that the first transaction didn't get replayed but the second one did.
    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(foo.key()).isPresent())).isFalse();
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(bar.key()))).isEqualTo(bar);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING, "Ignoring transaction 1, which appears to have already been applied.");
  }

  @Test
  public void testMissingTransactions() {
    // Write a transaction (should have a transaction id of 1).
    TestEntity foo = new TestEntity("foo");
    jpaTm().transact(() -> jpaTm().insert(foo));

    // Force the last transaction id back to -1 so that we look for transaction 0.
    ofyTm().transact(() -> ofyTm().insert(new LastSqlTransaction(-1)));

    List<TransactionEntity> txns = task.getTransactionBatch();
    assertThat(txns).hasSize(1);
    assertThat(task.applyTransaction(txns.get(0))).isTrue();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.SEVERE,
            "Missing transaction: last transaction id = -1, next available transaction = 1");
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
