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

import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.request.Action.Method.GET;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.joda.time.Duration.standardHours;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DatabaseMigrationStateSchedule.ReplayDirection;
import google.registry.model.server.Lock;
import google.registry.persistence.transaction.Transaction;
import google.registry.persistence.transaction.TransactionEntity;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.RequestStatusChecker;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.persistence.NoResultException;
import org.joda.time.Duration;

/** Cron task to replicate from Cloud SQL to datastore. */
@Action(
    service = Action.Service.BACKEND,
    path = ReplicateToDatastoreAction.PATH,
    method = GET,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
@VisibleForTesting
@DeleteAfterMigration
public class ReplicateToDatastoreAction implements Runnable {
  public static final String PATH = "/_dr/cron/replicateToDatastore";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Name of the lock that ensures sequential execution of replays. */
  public static final String REPLICATE_TO_DATASTORE_LOCK_NAME =
      ReplicateToDatastoreAction.class.getSimpleName();

  /**
   * Number of transactions to fetch from SQL. The rationale for 200 is that we're processing these
   * every minute and our production instance currently does about 2 mutations per second, so this
   * should generally be enough to scoop up all of the transactions for the past minute.
   */
  public static final int BATCH_SIZE = 200;

  public static final Duration REPLICATE_TO_DATASTORE_LOCK_LEASE_LENGTH = standardHours(1);

  private final Clock clock;
  private final RequestStatusChecker requestStatusChecker;
  private final Response response;

  @Inject
  public ReplicateToDatastoreAction(
      Clock clock, RequestStatusChecker requestStatusChecker, Response response) {
    this.clock = clock;
    this.requestStatusChecker = requestStatusChecker;
    this.response = response;
  }

  @VisibleForTesting
  public List<TransactionEntity> getTransactionBatchAtSnapshot() {
    return getTransactionBatchAtSnapshot(Optional.empty());
  }

  static List<TransactionEntity> getTransactionBatchAtSnapshot(Optional<String> snapshotId) {
    // Get the next batch of transactions that we haven't replicated.
    LastSqlTransaction lastSqlTxnBeforeBatch = ofyTm().transact(LastSqlTransaction::load);
    try {
      return jpaTm()
          .transactWithoutBackup(
              () -> {
                snapshotId.ifPresent(jpaTm()::setDatabaseSnapshot);
                return jpaTm()
                    .query(
                        "SELECT txn FROM TransactionEntity txn WHERE id >" + " :lastId ORDER BY id",
                        TransactionEntity.class)
                    .setParameter("lastId", lastSqlTxnBeforeBatch.getTransactionId())
                    .setMaxResults(BATCH_SIZE)
                    .getResultList();
              });
    } catch (NoResultException e) {
      return ImmutableList.of();
    }
  }

  /**
   * Apply a transaction to Datastore, returns true if there was a fatal error and the batch should
   * be aborted.
   *
   * <p>Throws an exception if a fatal error occurred and the batch should be aborted
   */
  @VisibleForTesting
  public static void applyTransaction(TransactionEntity txnEntity) {
    logger.atInfo().log("Applying a single transaction Cloud SQL -> Cloud Datastore.");
    try (UpdateAutoTimestamp.DisableAutoUpdateResource disabler =
        UpdateAutoTimestamp.disableAutoUpdate()) {
      ofyTm()
          .transact(
              () -> {
                // Reload the last transaction id, which could possibly have changed.
                LastSqlTransaction lastSqlTxn = LastSqlTransaction.load();
                long nextTxnId = lastSqlTxn.getTransactionId() + 1;
                if (nextTxnId < txnEntity.getId()) {
                  // Missing transaction id.  This can happen normally.  If a transaction gets
                  // rolled back, the sequence counter doesn't.
                  logger.atWarning().log(
                      "Ignoring transaction %s, which does not exist.", nextTxnId);
                } else if (nextTxnId > txnEntity.getId()) {
                  // We've already replayed this transaction.  This shouldn't happen, as GAE cron
                  // is supposed to avoid overruns and this action shouldn't be executed from any
                  // other context, but it's not harmful as we can just ignore the transaction.  Log
                  // it so that we know about it and move on.
                  logger.atWarning().log(
                      "Ignoring transaction %s, which appears to have already been applied.",
                      txnEntity.getId());
                  return;
                }

                logger.atInfo().log(
                    "Applying transaction %s to Cloud Datastore.", txnEntity.getId());

                // At this point, we know txnEntity is the correct next transaction, so write it
                // to Datastore.
                try {
                  Transaction.deserialize(txnEntity.getContents()).writeToDatastore();
                } catch (IOException e) {
                  throw new RuntimeException("Error during transaction deserialization", e);
                }

                // Write the updated last transaction id to Datastore as part of this Datastore
                // transaction.
                auditedOfy()
                    .saveIgnoringReadOnlyWithoutBackup()
                    .entity(lastSqlTxn.cloneWithNewTransactionId(nextTxnId));
                logger.atInfo().log(
                    "Finished applying single transaction Cloud SQL -> Cloud Datastore.");
              });
    }
  }

  @Override
  public void run() {
    MigrationState state = DatabaseMigrationStateSchedule.getValueAtTime(clock.nowUtc());
    if (!state.getReplayDirection().equals(ReplayDirection.SQL_TO_DATASTORE)) {
      String message =
          String.format(
              "Skipping ReplicateToDatastoreAction because we are in migration phase %s.", state);
      logger.atInfo().log(message);
      // App Engine will retry on any non-2xx status code, which we don't want in this case.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload(message);
      return;
    }
    Optional<Lock> lock =
        Lock.acquireSql(
            REPLICATE_TO_DATASTORE_LOCK_NAME,
            null,
            REPLICATE_TO_DATASTORE_LOCK_LEASE_LENGTH,
            requestStatusChecker,
            false);
    if (!lock.isPresent()) {
      String message = "Can't acquire ReplicateToDatastoreAction lock, aborting.";
      logger.atSevere().log(message);
      // App Engine will retry on any non-2xx status code, which we don't want in this case.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload(message);
      return;
    }
    try {
      logger.atInfo().log("Processing transaction replay batch Cloud SQL -> Cloud Datastore.");
      int numTransactionsReplayed = replayAllTransactions();
      String resultMessage =
          String.format(
              "Replayed %d transaction(s) from Cloud SQL -> Datastore.", numTransactionsReplayed);
      logger.atInfo().log(resultMessage);
      response.setPayload(resultMessage);
      response.setStatus(SC_OK);
    } catch (Throwable t) {
      String message = "Errored out replaying files.";
      logger.atSevere().withCause(t).log(message);
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(message);
    } finally {
      lock.ifPresent(Lock::releaseSql);
    }
  }

  private int replayAllTransactions() {
    return replayAllTransactions(Optional.empty());
  }

  public static int replayAllTransactions(Optional<String> snapshotId) {
    int numTransactionsReplayed = 0;
    List<TransactionEntity> transactionBatch;
    do {
      transactionBatch = getTransactionBatchAtSnapshot(snapshotId);
      for (TransactionEntity transaction : transactionBatch) {
        applyTransaction(transaction);
        numTransactionsReplayed++;
      }
    } while (!transactionBatch.isEmpty());
    return numTransactionsReplayed;
  }
}
