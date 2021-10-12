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

package google.registry.testing;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.ReplayQueue;
import google.registry.model.ofy.TransactionInfo;
import google.registry.model.replay.DatastoreEntity;
import google.registry.model.replay.ReplicateToDatastoreAction;
import google.registry.model.replay.SqlEntity;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTransactionManagerImpl;
import google.registry.persistence.transaction.Transaction;
import google.registry.persistence.transaction.Transaction.Delete;
import google.registry.persistence.transaction.Transaction.Mutation;
import google.registry.persistence.transaction.Transaction.Update;
import google.registry.persistence.transaction.TransactionEntity;
import google.registry.util.RequestStatusChecker;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;

/**
 * A JUnit extension that replays datastore transactions against postgresql.
 *
 * <p>This extension must be ordered before AppEngineExtension so that the test entities saved in
 * that extension are also replayed. If AppEngineExtension is not used,
 * JpaTransactionManagerExtension must be, and this extension should be ordered _after_
 * JpaTransactionManagerExtension so that writes to SQL work.
 *
 * <p>If the "compare" flag is set in the constructor, this will also compare all touched objects in
 * both databases after performing the replay.
 */
public class ReplayExtension implements BeforeEachCallback, AfterEachCallback {

  FakeClock clock;
  boolean compare;
  boolean replayed = false;
  boolean inOfyContext;
  InjectExtension injectExtension = new InjectExtension();
  @Nullable ReplicateToDatastoreAction sqlToDsReplicator;

  private ReplayExtension(
      FakeClock clock, boolean compare, @Nullable ReplicateToDatastoreAction sqlToDsReplicator) {
    this.clock = clock;
    this.compare = compare;
    this.sqlToDsReplicator = sqlToDsReplicator;
  }

  public static ReplayExtension createWithCompare(FakeClock clock) {
    return new ReplayExtension(clock, true, null);
  }

  /**
   * Create a replay extension that replays from SQL to cloud datastore when running in SQL mode.
   */
  public static ReplayExtension createWithDoubleReplay(FakeClock clock) {
    return new ReplayExtension(
        clock,
        true,
        new ReplicateToDatastoreAction(
            clock, Mockito.mock(RequestStatusChecker.class), new FakeResponse()));
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    // Use a single bucket to expose timestamp inversion problems. This typically happens when
    // a test with this extension rolls back the fake clock in the setup method, creating inverted
    // timestamp with the canned data preloaded by AppengineExtension. The solution is to move
    // the clock change to the test's constructor.
    injectExtension.setStaticField(
        CommitLogBucket.class, "bucketIdSupplier", Suppliers.ofInstance(1));
    DatabaseHelper.setClock(clock);
    DatabaseHelper.setAlwaysSaveWithBackup(true);
    ReplayQueue.clear();

    // When running in JPA mode with double replay enabled, enable JPA transaction replication.
    // Note that we can't just use isOfy() here because this extension gets run before the dual-test
    // transaction manager gets injected.
    inOfyContext = DualDatabaseTestInvocationContextProvider.inOfyContext(context);
    if (sqlToDsReplicator != null && !inOfyContext) {
      JpaTransactionManagerImpl.setReplaySqlToDatastoreOverrideForTest(true);
    }

    context.getStore(ExtensionContext.Namespace.GLOBAL).put(ReplayExtension.class, this);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // This ensures that we do the replay even if we're not called from AppEngineExtension.  It
    // is safe to call replay() twice, as the method ensures idempotence.
    replay();
    injectExtension.afterEach(context);
    if (sqlToDsReplicator != null) {
      JpaTransactionManagerImpl.removeReplaySqlToDsOverrideForTest();
    }
  }

  private static ImmutableSet<String> NON_REPLICATED_TYPES =
      ImmutableSet.of(
          "PremiumList",
          "PremiumListRevision",
          "PremiumListEntry",
          "ReservedList",
          "RdeRevision",
          "ServerSecret",
          "SignedMarkRevocationList",
          "ClaimsListShard",
          "TmchCrl",
          "EppResourceIndex",
          "ForeignKeyIndex",
          "ForeignKeyHostIndex",
          "ForeignKeyContactIndex",
          "ForeignKeyDomainIndex");

  public void replay() {
    if (!replayed) {
      if (inOfyContext) {
        replayToSql();
      } else {
        // Disable database backups.  For unknown reason, if we don't do this we get residual commit
        // log entries that cause timestamp inversions in other tests.
        DatabaseHelper.setAlwaysSaveWithBackup(false);

        // Do the ofy replay.
        replayToOfy();

        // Clean out anything that ends up in the replay queue.
        ReplayQueue.clear();
      }
      replayed = true;
    }
  }

  private void replayToSql() {
    DatabaseHelper.setAlwaysSaveWithBackup(false);
    ImmutableMap<Key<?>, Object> changes = ReplayQueue.replay();

    // Compare JPA to OFY, if requested.
    if (compare) {
      for (ImmutableMap.Entry<Key<?>, Object> entry : changes.entrySet()) {
        // Don't verify non-replicated types.
        if (NON_REPLICATED_TYPES.contains(entry.getKey().getKind())) {
          continue;
        }

        // Since the object may have changed in datastore by the time we're doing the replay, we
        // have to compare the current value in SQL (which we just mutated) against the value that
        // we originally would have persisted (that being the object in the entry).
        VKey<?> vkey = VKey.from(entry.getKey());
        jpaTm()
            .transact(
                () -> {
                  Optional<?> jpaValue = jpaTm().loadByKeyIfPresent(vkey);
                  if (entry.getValue().equals(TransactionInfo.Delete.SENTINEL)) {
                    assertThat(jpaValue.isPresent()).isFalse();
                  } else {
                    ImmutableObject immutJpaObject = (ImmutableObject) jpaValue.get();
                    assertAboutImmutableObjects().that(immutJpaObject).hasCorrectHashValue();
                    assertAboutImmutableObjects()
                        .that(immutJpaObject)
                        .isEqualAcrossDatabases(
                            (ImmutableObject)
                                ((DatastoreEntity) entry.getValue()).toSqlEntity().get());
                  }
                });
      }
    }
  }

  private void replayToOfy() {
    if (sqlToDsReplicator == null) {
      return;
    }

    List<TransactionEntity> transactionBatch;
    do {
      transactionBatch = sqlToDsReplicator.getTransactionBatch();
      for (TransactionEntity txn : transactionBatch) {
        sqlToDsReplicator.applyTransaction(txn);
        if (compare) {
          ofyTm().transact(() -> compareSqlTransaction(txn));
        }
        clock.advanceOneMilli();
      }
    } while (!transactionBatch.isEmpty());
  }

  /** Verifies that the replaying the SQL transaction created the same entities in Datastore. */
  private void compareSqlTransaction(TransactionEntity transactionEntity) {
    Transaction transaction;
    try {
      transaction = Transaction.deserialize(transactionEntity.getContents());
    } catch (IOException e) {
      throw new RuntimeException("Error during transaction deserialization.", e);
    }
    for (Mutation mutation : transaction.getMutations()) {
      if (mutation instanceof Update) {
        Update update = (Update) mutation;
        ImmutableObject fromTransactionEntity = (ImmutableObject) update.getEntity();
        ImmutableObject fromDatastore = ofyTm().loadByEntity(fromTransactionEntity);
        if (fromDatastore instanceof SqlEntity) {
          // We store the Datastore entity in the transaction, so use that if necessary
          fromDatastore = (ImmutableObject) ((SqlEntity) fromDatastore).toDatastoreEntity().get();
        }
        assertAboutImmutableObjects().that(fromDatastore).hasCorrectHashValue();
        assertAboutImmutableObjects()
            .that(fromDatastore)
            .isEqualAcrossDatabases(fromTransactionEntity);
      } else {
        Delete delete = (Delete) mutation;
        VKey<?> key = delete.getKey();
        assertWithMessage(String.format("Expected key %s to not exist in Datastore", key))
            .that(ofyTm().exists(key))
            .isFalse();
      }
    }
  }
}
