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

package google.registry.model.history;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.newContactResourceWithRoid;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.newHostResourceWithRoid;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainContent;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.util.SerializeUtils;
import java.util.Optional;
import org.joda.time.DateTime;
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
    DomainBase domain = addGracePeriodForSql(createDomainWithContactsAndHosts());
    DomainHistory domainHistory = createDomainHistory(domain);
    insertInDb(domainHistory);

    jpaTm()
        .transact(
            () -> {
              DomainHistory fromDatabase = jpaTm().loadByKey(domainHistory.createVKey());
              assertDomainHistoriesEqual(fromDatabase, domainHistory);
              assertThat(fromDatabase.getParentVKey()).isEqualTo(domainHistory.getParentVKey());
            });
  }

  @Test
  void testSerializable() {
    DomainBase domain = addGracePeriodForSql(createDomainWithContactsAndHosts());
    DomainHistory domainHistory = createDomainHistory(domain);
    insertInDb(domainHistory);
    DomainHistory fromDatabase =
        jpaTm().transact(() -> jpaTm().loadByKey(domainHistory.createVKey()));
    assertThat(SerializeUtils.serializeDeserialize(fromDatabase)).isEqualTo(fromDatabase);
  }

  @Test
  void testLegacyPersistence_nullResource() {
    DomainBase domain = addGracePeriodForSql(createDomainWithContactsAndHosts());
    DomainHistory domainHistory = createDomainHistory(domain).asBuilder().setDomain(null).build();
    insertInDb(domainHistory);

    jpaTm()
        .transact(
            () -> {
              DomainHistory fromDatabase = jpaTm().loadByKey(domainHistory.createVKey());
              assertDomainHistoriesEqual(fromDatabase, domainHistory);
              assertThat(fromDatabase.getParentVKey()).isEqualTo(domainHistory.getParentVKey());
              assertThat(fromDatabase.getNsHosts())
                  .containsExactlyElementsIn(domainHistory.getNsHosts());
            });
  }

  static DomainBase createDomainWithContactsAndHosts() {
    createTld("tld");
    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    ContactResource contact = newContactResourceWithRoid("contactId", "contact1");

    jpaTm()
        .transact(
            () -> {
              jpaTm().insert(host);
              jpaTm().insert(contact);
            });

    DomainBase domain =
        newDomainBase("example.tld", "domainRepoId", contact)
            .asBuilder()
            .setNameservers(host.createVKey())
            .setDsData(ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
            .setDnsRefreshRequestTime(Optional.of(DateTime.parse("2020-03-09T16:40:00Z")))
            .build();
    insertInDb(domain);
    return domain;
  }

  private static DomainBase addGracePeriodForSql(DomainBase domainBase) {
    return domainBase
        .asBuilder()
        .setGracePeriods(
            ImmutableSet.of(
                GracePeriod.create(
                    GracePeriodStatus.ADD, "domainRepoId", END_OF_TIME, "clientId", null)))
        .build();
  }

  static void assertDomainHistoriesEqual(DomainHistory one, DomainHistory two) {
    assertAboutImmutableObjects().that(one).isEqualExceptFields(two, "domainContent");
    assertAboutImmutableObjects()
        .that(one.getDomainContent().get())
        .isEqualExceptFields(two.getDomainContent().get(), "updateTimestamp");
  }

  private DomainHistory createDomainHistory(DomainContent domain) {
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
        .setDomainRepoId(domain.getRepoId())
        .setDomainTransactionRecords(ImmutableSet.of(transactionRecord))
        .setOtherRegistrarId("otherClient")
        .setPeriod(Period.create(1, Period.Unit.YEARS))
        .build();
  }
}
