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

import google.registry.persistence.transaction.Transaction;
import google.registry.persistence.transaction.TransactionEntity;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import java.io.IOException;
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

  @Override
  public void run() {
    // We're limited by the number of objects that can be in a single datastore transaction,
    // therefore we have to do this one SQL transaction at a time.
    while (processSingleTransaction()) {}
  }

  private boolean processSingleTransaction() {
    return ofyTm()
        .transact(
            () -> {
              LastSqlTransaction lastSqlTxn = LastSqlTransaction.load();

              // Get the next entry that we haven't processed.
              TransactionEntity txnEntity;
              try {
                txnEntity =
                    (TransactionEntity)
                        jpaTm()
                            .transact(
                                () ->
                                    jpaTm()
                                        .getEntityManager()
                                        .createQuery(
                                            "SELECT txn FROM TransactionEntity txn WHERE id >"
                                                + " :lastId ORDER BY id")
                                        .setParameter("lastId", lastSqlTxn.getTransactionId())
                                        .setMaxResults(1)
                                        .getSingleResult());
              } catch (NoResultException e) {
                return false;
              }

              // Write it to datastore.
              long newTransactionId;
              try {
                Transaction.deserialize(txnEntity.getContents()).writeToDatastore();
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
              newTransactionId = txnEntity.getId();

              // Write the updated last transaction id to datastore.
              ofy().save().entity(lastSqlTxn.withNewTransactionId(newTransactionId));
              return true;
            });
  }
}
