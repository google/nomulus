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

package google.registry.model.replay;

import static google.registry.model.annotations.NotBackedUp.Reason.TRANSIENT;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.annotations.NotBackedUp;
import org.joda.time.DateTime;

/**
 * Tracks gaps in transaction ids when replicating from SQL to datastore.
 *
 * <p>SQL -&gt; DS replication uses a Transaction table indexed by a SEQUENCE column, which normally
 * increments monotonically for each committed transaction. Gaps in this sequence can occur when a
 * transaction is rolled back or when a transaction has been initiated but not committed to the
 * table at the time of a query. To protect us from the latter scenario, we need to keep track of
 * these gaps and replay any of them that have been filled in since we processed their batch.
 */
@DeleteAfterMigration
@NotBackedUp(reason = TRANSIENT)
@Entity
public class ReplayGap extends ImmutableObject {
  @Id long transactionId;

  // We can't use a CreateAutoTimestamp here because this ends up getting persisted in an ofy
  // transaction that happens in JPA mode, so we don't end up getting an active transaction manager
  // when the timestamp needs to be set.
  DateTime timestamp;

  ReplayGap() {}

  ReplayGap(DateTime timestamp, long transactionId) {
    this.timestamp = timestamp;
    this.transactionId = transactionId;
  }

  long getTransactionId() {
    return transactionId;
  }

  DateTime getTimestamp() {
    return timestamp;
  }
}
