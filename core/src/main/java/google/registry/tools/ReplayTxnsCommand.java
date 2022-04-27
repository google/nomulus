// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static google.registry.persistence.transaction.TransactionManagerFactory.replicaJpaTm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.persistence.transaction.Transaction;
import google.registry.persistence.transaction.TransactionEntity;
import java.util.List;

@Parameters(separators = " =", commandDescription = "Replay a range of transactions.")
public class ReplayTxnsCommand implements CommandWithRemoteApi {

  private static final int BATCH_SIZE = 200;

  @Parameter(
      names = {"-s", "--start-txn-id"},
      description = "Transaction id to start replaying at.",
      required = true)
  long startTxnId;

  @Override
  public void run() throws Exception {
    List<TransactionEntity> txns;
    do {
      txns =
          replicaJpaTm()
              .transact(
                  () ->
                      replicaJpaTm()
                          .query(
                              "SELECT txn FROM TransactionEntity txn where id >= :startTxn ORDER"
                                  + " BY id",
                              TransactionEntity.class)
                          .setParameter("startTxn", startTxnId)
                          .setMaxResults(BATCH_SIZE)
                          .getResultList());
      for (TransactionEntity txn : txns) {
        System.out.println("Replaying transaction " + txn.getId());
        Transaction.deserialize(txn.getContents());
        startTxnId = txn.getId() + 1;
      }
    } while (txns.size() > 0);
  }
}
