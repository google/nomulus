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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Splitter;
import com.google.common.io.BaseEncoding;
import google.registry.model.common.Cursor;
import google.registry.persistence.transaction.Transaction;
import google.registry.persistence.transaction.TransactionEntity;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Lists {@link Cursor} timestamps used by locking rolling cursor tasks, like in RDE. */
@Parameters(separators = " =", commandDescription = "Lists the contents of the Transaction table.")
final class ListTxnsCommand implements CommandWithRemoteApi {

  @Parameter(names = "--infile", description = "Parse an input file instead of reading from db.")
  private String infile;

  @Parameter(
      names = "--full_dump",
      description =
          "Do a full dump of the contents of the transaction.  Without this, "
              + "just write transactions as CSV lines suitable for ingestion via --infile.")
  private boolean fullDump = false;

  @Override
  public void run() {
    if (infile == null) {
      fetchFromDb();
    } else {
      parseCsvFile();
    }
  }

  private void parseCsvFile() {
    try {
      BufferedReader src = Files.newBufferedReader(Paths.get(infile), UTF_8);
      String line;
      while ((line = src.readLine()) != null) {
        List<String> cols = Splitter.on(",").splitToList(line);
        writeRecord(Integer.parseInt(cols.get(0)), BaseEncoding.base64().decode(cols.get(1)));
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void fetchFromDb() {
    long lastTransactionId = 0;
    List<TransactionEntity> results;

    do {
      final long txnId = lastTransactionId; // For use in the lambda.
      results =
          replicaJpaTm()
              .transact(
                  () ->
                      replicaJpaTm()
                          .query(
                              "select t from TransactionEntity t where id > :lastTransactionId",
                              TransactionEntity.class)
                          .setParameter("lastTransactionId", txnId)
                          .setMaxResults(1000)
                          .getResultList());

      for (TransactionEntity txn : results) {
        writeRecord(txn.getId(), txn.getContents());
        lastTransactionId = txn.getId();
      }
    } while (results.size() > 0);
  }

  private void writeRecord(long id, byte[] contents) {
    if (fullDump) {
      Transaction txn;
      try {
        txn = Transaction.deserialize(contents);
      } catch (IOException ex) {
        System.err.printf("Error deserializing transaction %s\n", id);
        return;
      }
      System.out.printf("transaction %s <<<\n", id);
      for (Transaction.Mutation mut : txn.getMutations()) {
        if (mut instanceof Transaction.Update) {
          System.out.println("updating: " + ((Transaction.Update) mut).getEntity());
        } else {
          System.out.println("deleting: " + ((Transaction.Delete) mut).getKey());
        }
      }
      System.out.println(">>>");
    } else {
      System.out.printf("%s,%s\n", id, BaseEncoding.base64().encode(contents));
    }
  }
}
