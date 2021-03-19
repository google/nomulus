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

import static google.registry.model.common.CrossTldSingleton.SINGLETON_ID;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import java.util.Optional;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.time.DateTime;

@Entity
public class SqlReplayCheckpoint implements SqlEntity {

  // Hibernate doesn't allow us to have a converted DateTime as our primary key so we need this
  @Id private long revisionId = SINGLETON_ID;

  private DateTime lastReplayTime;

  @Override
  public Optional<DatastoreEntity> toDatastoreEntity() {
    return Optional.empty(); // Not necessary to persist in Datastore
  }

  public static DateTime get() {
    jpaTm().assertInTransaction();
    return jpaTm()
        .query("FROM SqlReplayCheckpoint", SqlReplayCheckpoint.class)
        .setMaxResults(1)
        .getResultStream()
        .findFirst()
        .map(checkpoint -> checkpoint.lastReplayTime)
        .orElse(START_OF_TIME);
  }

  public static void set(DateTime lastReplayTime) {
    jpaTm().assertInTransaction();
    SqlReplayCheckpoint checkpoint = new SqlReplayCheckpoint();
    checkpoint.lastReplayTime = lastReplayTime;
    // this will overwrite the existing object due to the constant revisionId
    jpaTm().put(checkpoint);
  }
}
