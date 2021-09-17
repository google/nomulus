// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.javascrap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDomainAsDeleted;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.model.tld.Registry;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link CreateSyntheticHistoryEntriesAction}. */
public class CreateSyntheticHistoryEntriesActionTest
    extends MapreduceTestCase<CreateSyntheticHistoryEntriesAction> {

  private DomainBase domain;
  private ContactResource contact;

  @BeforeEach
  void beforeEach() {
    action =
        new CreateSyntheticHistoryEntriesAction(
            makeDefaultRunner(), new FakeResponse(), "adminRegistrarId");

    createTld("tld");
    domain = persistActiveDomain("example.tld");
    contact = loadByKey(domain.getAdminContact());
  }

  @Test
  void testCreation_forAllTypes() throws Exception {
    DomainBase domain2 = persistActiveDomain("exampletwo.tld");
    ContactResource contact2 = loadByKey(domain2.getAdminContact());
    HostResource host = persistActiveHost("ns1.foobar.tld");
    HostResource host2 = persistActiveHost("ns1.baz.tld");

    assertThat(HistoryEntryDao.loadAllHistoryObjects(START_OF_TIME, END_OF_TIME)).isEmpty();
    runMapreduce();

    for (EppResource resource : ImmutableList.of(contact, contact2, domain, domain2, host, host2)) {
      HistoryEntry historyEntry =
          Iterables.getOnlyElement(
              HistoryEntryDao.loadHistoryObjectsForResource(resource.createVKey()));
      assertThat(historyEntry.getParent()).isEqualTo(Key.create(resource));
      assertThat(historyEntry.getType()).isEqualTo(HistoryEntry.Type.SYNTHETIC);
    }
    assertThat(HistoryEntryDao.loadAllHistoryObjects(START_OF_TIME, END_OF_TIME)).hasSize(6);
  }

  @Test
  void testCreation_withPreviousHistoryEntry() throws Exception {
    DateTime now = DateTime.parse("1999-04-03T22:00:00.0Z");
    DomainBase withHistoryEntry =
        persistDomainWithDependentResources("foobar", "tld", contact, now, now, now.plusYears(1));
    assertThat(
            Iterables.getOnlyElement(
                    HistoryEntryDao.loadHistoryObjectsForResource(withHistoryEntry.createVKey()))
                .getType())
        .isEqualTo(HistoryEntry.Type.DOMAIN_CREATE);

    runMapreduce();

    Iterable<? extends HistoryEntry> historyEntries =
        HistoryEntryDao.loadHistoryObjectsForResource(withHistoryEntry.createVKey());
    assertThat(historyEntries).hasSize(2);
    assertThat(Iterables.getLast(historyEntries).getType()).isEqualTo(HistoryEntry.Type.SYNTHETIC);
  }

  @Test
  void testDoesntSave_ifAlreadyReplayed() throws Exception {
    DateTime now = DateTime.parse("1999-04-03T22:00:00.0Z");
    DomainHistory domainHistoryWithoutDomain =
        persistResource(
            new DomainHistory.Builder()
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setModificationTime(now)
                .setDomain(domain)
                .setRegistrarId(domain.getCreationRegistrarId())
                .build());
    DomainHistory domainHistoryWithDomain =
        domainHistoryWithoutDomain.asBuilder().setDomain(domain).build();
    // Simulate having replayed the domain and history to SQL
    jpaTm()
        .transact(
            () ->
                jpaTm()
                    .putAll(
                        Registry.get("tld"),
                        loadRegistrar("TheRegistrar"),
                        contact,
                        domain,
                        domainHistoryWithDomain));
    runMapreduce();

    // Since we already had a DomainHistory with the domain in SQL, we shouldn't create a synthetic
    // history entry
    assertThat(HistoryEntryDao.loadHistoryObjectsForResource(domain.createVKey()))
        .containsExactly(domainHistoryWithoutDomain);
  }

  @Test
  void testCreation_forDeletedResource() throws Exception {
    persistDomainAsDeleted(domain, domain.getCreationTime().plusMonths(6));
    runMapreduce();

    assertThat(
            Iterables.getOnlyElement(
                    HistoryEntryDao.loadHistoryObjectsForResource(domain.createVKey()))
                .getType())
        .isEqualTo(HistoryEntry.Type.SYNTHETIC);
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }
}
