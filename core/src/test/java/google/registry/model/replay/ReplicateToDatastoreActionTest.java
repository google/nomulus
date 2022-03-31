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
import static google.registry.model.replay.ReplicateToDatastoreAction.applyTransaction;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.testing.TestLogHandler;
import com.google.common.truth.Truth8;
import com.googlecode.objectify.Key;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.Ofy;
import google.registry.model.server.Lock;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTransactionManagerImpl;
import google.registry.persistence.transaction.TransactionEntity;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.testing.ReplayExtension;
import google.registry.testing.TestObject;
import google.registry.util.RequestStatusChecker;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.RetryingTest;

public class ReplicateToDatastoreActionTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(AllocationToken.class, Lock.class, TestObject.class)
          .withJpaUnitTestEntities(AllocationToken.class, Lock.class, TestObject.class)
          .withClock(fakeClock)
          .build();

  @RegisterExtension final InjectExtension injectExtension = new InjectExtension();

  private final TestLogHandler logHandler = new TestLogHandler();
  private ReplicateToDatastoreAction action;
  private FakeResponse response;

  @BeforeEach
  void setUp() {
    resetAction();
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
    assumeTrue(ReplayExtension.replayTestsEnabled());

    TestObject foo = TestObject.create("foo");
    TestObject bar = TestObject.create("bar");
    TestObject baz = TestObject.create("baz");

    insertInDb(foo, bar);
    runAndVerifySuccess();

    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(foo.key()))).isEqualTo(foo);
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(bar.key()))).isEqualTo(bar);
    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(baz.key())).isPresent()).isFalse();

    jpaTm()
        .transact(
            () -> {
              jpaTm().delete(bar.key());
              jpaTm().insert(baz);
            });
    runAndVerifySuccess();

    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(bar.key()).isPresent())).isFalse();
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(baz.key()))).isEqualTo(baz);
  }

  @RetryingTest(4)
  void testReplayFromLastTxn() {
    assumeTrue(ReplayExtension.replayTestsEnabled());

    TestObject foo = TestObject.create("foo");
    TestObject bar = TestObject.create("bar");

    // Write a transaction containing "foo".
    insertInDb(foo);
    runAndVerifySuccess();

    // Verify that it propagated to datastore, then remove "foo" directly from datastore.
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(foo.key()))).isEqualTo(foo);
    ofyTm().transact(() -> ofyTm().delete(foo.key()));

    // Write "bar"
    insertInDb(bar);
    runAndVerifySuccess();

    // If we replayed only the most recent transaction, we should have "bar" but not "foo".
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(bar.key()))).isEqualTo(bar);
    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(foo.key()).isPresent())).isFalse();
  }

  @RetryingTest(4)
  void testUnintentionalConcurrency() {
    assumeTrue(ReplayExtension.replayTestsEnabled());

    TestObject foo = TestObject.create("foo");
    TestObject bar = TestObject.create("bar");

    // Write a transaction and run just the batch fetch.
    insertInDb(foo);
    List<TransactionEntity> txns1 = action.getTransactionBatchAtSnapshot();
    assertThat(txns1).hasSize(1);

    // Write a second transaction and do another batch fetch.
    insertInDb(bar);
    List<TransactionEntity> txns2 = action.getTransactionBatchAtSnapshot();
    assertThat(txns2).hasSize(2);

    // Apply the first batch.
    applyTransaction(txns1.get(0));

    // Remove the foo record so we can ensure that this transaction doesn't get doublle-played.
    ofyTm().transact(() -> ofyTm().delete(foo.key()));

    // Apply the second batch.
    for (TransactionEntity txn : txns2) {
      applyTransaction(txn);
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
  void testCreateAutoTimestamp() {
    // Verify that fields populated by the DB (e.g. CreateAutoTimestamp) correctly get populated in
    // both databases.
    assumeTrue(ReplayExtension.replayTestsEnabled());

    AllocationToken allocationToken =
        new AllocationToken.Builder().setToken("abc123").setTokenType(TokenType.SINGLE_USE).build();
    insertInDb(allocationToken);
    runAndVerifySuccess();

    assertThat(ofyTm().transact(() -> ofyTm().loadByEntity(allocationToken)))
        .isEqualTo(jpaTm().transact(() -> jpaTm().loadByEntity(allocationToken)));
  }

  @RetryingTest(4)
  void testNoTransactionIdUpdate() {
    // Create an object.
    TestObject foo = TestObject.create("foo");
    insertInDb(foo);

    // Fail two transactions.
    for (int i = 0; i < 2; ++i) {
      try {
        jpaTm()
            .transact(
                () -> {
                  jpaTm().delete(foo.key());
                  // Explicitly save the transaction entity to force the id update.
                  jpaTm().insert(new TransactionEntity(new byte[] {1, 2, 3}));
                  throw new RuntimeException("fail!!!");
                });
      } catch (Exception e) {
        logger.atInfo().log("Got expected exception.");
      }
    }

    TestObject bar = TestObject.create("bar");
    insertInDb(bar);

    // Make sure we have only the expected transaction ids.
    List<TransactionEntity> txns = action.getTransactionBatchAtSnapshot();
    assertThat(txns).hasSize(2);
    for (TransactionEntity txn : txns) {
      assertThat(txn.getId()).isNotEqualTo(2);
      assertThat(txn.getId()).isNotEqualTo(3);
      applyTransaction(txn);
    }

    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(foo.key()))).isEqualTo(foo);
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(bar.key()))).isEqualTo(bar);
    assertThat(ofyTm().transact(() -> LastSqlTransaction.load()).getTransactionId()).isEqualTo(4);
  }

  @Test
  void testTransactionGapReplay() {
    insertInDb(TestObject.create("foo"));
    DeferredCommit deferred = new DeferredCommit(fakeClock);
    TestObject bar = TestObject.create("bar");
    deferred.transact(() -> jpaTm().insert(bar));
    TestObject baz = TestObject.create("baz");
    insertInDb(baz);

    action.run();
    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(bar.key())).isPresent()).isFalse();
    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(baz.key()))).isEqualTo(baz);
    VKey<ReplayGap> gapKey = VKey.createOfy(ReplayGap.class, Key.create(ReplayGap.class, 2));
    Truth8.assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(gapKey))).isPresent();

    deferred.commit();
    resetAction();
    action.run();

    assertThat(ofyTm().transact(() -> ofyTm().loadByKey(bar.key()))).isEqualTo(bar);
    // Verify that the gap record has been cleaned up.
    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(gapKey).isPresent())).isFalse();
  }

  /** Verify that we can handle creation and deletion of > 25 gap records. */
  @Test
  void testLargeNumberOfGaps() {
    // Fail thirty transactions.
    for (int i = 0; i < 30; ++i) {
      try {
        jpaTm()
            .transact(
                () -> {
                  insertInDb(TestObject.create("foo"));
                  // Explicitly save the transaction entity to force the id update.
                  jpaTm().insert(new TransactionEntity(new byte[] {1, 2, 3}));
                  throw new RuntimeException("fail!!!");
                });
      } catch (Exception e) {
        ;
      }
    }

    TestObject bar = TestObject.create("bar");
    insertInDb(bar);

    // Verify that the transaction was successfully applied and that we have generated 30 gap
    // records.
    action.run();
    Truth8.assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(bar.key()))).isPresent();
    assertThat(ofyTm().loadAllOf(ReplayGap.class).size()).isEqualTo(30);

    // Verify that we can clean up this many gap records after expiration.
    fakeClock.advanceBy(Duration.millis(ReplicateToDatastoreAction.MAX_GAP_RETENTION_MILLIS + 1));
    resetAction();
    action.run();
    assertThat(ofyTm().loadAllOf(ReplayGap.class).size()).isEqualTo(0);
  }

  @Test
  void testGapRecordExpiration() {
    insertInDb(TestObject.create("foo"));

    // Fail a transaction, create a gap.
    try {
      jpaTm()
          .transact(
              () -> {
                jpaTm().insert(TestObject.create("other"));
                // Explicitly save the transaction entity to force the id update.
                jpaTm().insert(new TransactionEntity(new byte[] {1, 2, 3}));
                throw new RuntimeException("fail!!!");
              });
    } catch (Exception e) {
      logger.atInfo().log("Got expected exception.");
    }

    insertInDb(TestObject.create("bar"));

    action.run();

    // Verify that the gap record has been created
    VKey<ReplayGap> gapKey = VKey.createOfy(ReplayGap.class, Key.create(ReplayGap.class, 2));
    Truth8.assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(gapKey))).isPresent();

    fakeClock.advanceBy(Duration.millis(ReplicateToDatastoreAction.MAX_GAP_RETENTION_MILLIS + 1));
    resetAction();
    action.run();

    // Verify that the gap record has been destroyed.
    assertThat(ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(gapKey)).isPresent()).isFalse();
  }

  @Test
  void testBeforeDatastoreSaveCallback() {
    assumeTrue(ReplayExtension.replayTestsEnabled());

    TestObject testObject = TestObject.create("foo");
    insertInDb(testObject);
    action.run();
    assertThat(ofyTm().loadAllOf(TestObject.class)).containsExactly(testObject);
    assertThat(TestObject.beforeDatastoreSaveCallCount).isEqualTo(1);
  }

  @Test
  void testNotInMigrationState_doesNothing() {
    assumeTrue(ReplayExtension.replayTestsEnabled());

    // set a schedule that backtracks the current status to DATASTORE_PRIMARY
    DateTime now = fakeClock.nowUtc();
    jpaTm()
        .transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    ImmutableSortedMap.<DateTime, MigrationState>naturalOrder()
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusHours(1), MigrationState.DATASTORE_PRIMARY)
                        .put(START_OF_TIME.plusHours(2), MigrationState.DATASTORE_PRIMARY_NO_ASYNC)
                        .put(START_OF_TIME.plusHours(3), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusHours(4), MigrationState.SQL_PRIMARY)
                        .put(now.plusHours(1), MigrationState.SQL_PRIMARY_READ_ONLY)
                        .put(now.plusHours(2), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .put(now.plusHours(3), MigrationState.DATASTORE_PRIMARY)
                        .build()));
    fakeClock.advanceBy(Duration.standardDays(1));

    insertInDb(TestObject.create("foo"));
    action.run();
    // Replication shouldn't have happened
    assertThat(ofyTm().loadAllOf(TestObject.class)).isEmpty();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Skipping ReplicateToDatastoreAction because we are in migration phase "
                + "DATASTORE_PRIMARY.");
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo(
            "Skipping ReplicateToDatastoreAction because we are in migration phase"
                + " DATASTORE_PRIMARY.");
  }

  @Test
  void testFailure_cannotAcquireLock() {
    assumeTrue(ReplayExtension.replayTestsEnabled());

    RequestStatusChecker requestStatusChecker = mock(RequestStatusChecker.class);
    when(requestStatusChecker.getLogId()).thenReturn("logId");
    Truth8.assertThat(
            Lock.acquireSql(
                ReplicateToDatastoreAction.class.getSimpleName(),
                null,
                Duration.standardHours(1),
                requestStatusChecker,
                false))
        .isPresent();
    fakeClock.advanceOneMilli();

    resetAction();
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo("Can't acquire ReplicateToDatastoreAction lock, aborting.");
  }

  private void runAndVerifySuccess() {
    resetAction();
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo("Replayed 1 transaction(s) from Cloud SQL -> Datastore.");
  }

  private void resetAction() {
    response = new FakeResponse();
    RequestStatusChecker requestStatusChecker = mock(RequestStatusChecker.class);
    when(requestStatusChecker.getLogId()).thenReturn("logId");
    action = new ReplicateToDatastoreAction(fakeClock, requestStatusChecker, response);
  }

  /**
   * Deep fake of EntityManagerFactory -> EntityManager -> EntityTransaction that allows us to defer
   * the actual commit until after the other transactions are replayed.
   */
  static class DeferredCommit {

    FakeClock clock;
    EntityTransaction realTransaction;

    DeferredCommit(FakeClock clock) {
      this.clock = clock;
    }

    private static <T> T makeProxy(
        Class<T> iface, T delegate, String method, Supplier<?> supplier) {
      return (T)
          Proxy.newProxyInstance(
              delegate.getClass().getClassLoader(),
              new Class[] {iface},
              (proxy, meth, args) -> {
                if (meth.getName().equals(method)) {
                  return supplier.get();
                } else {
                  return meth.invoke(delegate, args);
                }
              });
    }

    EntityManager createEntityManagerProxy(EntityManager orgEntityManager) {
      return makeProxy(
          EntityManager.class,
          orgEntityManager,
          "getTransaction",
          () ->
              makeProxy(
                  EntityTransaction.class,
                  realTransaction = orgEntityManager.getTransaction(),
                  "commit",
                  () -> null));
    }

    void commit() {
      realTransaction.commit();
    }

    void transact(Runnable runnable) {
      EntityManagerFactory orgEmf =
          jpaTm().transact(() -> jpaTm().getEntityManager().getEntityManagerFactory());
      EntityManagerFactory emfProxy =
          makeProxy(
              EntityManagerFactory.class,
              orgEmf,
              "createEntityManager",
              () -> createEntityManagerProxy(orgEmf.createEntityManager()));

      new JpaTransactionManagerImpl(emfProxy, clock).transact(runnable);
    }
  }
}
