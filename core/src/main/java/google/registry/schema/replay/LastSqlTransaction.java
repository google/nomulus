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

package google.registry.schema.replay;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.annotations.VisibleForTesting;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.ImmutableObject;

/** Datastore entity to keep track of the last SQL transaction imported into the datastore. */
@Entity
public class LastSqlTransaction extends ImmutableObject implements DatastoreOnlyEntity {

  /** The key for this singleton. */
  public static final Key<LastSqlTransaction> KEY = Key.create(LastSqlTransaction.class, 1);

  @SuppressWarnings("unused")
  @Id
  private long id = 1;

  private long transactionId;

  LastSqlTransaction() {}

  @VisibleForTesting
  LastSqlTransaction(long newTransactionId) {
    transactionId = newTransactionId;
  }

  LastSqlTransaction cloneWithNewTransactionId(long transactionId) {
    checkArgument(
        transactionId > this.transactionId,
        "New transaction id (%s) must be greater than the current transaction id (%s)",
        transactionId,
        this.transactionId);
    return new LastSqlTransaction(transactionId);
  }

  long getTransactionId() {
    return transactionId;
  }

  /**
   * Loads the instance.
   *
   * <p>Must be called within an Ofy transaction.
   *
   * <p>Creates a new instance of the singleton if it is not already present in Cloud Datastore,
   */
  static LastSqlTransaction load() {
    ofy().assertInTransaction();
    LastSqlTransaction result = ofy().load().key(KEY).now();
    return result == null ? new LastSqlTransaction() : result;
  }
}
