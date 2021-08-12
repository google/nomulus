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

package google.registry.model.replay;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import google.registry.model.common.CrossTldSingleton;
import javax.persistence.Column;
import javax.persistence.Entity;
import org.joda.time.DateTime;

@Entity
public class SqlReplayCheckpoint extends CrossTldSingleton implements SqlOnlyEntity {

  @Column(nullable = false)
  private DateTime lastReplayTime;

  public static DateTime get() {
    jpaTm().assertInTransaction();
    return jpaTm()
        .loadSingleton(SqlReplayCheckpoint.class)
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
