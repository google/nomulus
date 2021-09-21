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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.newHostResource;
import static google.registry.testing.DatabaseHelper.newHostResourceWithRoid;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostBase;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyOnly;
import google.registry.testing.TestSqlOnly;

/** Tests for {@link HostHistory}. */
@DualDatabaseTest
public class HostHistoryTest extends EntityTestCase {

  HostHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @TestSqlOnly
  void testPersistence() {
    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    insertInDb(host);
    HostResource hostFromDb = loadByEntity(host);
    HostHistory hostHistory = createHostHistory(hostFromDb);
    insertInDb(hostHistory);
    jpaTm()
        .transact(
            () -> {
              HostHistory fromDatabase = jpaTm().loadByKey(hostHistory.createVKey());
              assertHostHistoriesEqual(fromDatabase, hostHistory);
              assertThat(fromDatabase.getParentVKey()).isEqualTo(hostHistory.getParentVKey());
            });
  }

  @TestSqlOnly
  void testLegacyPersistence_nullHostBase() {
    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    insertInDb(host);

    HostResource hostFromDb = loadByEntity(host);
    HostHistory hostHistory = createHostHistory(hostFromDb).asBuilder().setHost(null).build();
    insertInDb(hostHistory);

    jpaTm()
        .transact(
            () -> {
              HostHistory fromDatabase = jpaTm().loadByKey(hostHistory.createVKey());
              assertHostHistoriesEqual(fromDatabase, hostHistory);
              assertThat(fromDatabase.getParentVKey()).isEqualTo(hostHistory.getParentVKey());
            });
  }

  @TestOfyOnly
  void testOfySave() {
    saveRegistrar("registrar1");

    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    tm().transact(() -> tm().insert(host));
    VKey<HostResource> hostVKey =
        VKey.create(HostResource.class, "host1", Key.create(HostResource.class, "host1"));
    HostResource hostFromDb = tm().transact(() -> tm().loadByKey(hostVKey));
    HostHistory hostHistory = createHostHistory(hostFromDb);
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().insert(hostHistory));

    // retrieving a HistoryEntry or a HostHistory with the same key should return the same object
    // note: due to the @EntitySubclass annotation. all Keys for HostHistory objects will have
    // type HistoryEntry
    VKey<HostHistory> hostHistoryVKey = hostHistory.createVKey();
    VKey<HistoryEntry> historyEntryVKey =
        VKey.createOfy(HistoryEntry.class, Key.create(hostHistory.asHistoryEntry()));
    HostHistory hostHistoryFromDb = tm().transact(() -> tm().loadByKey(hostHistoryVKey));
    HistoryEntry historyEntryFromDb = tm().transact(() -> tm().loadByKey(historyEntryVKey));

    assertThat(hostHistoryFromDb).isEqualTo(historyEntryFromDb);
  }

  @TestSqlOnly
  void testBeforeSqlSave_afterHostPersisted() {
    HostResource hostResource = newHostResource("ns1.example.tld");
    HostHistory hostHistory =
        new HostHistory.Builder()
            .setType(HistoryEntry.Type.HOST_CREATE)
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(fakeClock.nowUtc())
            .setRegistrarId("TheRegistrar")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(true)
            .setHostRepoId(hostResource.getRepoId())
            .build();
    jpaTm()
        .transact(
            () -> {
              jpaTm().put(hostResource);
              hostHistory.beforeSqlSaveOnReplay();
              jpaTm().put(hostHistory);
            });
    jpaTm()
        .transact(
            () ->
                assertAboutImmutableObjects()
                    .that(jpaTm().loadByEntity(hostResource))
                    .hasFieldsEqualTo(jpaTm().loadByEntity(hostHistory).getHostBase().get()));
  }

  private void assertHostHistoriesEqual(HostHistory one, HostHistory two) {
    assertAboutImmutableObjects().that(one).isEqualExceptFields(two, "hostBase");
    assertAboutImmutableObjects()
        .that(one.getHostBase().orElse(null))
        .isEqualExceptFields(two.getHostBase().orElse(null), "repoId");
  }

  private HostHistory createHostHistory(HostBase hostBase) {
    return new HostHistory.Builder()
        .setType(HistoryEntry.Type.HOST_CREATE)
        .setXmlBytes("<xml></xml>".getBytes(UTF_8))
        .setModificationTime(fakeClock.nowUtc())
        .setRegistrarId("TheRegistrar")
        .setTrid(Trid.create("ABC-123", "server-trid"))
        .setBySuperuser(false)
        .setReason("reason")
        .setRequestedByRegistrar(true)
        .setHost(hostBase)
        .setHostRepoId(hostBase.getRepoId())
        .build();
  }
}
