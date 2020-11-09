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

package google.registry.model.ofy;

import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import google.registry.model.common.CrossTldSingleton;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import org.joda.time.DateTime;

@Entity
public class SqlReplayCheckpoint extends CrossTldSingleton implements DatastoreEntity {

  DateTime lastReplayTime;

  @Override
  public ImmutableList<SqlEntity> toSqlEntities() {
    return ImmutableList.of(); // not necessary to persist in SQL
  }

  public static DateTime get() {
    VKey<SqlReplayCheckpoint> key =
        VKey.create(
            SqlReplayCheckpoint.class,
            SINGLETON_ID,
            Key.create(getCrossTldKey(), SqlReplayCheckpoint.class, SINGLETON_ID));
    return ofyTm()
        .transact(
            () ->
                ofyTm()
                    .maybeLoad(key)
                    .map(checkpoint -> checkpoint.lastReplayTime)
                    .orElse(START_OF_TIME));
  }

  public static void set(DateTime lastReplayTime) {
    ofyTm()
        .transact(
            () -> {
              SqlReplayCheckpoint checkpoint = new SqlReplayCheckpoint();
              checkpoint.lastReplayTime = lastReplayTime;
              ofyTm().put(checkpoint);
            });
  }
}
