// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.history;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.newContactWithRoid;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.newHostWithRoid;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.Host;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.util.SerializeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link DomainHistory}. */
public class DomainHistoryTest extends EntityTestCase {

  DomainHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void beforeEach() {
    fakeClock.setAutoIncrementByOneMilli();
  }

  @Test
  void testPersistence() {
    Domain domain = addGracePeriodForSql(createDomainWithContactsAndHosts());
    DomainHistory domainHistory = createDomainHistory(domain);
    insertInDb(domainHistory);

    tm().transact(
            () -> {
              DomainHistory fromDatabase = tm().loadByKey(domainHistory.createVKey());
              assertDomainHistoriesEqual(fromDatabase, domainHistory);
              assertThat(fromDatabase.getRepoId()).isEqualTo(domainHistory.getRepoId());
            });
  }

  @Test
  void testSerializable() {
    Domain domain = addGracePeriodForSql(createDomainWithContactsAndHosts());
    DomainHistory domainHistory = createDomainHistory(domain);
    insertInDb(domainHistory);
    DomainHistory fromDatabase = tm().transact(() -> tm().loadByKey(domainHistory.createVKey()));
    assertThat(SerializeUtils.serializeDeserialize(fromDatabase)).isEqualTo(fromDatabase);
  }

  static Domain createDomainWithContactsAndHosts() {
    createTld("tld");
    Host host = newHostWithRoid("ns1.example.com", "host1");
    Contact contact = newContactWithRoid("contactId", "contact1");

    tm().transact(
            () -> {
              tm().insert(host);
              tm().insert(contact);
            });

    Domain domain =
        newDomain("example.tld", "domainRepoId", contact)
            .asBuilder()
            .setNameservers(host.createVKey())
            .setDsData(ImmutableSet.of(DomainDsData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .build();
    insertInDb(domain);
    return domain;
  }

  private static Domain addGracePeriodForSql(Domain domain) {
    return domain
        .asBuilder()
        .setGracePeriods(
            ImmutableSet.of(
                GracePeriod.create(
                    GracePeriodStatus.ADD, "domainRepoId", END_OF_TIME, "clientId", null)))
        .build();
  }

  static void assertDomainHistoriesEqual(DomainHistory one, DomainHistory two) {
    assertAboutImmutableObjects().that(one).isEqualExceptFields(two, "resource");
    assertAboutImmutableObjects()
        .that(one.getDomainBase().get())
        .isEqualExceptFields(two.getDomainBase().get(), "updateTimestamp");
  }

  private DomainHistory createDomainHistory(DomainBase domain) {
    DomainTransactionRecord transactionRecord =
        new DomainTransactionRecord.Builder()
            .setTld("tld")
            .setReportingTime(fakeClock.nowUtc())
            .setReportField(TransactionReportField.NET_ADDS_1_YR)
            .setReportAmount(1)
            .build();

    return new DomainHistory.Builder()
        .setType(HistoryEntry.Type.DOMAIN_CREATE)
        .setXmlBytes("<xml></xml>".getBytes(UTF_8))
        .setModificationTime(fakeClock.nowUtc())
        .setRegistrarId("TheRegistrar")
        .setTrid(Trid.create("ABC-123", "server-trid"))
        .setBySuperuser(false)
        .setReason("reason")
        .setRequestedByRegistrar(true)
        .setDomain(domain)
        .setDomainTransactionRecords(ImmutableSet.of(transactionRecord))
        .setOtherRegistrarId("otherClient")
        .setPeriod(Period.create(1, Period.Unit.YEARS))
        .build();
  }
}
