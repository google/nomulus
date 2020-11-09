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
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newContactResourceWithRoid;
import static google.registry.testing.DatastoreHelper.newHostResourceWithRoid;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestSqlOnly;

/** Tests to check {@link HistoryEntry} + its subclasses' transitions to/from Datastore/SQL. */
@DualDatabaseTest
public class LegacyHistoryObjectTest extends EntityTestCase {

  public LegacyHistoryObjectTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @TestSqlOnly
  void testFullConversion_contact() {
    // Create+save an old contact HistoryEntry, reload it, and verify it's a proper ContactHistory
    ContactResource contact = newContactResourceWithRoid("contactId", "contact1");
    HistoryEntry legacyHistoryEntry = historyEntryBuilderFor(contact).build();
    ofyTm().transact(() -> ofyTm().insert(legacyHistoryEntry));

    // In Datastore, we will save it as HistoryEntry but retrieve it as ContactHistory
    long historyEntryId = legacyHistoryEntry.getId();
    HistoryEntry fromObjectify =
        ofyTm()
            .transact(
                () ->
                    ofyTm()
                        .load(
                            VKey.create(
                                HistoryEntry.class,
                                historyEntryId,
                                Key.create(legacyHistoryEntry))));
    // The objects will be mostly the same, but the ContactHistory object has a couple extra fields
    assertAboutImmutableObjects()
        .that(legacyHistoryEntry)
        .isEqualExceptFields(fromObjectify, "contactBase", "contactRepoId");
    assertThat(fromObjectify instanceof ContactHistory).isTrue();
    ContactHistory legacyContactHistory = (ContactHistory) fromObjectify;

    // Next, save that from-Datastore object in SQL and verify we can load it back in
    jpaTm()
        .transact(
            () -> {
              jpaTm().insert(contact);
              jpaTm().insert(legacyContactHistory);
            });
    ContactHistory legacyHistoryFromSql =
        jpaTm().transact(() -> jpaTm().load(legacyContactHistory.createVKey()));
    assertAboutImmutableObjects()
        .that(legacyContactHistory)
        .isEqualExceptFields(legacyHistoryFromSql);
    // can't compare contactRepoId directly since it doesn't save the ofy key
    assertThat(legacyContactHistory.getParentVKey().getSqlKey())
        .isEqualTo(legacyHistoryFromSql.getParentVKey().getSqlKey());
  }

  @TestSqlOnly
  void testFullConversion_domain() {
    createTld("foobar");
    // Create+save an old domain HistoryEntry, reload it, and verify it's a proper DomainHistory
    DomainBase domain = DomainHistoryTest.createDomainWithContactsAndHosts();
    HistoryEntry legacyHistoryEntry = historyEntryForDomain(domain);
    ofyTm().transact(() -> ofyTm().insert(legacyHistoryEntry));

    // In Datastore, we will save it as HistoryEntry but retrieve it as DomainHistory
    long historyEntryId = legacyHistoryEntry.getId();
    HistoryEntry fromObjectify =
        ofyTm()
            .transact(
                () ->
                    ofyTm()
                        .load(
                            VKey.create(
                                HistoryEntry.class,
                                historyEntryId,
                                Key.create(legacyHistoryEntry))));
    // The objects will be mostly the same, but the DomainHistory object has a couple extra fields
    assertAboutImmutableObjects()
        .that(legacyHistoryEntry)
        .isEqualExceptFields(
            fromObjectify, "domainContent", "domainRepoId", "nsHosts", "dsDataHistories");
    assertThat(fromObjectify instanceof DomainHistory).isTrue();
    DomainHistory legacyDomainHistory = (DomainHistory) fromObjectify;

    // Next, save that from-Datastore object in SQL and verify we can load it back in
    jpaTm().transact(() -> jpaTm().insert(legacyDomainHistory));
    DomainHistory legacyHistoryFromSql =
        jpaTm().transact(() -> jpaTm().load(legacyDomainHistory.createVKey()));
    // Don't compare nsHosts directly because one is null and the other is empty
    assertAboutImmutableObjects()
        .that(legacyDomainHistory)
        .isEqualExceptFields(
            // NB: period, transaction records, and other client ID are added in #794
            legacyHistoryFromSql,
            "period",
            "domainTransactionRecords",
            "otherClientId",
            "nsHosts",
            "dsDataHistories");
    assertThat(nullToEmpty(legacyDomainHistory.getNsHosts()))
        .isEqualTo(nullToEmpty(legacyHistoryFromSql.getNsHosts()));
  }

  @TestSqlOnly
  void testFullConversion_host() {
    // Create+save an old host HistoryEntry, reload it, and verify it's a proper HostHistory
    HostResource host = newHostResourceWithRoid("hs1.example.com", "host1");
    HistoryEntry legacyHistoryEntry = historyEntryBuilderFor(host).build();
    ofyTm().transact(() -> ofyTm().insert(legacyHistoryEntry));

    // In Datastore, we will save it as HistoryEntry but retrieve it as HostHistory
    long historyEntryId = legacyHistoryEntry.getId();
    HistoryEntry fromObjectify =
        ofyTm()
            .transact(
                () ->
                    ofyTm()
                        .load(
                            VKey.create(
                                HistoryEntry.class,
                                historyEntryId,
                                Key.create(legacyHistoryEntry))));
    // The objects will be mostly the same, but the HostHistory object has a couple extra fields
    assertAboutImmutableObjects()
        .that(legacyHistoryEntry)
        .isEqualExceptFields(fromObjectify, "hostBase", "hostRepoId");
    assertThat(fromObjectify instanceof HostHistory).isTrue();
    HostHistory legacyHostHistory = (HostHistory) fromObjectify;

    // Next, save that from-Datastore object in SQL and verify we can load it back in
    jpaTm()
        .transact(
            () -> {
              jpaTm().insert(host);
              jpaTm().insert(legacyHostHistory);
            });
    HostHistory legacyHistoryFromSql =
        jpaTm().transact(() -> jpaTm().load(legacyHostHistory.createVKey()));
    assertAboutImmutableObjects().that(legacyHostHistory).isEqualExceptFields(legacyHistoryFromSql);
    // can't compare hostRepoId directly since it doesn't save the ofy key in SQL
    assertThat(legacyHostHistory.getParentVKey().getSqlKey())
        .isEqualTo(legacyHistoryFromSql.getParentVKey().getSqlKey());
  }

  private HistoryEntry historyEntryForDomain(DomainBase domain) {
    DomainTransactionRecord transactionRecord =
        new DomainTransactionRecord.Builder()
            .setTld("foobar")
            .setReportingTime(fakeClock.nowUtc())
            .setReportField(TransactionReportField.NET_ADDS_1_YR)
            .setReportAmount(1)
            .build();
    return historyEntryBuilderFor(domain)
        .setPeriod(Period.create(1, Period.Unit.YEARS))
        .setDomainTransactionRecords(ImmutableSet.of(transactionRecord))
        .setOtherClientId("TheRegistrar")
        .build();
  }

  private HistoryEntry.Builder historyEntryBuilderFor(EppResource parent) {
    return new HistoryEntry.Builder()
        .setParent(parent)
        .setType(HistoryEntry.Type.DOMAIN_CREATE)
        .setXmlBytes("<xml></xml>".getBytes(UTF_8))
        .setModificationTime(fakeClock.nowUtc())
        .setClientId("TheRegistrar")
        .setTrid(Trid.create("ABC-123", "server-trid"))
        .setBySuperuser(false)
        .setReason("reason")
        .setRequestedByRegistrar(false);
  }
}
