// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;

import google.registry.model.OteStats.StatType;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

@DualDatabaseTest
public final class OteStatsTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @TestOfyAndSql
  void testSuccess_allPass() throws Exception {
    OteStatsTestHelper.setupCompleteOte("blobio");
    OteStats stats = OteStats.getFromRegistrar("blobio");
    assertThat(stats.getFailures()).isEmpty();
    assertThat(stats.getSize()).isEqualTo(30);
  }

  @TestOfyAndSql
  void testSuccess_incomplete() throws Exception {
    OteStatsTestHelper.setupIncompleteOte("blobio");
    OteStats stats = OteStats.getFromRegistrar("blobio");
    assertThat(stats.getFailures())
        .containsExactly(
            StatType.DOMAIN_CREATES_IDN, StatType.DOMAIN_RESTORES, StatType.HOST_DELETES)
        .inOrder();
    assertThat(stats.getSize()).isEqualTo(34);
  }

  @TestOfyAndSql
  void testSuccess_toString() throws Exception {
    OteStatsTestHelper.setupCompleteOte("blobio");
    OteStats stats = OteStats.getFromRegistrar("blobio");
    String expected =
        "contact creates: 0\n"
            + "contact deletes: 0\n"
            + "contact transfer approves: 0\n"
            + "contact transfer cancels: 0\n"
            + "contact transfer rejects: 0\n"
            + "contact transfer requests: 0\n"
            + "contact updates: 0\n"
            + "domain autorenews: 0\n"
            + "domain creates: 5\n"
            + "domain creates ascii: 4\n"
            + "domain creates idn: 1\n"
            + "domain creates start date sunrise: 1\n"
            + "domain creates with claims notice: 1\n"
            + "domain creates with fee: 1\n"
            + "domain creates with sec dns: 1\n"
            + "domain creates without sec dns: 4\n"
            + "domain deletes: 1\n"
            + "domain renews: 0\n"
            + "domain restores: 1\n"
            + "domain transfer approves: 1\n"
            + "domain transfer cancels: 1\n"
            + "domain transfer rejects: 1\n"
            + "domain transfer requests: 1\n"
            + "domain updates: 1\n"
            + "domain updates with sec dns: 1\n"
            + "domain updates without sec dns: 0\n"
            + "host creates: 1\n"
            + "host creates external: 0\n"
            + "host creates subordinate: 1\n"
            + "host deletes: 1\n"
            + "host updates: 1\n"
            + "unclassified flows: 0\n"
            + "TOTAL: 30";
    assertThat(stats.toString()).isEqualTo(expected);
  }

  @TestOfyAndSql
  void testIncomplete_toString() throws Exception {
    OteStatsTestHelper.setupIncompleteOte("blobio");
    OteStats stats = OteStats.getFromRegistrar("blobio");
    String expected =
        "contact creates: 0\n"
            + "contact deletes: 0\n"
            + "contact transfer approves: 0\n"
            + "contact transfer cancels: 0\n"
            + "contact transfer rejects: 0\n"
            + "contact transfer requests: 0\n"
            + "contact updates: 0\n"
            + "domain autorenews: 0\n"
            + "domain creates: 4\n"
            + "domain creates ascii: 4\n"
            + "domain creates idn: 0\n"
            + "domain creates start date sunrise: 1\n"
            + "domain creates with claims notice: 1\n"
            + "domain creates with fee: 1\n"
            + "domain creates with sec dns: 1\n"
            + "domain creates without sec dns: 3\n"
            + "domain deletes: 1\n"
            + "domain renews: 0\n"
            + "domain restores: 0\n"
            + "domain transfer approves: 1\n"
            + "domain transfer cancels: 1\n"
            + "domain transfer rejects: 1\n"
            + "domain transfer requests: 1\n"
            + "domain updates: 1\n"
            + "domain updates with sec dns: 1\n"
            + "domain updates without sec dns: 0\n"
            + "host creates: 1\n"
            + "host creates external: 0\n"
            + "host creates subordinate: 1\n"
            + "host deletes: 0\n"
            + "host updates: 10\n"
            + "unclassified flows: 0\n"
            + "TOTAL: 34";
    assertThat(stats.toString()).isEqualTo(expected);
  }
}
