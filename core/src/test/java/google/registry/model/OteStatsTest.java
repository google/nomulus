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
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.money.CurrencyUnit.USD;

import google.registry.model.OteStats.StatType;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public final class OteStatsTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void testSuccess_allPass() throws Exception {
    OteStatsTestHelper.setupCompleteOte("blobio");
    OteStats stats = OteStats.getFromRegistrar("blobio");
    assertThat(stats.getFailures()).isEmpty();
    assertThat(stats.getSize()).isEqualTo(30);
  }

  @Test
  void testSuccess_incomplete() throws Exception {
    OteStatsTestHelper.setupIncompleteOte("blobio");
    OteStats stats = OteStats.getFromRegistrar("blobio");
    assertThat(stats.getFailures())
        .containsExactly(
            StatType.DOMAIN_CREATES_IDN, StatType.DOMAIN_RESTORES, StatType.HOST_DELETES)
        .inOrder();
    assertThat(stats.getSize()).isEqualTo(34);
  }

  @Test
  void testSuccess_toString() throws Exception {
    OteStatsTestHelper.setupCompleteOte("blobio");
    OteStats stats = OteStats.getFromRegistrar("blobio");
    String expected =
        """
        contact creates: 0
        contact deletes: 0
        contact transfer approves: 0
        contact transfer cancels: 0
        contact transfer rejects: 0
        contact transfer requests: 0
        contact updates: 0
        domain autorenews: 0
        domain creates: 5
        domain creates ascii: 4
        domain creates idn: 1
        domain creates start date sunrise: 1
        domain creates with claims notice: 1
        domain creates with fee: 1
        domain creates with sec dns: 1
        domain creates without sec dns: 4
        domain deletes: 1
        domain renews: 0
        domain restores: 1
        domain transfer approves: 1
        domain transfer cancels: 1
        domain transfer rejects: 1
        domain transfer requests: 1
        domain updates: 1
        domain updates with sec dns: 1
        domain updates without sec dns: 0
        host creates: 1
        host creates external: 0
        host creates subordinate: 1
        host deletes: 1
        host updates: 1
        unclassified flows: 0
        TOTAL: 30\
        """;
    assertThat(stats.toString()).isEqualTo(expected);
  }

  @Test
  void testIncomplete_toString() throws Exception {
    OteStatsTestHelper.setupIncompleteOte("blobio");
    OteStats stats = OteStats.getFromRegistrar("blobio");
    String expected =
        """
        contact creates: 0
        contact deletes: 0
        contact transfer approves: 0
        contact transfer cancels: 0
        contact transfer rejects: 0
        contact transfer requests: 0
        contact updates: 0
        domain autorenews: 0
        domain creates: 4
        domain creates ascii: 4
        domain creates idn: 0
        domain creates start date sunrise: 1
        domain creates with claims notice: 1
        domain creates with fee: 1
        domain creates with sec dns: 1
        domain creates without sec dns: 3
        domain deletes: 1
        domain renews: 0
        domain restores: 0
        domain transfer approves: 1
        domain transfer cancels: 1
        domain transfer rejects: 1
        domain transfer requests: 1
        domain updates: 1
        domain updates with sec dns: 1
        domain updates without sec dns: 0
        host creates: 1
        host creates external: 0
        host creates subordinate: 1
        host deletes: 0
        host updates: 10
        unclassified flows: 0
        TOTAL: 34\
        """;
    assertThat(stats.toString()).isEqualTo(expected);
  }

  @Test
  void testDomainCreateWithoutXmlBytes_doesNotSatisfyComplexRequirements() throws Exception {
    persistPremiumList("default_sandbox_list", USD, "sandbox,USD 1000");
    OteAccountBuilder.forRegistrarId("blobio").buildAndPersist();
    String oteAccount1 = "blobio-1";
    Instant now = Instant.parse("2026-06-25T10:00:00Z");

    // Persist a DOMAIN_CREATE history entry without XML bytes
    persistResource(
        new DomainHistory.Builder()
            .setDomain(persistActiveDomain("example.tld"))
            .setRegistrarId(oteAccount1)
            .setType(Type.DOMAIN_CREATE)
            .setXmlBytes(null) // explicitly null
            .setModificationTime(now)
            .build());

    OteStats stats = OteStats.getFromRegistrar("blobio");

    // It should count towards the basic DOMAIN_CREATES
    assertThat(stats.getCount(StatType.DOMAIN_CREATES)).isEqualTo(1);

    // It should NOT count towards any stat type that requires EPP input filtering
    assertThat(stats.getCount(StatType.DOMAIN_CREATES_ASCII)).isEqualTo(0);
    assertThat(stats.getCount(StatType.DOMAIN_CREATES_IDN)).isEqualTo(0);
    assertThat(stats.getCount(StatType.DOMAIN_CREATES_START_DATE_SUNRISE)).isEqualTo(0);
    assertThat(stats.getCount(StatType.DOMAIN_CREATES_WITH_CLAIMS_NOTICE)).isEqualTo(0);
    assertThat(stats.getCount(StatType.DOMAIN_CREATES_WITH_FEE)).isEqualTo(0);
    assertThat(stats.getCount(StatType.DOMAIN_CREATES_WITH_SEC_DNS)).isEqualTo(0);
    assertThat(stats.getCount(StatType.DOMAIN_CREATES_WITHOUT_SEC_DNS)).isEqualTo(0);
  }
}
