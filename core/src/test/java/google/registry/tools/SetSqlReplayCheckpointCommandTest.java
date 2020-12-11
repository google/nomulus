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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.Assert.assertThrows;

import google.registry.schema.replay.SqlReplayCheckpoint;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

class SetSqlReplayCheckpointCommandTest extends CommandTestCase<SetSqlReplayCheckpointCommand> {

  @Test
  void testSuccess() throws Exception {
    assertThat(jpaTm().transact(SqlReplayCheckpoint::get)).isEqualTo(START_OF_TIME);
    DateTime timeToSet = DateTime.parse("2000-06-06T22:00:00.0Z");
    runCommandForced(timeToSet.toString());
    assertThat(jpaTm().transact(SqlReplayCheckpoint::get)).isEqualTo(timeToSet);
  }

  @Test
  void testFailure_multipleParams() throws Exception {
    DateTime one = DateTime.parse("2000-06-06T22:00:00.0Z");
    DateTime two = DateTime.parse("2001-06-06T22:00:00.0Z");
    assertThrows(IllegalArgumentException.class, () -> runCommand(one.toString(), two.toString()));
  }
}
