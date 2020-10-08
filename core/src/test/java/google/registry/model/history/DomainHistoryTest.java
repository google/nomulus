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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.newContactResourceWithRoid;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newHostResourceWithRoid;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainContent;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link DomainHistory}. */
public class DomainHistoryTest extends EntityTestCase {

  DomainHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void beforeEach() {
    saveRegistrar("TheRegistrar");
  }

  @Test
  void testPersistence() {
    DomainBase domain = createDomainWithContactsAndHosts();
    DomainHistory domainHistory = createDomainHistory(domain);
    jpaTm().transact(() -> jpaTm().insert(domainHistory));

    jpaTm()
        .transact(
            () -> {
              DomainHistory fromDatabase = jpaTm().load(domainHistory.createVKey());
              assertDomainHistoriesEqual(fromDatabase, domainHistory);
              assertThat(fromDatabase.getParentVKey().getSqlKey())
                  .isEqualTo(domainHistory.getParentVKey().getSqlKey());
            });
  }

  @Test
  void testLegacyPersistence_nullResource() {
    DomainBase domain = createDomainWithContactsAndHosts();
    DomainHistory domainHistory =
        createDomainHistory(domain).asBuilder().setDomainContent(null).build();
    jpaTm().transact(() -> jpaTm().insert(domainHistory));

    jpaTm()
        .transact(
            () -> {
              DomainHistory fromDatabase = jpaTm().load(domainHistory.createVKey());
              assertDomainHistoriesEqual(fromDatabase, domainHistory);
              assertThat(fromDatabase.getParentVKey().getSqlKey())
                  .isEqualTo(domainHistory.getParentVKey().getSqlKey());
              assertThat(fromDatabase.getNsHosts())
                  .containsExactlyElementsIn(
                      domainHistory.getNsHosts().stream()
                          .map(key -> VKey.createSql(HostResource.class, key.getSqlKey()))
                          .collect(toImmutableSet()));
            });
  }

  @Test
  void testOfyPersistence() {
    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    ContactResource contact = newContactResourceWithRoid("contactId", "contact1");

    tm().transact(
            () -> {
              tm().insert(host);
              tm().insert(contact);
            });
    fakeClock.advanceOneMilli();

    DomainBase domain =
        newDomainBase("example.tld", "domainRepoId", contact)
            .asBuilder()
            .setNameservers(host.createVKey())
            .build();
    tm().transact(() -> tm().insert(domain));

    fakeClock.advanceOneMilli();
    DomainHistory domainHistory = createDomainHistory(domain);
    tm().transact(() -> tm().insert(domainHistory));

    // retrieving a HistoryEntry or a DomainHistory with the same key should return the same object
    // note: due to the @EntitySubclass annotation. all Keys for DomainHistory objects will have
    // type HistoryEntry
    VKey<DomainHistory> domainHistoryVKey = domainHistory.createVKey();
    VKey<HistoryEntry> historyEntryVKey =
        VKey.createOfy(HistoryEntry.class, Key.create(domainHistory.asHistoryEntry()));
    DomainHistory domainHistoryFromDb = tm().transact(() -> tm().load(domainHistoryVKey));
    HistoryEntry historyEntryFromDb = tm().transact(() -> tm().load(historyEntryVKey));

    assertThat(domainHistoryFromDb).isEqualTo(historyEntryFromDb);
  }

  static DomainBase createDomainWithContactsAndHosts() {
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
            .build();
    jpaTm().transact(() -> jpaTm().insert(domain));
    return domain;
  }

  static void assertDomainHistoriesEqual(DomainHistory one, DomainHistory two) {
    assertAboutImmutableObjects()
        .that(one)
        .isEqualExceptFields(
            two, "domainContent", "domainRepoId", "nsHosts", "domainTransactionRecords");
    assertThat(one.getDomainContent().map(DomainContent::getDomainName))
        .isEqualTo(two.getDomainContent().map(DomainContent::getDomainName));
    // NB: the record's ID gets reset by Hibernate, causing the hash code to differ so we have to
    // compare it separately
    assertThat(one.getDomainTransactionRecords())
        .comparingElementsUsing(immutableObjectCorrespondence())
        .containsExactlyElementsIn(two.getDomainTransactionRecords());
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
        .setClientId("TheRegistrar")
        .setTrid(Trid.create("ABC-123", "server-trid"))
        .setBySuperuser(false)
        .setReason("reason")
        .setRequestedByRegistrar(true)
        .setDomainContent(domain)
        .setDomainRepoId(domain.getRepoId())
        .setDomainTransactionRecords(ImmutableSet.of(transactionRecord))
        .setOtherClientId("otherClient")
        .setPeriod(Period.create(1, Period.Unit.YEARS))
        .build();
  }
}
