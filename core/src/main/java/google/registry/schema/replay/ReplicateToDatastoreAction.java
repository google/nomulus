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

import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.request.Action.Method.GET;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.persistence.transaction.Transaction;
import google.registry.persistence.transaction.TransactionEntity;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import java.io.IOException;
import java.util.List;
import javax.persistence.NoResultException;

/** Cron task to replicate from Cloud SQL to datastore. */
@Action(
    service = Action.Service.BACKEND,
    path = ReplicateToDatastoreAction.PATH,
    method = GET,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
class ReplicateToDatastoreAction implements Runnable {
  public static final String PATH = "/_dr/cron/replicateToDatastore";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Number of transactions to fetch from SQL. The rationale for 200 is that we're processing these
   * every minute and our production instance currently does about 2 mutations per second, so this
   * should generally be enough to scoop up all of the transactions for the past minute.
   */
  public static final int BATCH_SIZE = 200;

  @VisibleForTesting
  List<TransactionEntity> getTransactionBatch() {
    // Get the next batch of transactions that we haven't replicated.
    LastSqlTransaction lastSqlTxnBeforeBatch =
        ofyTm().doTransactionless(() -> LastSqlTransaction.load());
    try {
      return jpaTm()
          .transact(
              () ->
                  jpaTm()
                      .getEntityManager()
                      .createQuery(
                          "SELECT txn FROM TransactionEntity txn WHERE id >"
                              + " :lastId ORDER BY id")
                      .setParameter("lastId", lastSqlTxnBeforeBatch.getTransactionId())
                      .setMaxResults(BATCH_SIZE)
                      .getResultList());
    } catch (NoResultException e) {
      return ImmutableList.of();
    }
  }

  /**
   * Apply a transaction to datastore, returns true if there was a fatel error and the batch should
   * be aborted.
   */
  @VisibleForTesting
  boolean applyTransaction(TransactionEntity txnEntity) {
    return ofyTm()
        .transact(
            () -> {
              // Reload the last transaction id, which could possibly have changed.
              LastSqlTransaction lastSqlTxn = LastSqlTransaction.load();
              long nextTxnId = lastSqlTxn.getTransactionId() + 1;
              if (nextTxnId < txnEntity.getId()) {
                // We're missing a transaction.  This is bad.  Transaction ids are supposed to
                // increase monotonically, so we abort rather than applying anything out of
                // order.
                logger.atSevere().log(
                    "Missing transaction: last transaction id = %s, next available transaction "
                        + "= %s",
                    nextTxnId - 1, txnEntity.getId());
                return true;
              } else if (nextTxnId > txnEntity.getId()) {
                // We've already replayed this transaction.  This shouldn't happen, as GAE cron
                // is supposed to avoid overruns and this action shouldn't be executed from any
                // other context, but it's not harmful as we can just ignore the transaction.  Log
                // it so that we know about it and move on.
                logger.atWarning().log(
                    "Ignoring transaction %s, which appears to have already been applied.",
                    txnEntity.getId());
                return false;
              }

              // At this point, we know txnEntity is the correct next transaction, so write it
              // to datastore.
              try {
                Transaction.deserialize(txnEntity.getContents()).writeToDatastore();
              } catch (IOException e) {
                throw new RuntimeException(e);
              }

              // Write the updated last transaction id to datastore as part of this datastore
              // transaction.
              ofy().save().entity(lastSqlTxn.withNewTransactionId(nextTxnId));
              return false;
            });
  }

  @Override
  public void run() {
    for (TransactionEntity txnEntity : getTransactionBatch()) {
      if (applyTransaction(txnEntity)) {
        break;
      }
    }
  }
}
