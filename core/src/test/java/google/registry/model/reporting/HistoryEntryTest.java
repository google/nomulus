// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.reporting;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import google.registry.model.EntityTestCase;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;

/** Unit tests for {@link HistoryEntry}. */
@DualDatabaseTest
class HistoryEntryTest extends EntityTestCase {

  private DomainHistory domainHistory;

  @BeforeEach
  void setUp() {
    createTld("foobar");
    DomainBase domain = persistActiveDomain("foo.foobar");
    DomainTransactionRecord transactionRecord =
        new DomainTransactionRecord.Builder()
            .setTld("foobar")
            .setReportingTime(fakeClock.nowUtc())
            .setReportField(TransactionReportField.NET_ADDS_1_YR)
            .setReportAmount(1)
            .build();
    // Set up a new persisted HistoryEntry entity.
    domainHistory =
        new DomainHistory.Builder()
            .setDomainContent(domain)
            .setType(HistoryEntry.Type.DOMAIN_CREATE)
            .setPeriod(Period.create(1, Period.Unit.YEARS))
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(fakeClock.nowUtc())
            .setClientId("TheRegistrar")
            .setOtherClientId("otherClient")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(false)
            .setDomainTransactionRecords(ImmutableSet.of(transactionRecord))
            .build();
    persistResource(domainHistory);
  }

  @TestOfyAndSql
  void testPersistence() {
    transactIfJpaTm(
        () -> {
          HistoryEntry fromDatabase = tm().loadByEntity(domainHistory);
          assertAboutImmutableObjects()
              .that(fromDatabase)
              .isEqualExceptFields(
                  domainHistory, "nsHosts", "domainTransactionRecords", "domainContent");
          assertAboutImmutableObjects()
              .that(Iterables.getOnlyElement(fromDatabase.getDomainTransactionRecords()))
              .isEqualExceptFields(
                  Iterables.getOnlyElement(domainHistory.getDomainTransactionRecords()), "id");
        });
  }

  @TestOfyAndSql
  void testBuilder_typeMustBeSpecified() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new HistoryEntry.Builder()
                    .setId(5L)
                    .setModificationTime(DateTime.parse("1985-07-12T22:30:00Z"))
                    .setClientId("TheRegistrar")
                    .setReason("Reason")
                    .build());
    assertThat(thrown).hasMessageThat().isEqualTo("History entry type must be specified");
  }

  @TestOfyAndSql
  void testBuilder_modificationTimeMustBeSpecified() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new HistoryEntry.Builder()
                    .setId(5L)
                    .setType(HistoryEntry.Type.CONTACT_CREATE)
                    .setClientId("TheRegistrar")
                    .setReason("Reason")
                    .build());
    assertThat(thrown).hasMessageThat().isEqualTo("Modification time must be specified");
  }

  @TestOfyAndSql
  void testBuilder_registrarIdMustBeSpecified() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new HistoryEntry.Builder()
                    .setId(5L)
                    .setType(HistoryEntry.Type.CONTACT_CREATE)
                    .setModificationTime(DateTime.parse("1985-07-12T22:30:00Z"))
                    .setReason("Reason")
                    .build());
    assertThat(thrown).hasMessageThat().isEqualTo("Registrar ID must be specified");
  }

  @TestOfyAndSql
  void testBuilder_syntheticHistoryEntries_mustNotBeRequestedByRegistrar() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new HistoryEntry.Builder()
                    .setId(5L)
                    .setType(HistoryEntry.Type.SYNTHETIC)
                    .setModificationTime(DateTime.parse("1985-07-12T22:30:00Z"))
                    .setClientId("TheRegistrar")
                    .setReason("Reason")
                    .setRequestedByRegistrar(true)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Synthetic history entries cannot be requested by a registrar");
  }

  @TestOfyOnly
  void testIndexing() throws Exception {
    verifyIndexing(domainHistory.asHistoryEntry(), "modificationTime", "clientId");
  }
}
