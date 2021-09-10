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

package google.registry.model.reporting;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;

@DualDatabaseTest
class HistoryEntryDaoTest extends EntityTestCase {

  private DomainBase domain;
  private HistoryEntry domainHistory;

  @BeforeEach
  void beforeEach() {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    createTld("foobar");
    domain = persistActiveDomain("foo.foobar");
    DomainTransactionRecord transactionRecord =
        new DomainTransactionRecord.Builder()
            .setTld("foobar")
            .setReportingTime(fakeClock.nowUtc())
            .setReportField(TransactionReportField.NET_ADDS_1_YR)
            .setReportAmount(1)
            .build();
    // Set up a new persisted DomainHistory entity.
    domainHistory =
        new DomainHistory.Builder()
            .setDomain(domain)
            .setType(HistoryEntry.Type.DOMAIN_CREATE)
            .setPeriod(Period.create(1, Period.Unit.YEARS))
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(fakeClock.nowUtc())
            .setRegistrarId("TheRegistrar")
            .setOtherRegistrarId("otherClient")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(false)
            .setDomainTransactionRecords(ImmutableSet.of(transactionRecord))
            .build();
    persistResource(domainHistory);
  }

  @TestOfyAndSql
  void testSimpleLoadAll() {
    assertThat(HistoryEntryDao.loadAllHistoryObjects(START_OF_TIME, END_OF_TIME))
        .comparingElementsUsing(immutableObjectCorrespondence("nsHosts", "domainContent"))
        .containsExactly(domainHistory);
  }

  @TestOfyAndSql
  void testSkips_tooEarly() {
    assertThat(HistoryEntryDao.loadAllHistoryObjects(fakeClock.nowUtc().plusMillis(1), END_OF_TIME))
        .isEmpty();
  }

  @TestOfyAndSql
  void testSkips_tooLate() {
    assertThat(
            HistoryEntryDao.loadAllHistoryObjects(START_OF_TIME, fakeClock.nowUtc().minusMillis(1)))
        .isEmpty();
  }

  @TestOfyAndSql
  void testLoadByResource() {
    transactIfJpaTm(
        () ->
            assertThat(HistoryEntryDao.loadHistoryObjectsForResource(domain.createVKey()))
                .comparingElementsUsing(immutableObjectCorrespondence("nsHosts", "domainContent"))
                .containsExactly(domainHistory));
  }

  @TestOfyAndSql
  void testLoadByResource_skips_tooEarly() {
    assertThat(
            HistoryEntryDao.loadHistoryObjectsForResource(
                domain.createVKey(), fakeClock.nowUtc().plusMillis(1), END_OF_TIME))
        .isEmpty();
  }

  @TestOfyAndSql
  void testLoadByResource_skips_tooLate() {
    assertThat(
            HistoryEntryDao.loadHistoryObjectsForResource(
                domain.createVKey(), START_OF_TIME, fakeClock.nowUtc().minusMillis(1)))
        .isEmpty();
  }

  @TestOfyAndSql
  void testLoadByResource_noEntriesForResource() {
    DomainBase newDomain = persistResource(newDomainBase("new.foobar"));
    assertThat(HistoryEntryDao.loadHistoryObjectsForResource(newDomain.createVKey())).isEmpty();
  }
}
