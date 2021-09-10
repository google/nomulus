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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import google.registry.model.EntityTestCase;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Tests for {@link SqlReplayCheckpoint}. */
public class SqlReplayCheckpointTest extends EntityTestCase {

  SqlReplayCheckpointTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testEmpty_startOfTime() {
    assertThat(jpaTm().transact(SqlReplayCheckpoint::get)).isEqualTo(START_OF_TIME);
  }

  @Test
  void testSuccess_writes() {
    DateTime dateTime = DateTime.parse("2012-02-29T00:00:00Z");
    jpaTm().transact(() -> SqlReplayCheckpoint.set(dateTime));
    assertThat(jpaTm().transact(SqlReplayCheckpoint::get)).isEqualTo(dateTime);
  }

  @Test
  void testSuccess_multipleWrites() {
    DateTime firstTime = DateTime.parse("2012-02-29T00:00:00Z");
    jpaTm().transact(() -> SqlReplayCheckpoint.set(firstTime));
    DateTime secondTime = DateTime.parse("2013-02-28T00:00:00Z");
    jpaTm().transact(() -> SqlReplayCheckpoint.set(secondTime));
    assertThat(jpaTm().transact(SqlReplayCheckpoint::get)).isEqualTo(secondTime);
    jpaTm()
        .transact(
            () ->
                assertThat(
                        jpaTm()
                            .query("SELECT COUNT(*) FROM SqlReplayCheckpoint", Long.class)
                            .getSingleResult())
                    .isEqualTo(1L));
  }
}
