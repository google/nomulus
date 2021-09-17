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

package google.registry.model.replay;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.testing.TestLogHandler;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.Ofy;
import google.registry.persistence.transaction.TransactionEntity;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestObject;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.RetryingTest;

public class ReplicateToDatastoreActionTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestObject.class)
          .withJpaUnitTestEntities(TestObject.class)
          .withClock(fakeClock)
          .build();

  @RegisterExtension final InjectExtension injectExtension = new InjectExtension();

  private final ReplicateToDatastoreAction task = new ReplicateToDatastoreAction(fakeClock);
  private final TestLogHandler logHandler = new TestLogHandler();

  @BeforeEach
  void setUp() {
    injectExtension.setStaticField(Ofy.class, "clock", fakeClock);
    // Use a single bucket to expose timestamp inversion problems.
    injectExtension.setStaticField(
        CommitLogBucket.class, "bucketIdSupplier", Suppliers.ofInstance(1));
    fakeClock.setAutoIncrementByOneMilli();
    DatabaseHelper.setMigrationScheduleToSqlPrimary(fakeClock);
    Logger.getLogger(ReplicateToDatastoreAction.class.getCanonicalName()).addHandler(logHandler);
    fakeClock.advanceBy(Duration.standardDays(1));
    TestObject.beforeDatastoreSaveCallCount = 0;
  }

  @AfterEach
  void tearDown() {
    DatabaseHelper.removeDatabaseMigrationSchedule();
    fakeClock.disableAutoIncrement();
  }

  @RetryingTest(4)
  void testReplication() {
    TestObject foo = TestObject.create("foo");
    TestObject bar = TestObject.create("bar");
    TestObject baz = TestObject.create("baz");

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

  @RetryingTest(4)
  void testReplayFromLastTxn() {
    TestObject foo = TestObject.create("foo");
    TestObject bar = TestObject.create("bar");

    // Write a transaction containing "foo".
    insertInDb(foo);
    task.run();

    // Verify that it propagated to datastore, then remove "foo" directly from datastore.
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(foo.key()))).isEqualTo(foo);
    ofyTm().transact(() -> ofyTm().delete(foo.key()));

    // Write "bar"
    insertInDb(bar);
    task.run();

    // If we replayed only the most recent transaction, we should have "bar" but not "foo".
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(bar.key()))).isEqualTo(bar);
    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(foo.key()).isPresent())).isFalse();
  }

  @RetryingTest(4)
  void testUnintentionalConcurrency() {
    TestObject foo = TestObject.create("foo");
    TestObject bar = TestObject.create("bar");

    // Write a transaction and run just the batch fetch.
    insertInDb(foo);
    List<TransactionEntity> txns1 = task.getTransactionBatch();
    assertThat(txns1).hasSize(1);

    // Write a second transaction and do another batch fetch.
    insertInDb(bar);
    List<TransactionEntity> txns2 = task.getTransactionBatch();
    assertThat(txns2).hasSize(2);

    // Apply the first batch.
    task.applyTransaction(txns1.get(0));

    // Remove the foo record so we can ensure that this transaction doesn't get doublle-played.
    ofyTm().transact(() -> ofyTm().delete(foo.key()));

    // Apply the second batch.
    for (TransactionEntity txn : txns2) {
      task.applyTransaction(txn);
    }

    // Verify that the first transaction didn't get replayed but the second one did.
    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(foo.key()).isPresent())).isFalse();
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(bar.key()))).isEqualTo(bar);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING, "Ignoring transaction 1, which appears to have already been applied.");
  }

  @RetryingTest(4)
  void testMissingTransactions() {
    // Write a transaction (should have a transaction id of 1).
    TestObject foo = TestObject.create("foo");
    insertInDb(foo);

    // Force the last transaction id back to -1 so that we look for transaction 0.
    ofyTm().transact(() -> ofyTm().insert(new LastSqlTransaction(-1)));

    List<TransactionEntity> txns = task.getTransactionBatch();
    assertThat(txns).hasSize(1);
    assertThat(assertThrows(IllegalStateException.class, () -> task.applyTransaction(txns.get(0))))
        .hasMessageThat()
        .isEqualTo("Missing transaction: last txn id = -1, next available txn = 1");
  }

  @Test
  void testMissingTransactions_fullTask() {
    // Write a transaction (should have a transaction id of 1).
    TestObject foo = TestObject.create("foo");
    insertInDb(foo);

    // Force the last transaction id back to -1 so that we look for transaction 0.
    ofyTm().transact(() -> ofyTm().insert(new LastSqlTransaction(-1)));
    task.run();
    assertAboutLogs()
        .that(logHandler)
        .hasSevereLogWithCause(
            new IllegalStateException(
                "Missing transaction: last txn id = -1, next available txn = 1"));
  }

  @Test
  void testBeforeDatastoreSaveCallback() {
    TestObject testObject = TestObject.create("foo");
    jpaTm().transact(() -> jpaTm().put(testObject));
    task.run();
    assertThat(ofyTm().loadAllOf(TestObject.class)).containsExactly(testObject);
    assertThat(TestObject.beforeDatastoreSaveCallCount).isEqualTo(1);
  }

  @Test
  void testNotInMigrationState_doesNothing() {
    // set a schedule that backtracks the current status to DATASTORE_PRIMARY
    DateTime now = fakeClock.nowUtc();
    jpaTm()
        .transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    ImmutableSortedMap.<DateTime, MigrationState>naturalOrder()
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusHours(1), MigrationState.DATASTORE_PRIMARY)
                        .put(START_OF_TIME.plusHours(2), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusHours(3), MigrationState.SQL_PRIMARY)
                        .put(now.plusHours(1), MigrationState.SQL_PRIMARY_READ_ONLY)
                        .put(now.plusHours(2), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .put(now.plusHours(3), MigrationState.DATASTORE_PRIMARY)
                        .build()));
    fakeClock.advanceBy(Duration.standardDays(1));

    insertInDb(TestObject.create("foo"));
    task.run();
    // Replication shouldn't have happened
    assertThat(ofyTm().loadAllOf(TestObject.class)).isEmpty();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Skipping ReplicateToDatastoreAction because we are in migration phase "
                + "DATASTORE_PRIMARY.");
  }
}
