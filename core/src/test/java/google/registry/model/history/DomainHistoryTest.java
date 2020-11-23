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
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newContactResourceWithRoid;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.newHostResourceWithRoid;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
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
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registries;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyOnly;
import google.registry.testing.TestSqlOnly;

/** Tests for {@link DomainHistory}. */
@DualDatabaseTest
public class DomainHistoryTest extends EntityTestCase {

  DomainHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @TestSqlOnly
  void testPersistence() {
    DomainBase domain = addGracePeriodForSql(createDomainWithContactsAndHosts());
    DomainHistory domainHistory = createDomainHistory(domain);
    jpaTm().transact(() -> jpaTm().insert(domainHistory));

    jpaTm()
        .transact(
            () -> {
              DomainHistory fromDatabase = jpaTm().load(domainHistory.createVKey());
              assertDomainHistoriesEqual(fromDatabase, domainHistory);
              assertThat(fromDatabase.getParentVKey()).isEqualTo(domainHistory.getParentVKey());
            });
  }

  @TestSqlOnly
  void testLegacyPersistence_nullResource() {
    DomainBase domain = addGracePeriodForSql(createDomainWithContactsAndHosts());
    DomainHistory domainHistory =
        createDomainHistory(domain).asBuilder().setDomainContent(null).build();
    jpaTm().transact(() -> jpaTm().insert(domainHistory));

    jpaTm()
        .transact(
            () -> {
              DomainHistory fromDatabase = jpaTm().load(domainHistory.createVKey());
              assertDomainHistoriesEqual(fromDatabase, domainHistory);
              assertThat(fromDatabase.getParentVKey()).isEqualTo(domainHistory.getParentVKey());
              assertThat(fromDatabase.getNsHosts())
                  .containsExactlyElementsIn(
                      domainHistory.getNsHosts().stream()
                          .map(key -> VKey.createSql(HostResource.class, key.getSqlKey()))
                          .collect(toImmutableSet()));
            });
  }

  @TestOfyOnly
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

    // retrieving a HistoryEntry or a DomainHistory with the same key should return the same
    // object
    // note: due to the @EntitySubclass annotation. all Keys for DomainHistory objects will have
    // type HistoryEntry
    VKey<DomainHistory> domainHistoryVKey = domainHistory.createVKey();
    VKey<HistoryEntry> historyEntryVKey =
        VKey.createOfy(HistoryEntry.class, Key.create(domainHistory.asHistoryEntry()));
    DomainHistory domainHistoryFromDb = tm().transact(() -> tm().load(domainHistoryVKey));
    HistoryEntry historyEntryFromDb = tm().transact(() -> tm().load(historyEntryVKey));

    assertThat(domainHistoryFromDb).isEqualTo(historyEntryFromDb);
  }

  @TestOfyOnly
  void testDoubleWriteOfOfyResource() {
    // We have to add the registry to ofy, since we're currently loading the cache from ofy.  We
    // also have to add it to SQL to satisfy the foreign key constraints of the registrar.
    Registry registry =
        DatabaseHelper.newRegistry(
            "tld", "TLD", ImmutableSortedMap.of(START_OF_TIME, GENERAL_AVAILABILITY));
    tm().transact(() -> tm().insert(registry));
    Registries.resetCache();
    jpaTm()
        .transact(
            () -> {
              jpaTm().insert(registry);
              Registrar registrar =
                  appEngine
                      .makeRegistrar2()
                      .asBuilder()
                      .setAllowedTlds(ImmutableSet.of("tld"))
                      .build();
              jpaTm().insert(registrar);
            });

    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    ContactResource contact = newContactResourceWithRoid("contactId", "contact1");

    // Set up the host and domain objects in both databases.
    tm().transact(
            () -> {
              tm().insert(host);
              tm().insert(contact);
            });
    jpaTm()
        .transact(
            () -> {
              jpaTm().insert(host);
              jpaTm().insert(contact);
            });
    fakeClock.advanceOneMilli();
    DomainBase domain =
        newDomainBase("example.tld", "domainRepoId", contact)
            .asBuilder()
            .setNameservers(host.createVKey())
            .build();
    tm().transact(() -> tm().insert(domain));
    jpaTm().transact(() -> jpaTm().insert(domain));
    fakeClock.advanceOneMilli();

    DomainHistory domainHistory = createDomainHistory(domain);
    tm().transact(() -> tm().insert(domainHistory));

    // Load the DomainHistory object from the datastore.
    VKey<DomainHistory> domainHistoryVKey = domainHistory.createVKey();
    DomainHistory domainHistoryFromDb = tm().transact(() -> tm().load(domainHistoryVKey));

    // attempt to write to SQL.
    jpaTm().transact(() -> jpaTm().insert(domainHistoryFromDb));

    // Reload and rewrite.
    DomainHistory domainHistoryFromDb2 = tm().transact(() -> tm().load(domainHistoryVKey));
    jpaTm().transact(() -> jpaTm().put(domainHistoryFromDb2));
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
            .build();
    jpaTm().transact(() -> jpaTm().insert(domain));
    return domain;
  }

  private static DomainBase addGracePeriodForSql(DomainBase domainBase) {
    return domainBase
        .asBuilder()
        .setGracePeriods(
            ImmutableSet.of(
                GracePeriod.create(
                        GracePeriodStatus.ADD, "domainRepoId", END_OF_TIME, "clientId", null)
                    .cloneWithPrepopulatedId()))
        .build();
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
