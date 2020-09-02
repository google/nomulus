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

package google.registry.model.history;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.newHostResourceWithRoid;
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
import org.junit.jupiter.api.Test;

/** Tests for {@link HostHistory}. */
public class HostHistoryTest extends EntityTestCase {

  HostHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testPersistence() {
    saveRegistrar("TheRegistrar");

    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    jpaTm().transact(() -> jpaTm().saveNew(host));
    VKey<HostResource> hostVKey = VKey.createSql(HostResource.class, "host1");
    HostResource hostFromDb = jpaTm().transact(() -> jpaTm().load(hostVKey));
    HostHistory hostHistory = createHostHistory(hostFromDb, hostVKey);
    hostHistory.id = null;
    jpaTm().transact(() -> jpaTm().saveNew(hostHistory));
    jpaTm()
        .transact(
            () -> {
              HostHistory fromDatabase =
                  jpaTm().load(VKey.createSql(HostHistory.class, hostHistory.getId()));
              assertHostHistoriesEqual(fromDatabase, hostHistory);
              assertThat(fromDatabase.getHostRepoId().getSqlKey())
                  .isEqualTo(hostHistory.getHostRepoId().getSqlKey());
            });
  }

  @Test
  void testLegacyPersistence_nullHostBase() {
    saveRegistrar("TheRegistrar");
    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    jpaTm().transact(() -> jpaTm().saveNew(host));
    VKey<HostResource> hostVKey = VKey.createSql(HostResource.class, "host1");
    HostResource hostFromDb = jpaTm().transact(() -> jpaTm().load(hostVKey));

    HostHistory hostHistory =
        createHostHistory(hostFromDb, hostVKey).asBuilder().setHostBase(null).build();
    hostHistory.id = null;
    jpaTm().transact(() -> jpaTm().saveNew(hostHistory));

    jpaTm()
        .transact(
            () -> {
              HostHistory fromDatabase =
                  jpaTm().load(VKey.createSql(HostHistory.class, hostHistory.getId()));
              assertHostHistoriesEqual(fromDatabase, hostHistory);
              assertThat(fromDatabase.getHostRepoId().getSqlKey())
                  .isEqualTo(hostHistory.getHostRepoId().getSqlKey());
            });
  }

  @Test
  void testOfySave() {
    saveRegistrar("registrar1");

    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    tm().transact(() -> tm().saveNew(host));
    VKey<HostResource> hostVKey = VKey.create(HostResource.class, "host1", Key.create(host));
    HostResource hostFromDb = tm().transact(() -> tm().load(hostVKey));
    HostHistory hostHistory = createHostHistory(hostFromDb, hostVKey);
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveNew(hostHistory));

    // retrieving a HistoryEntry or a HostHistory with the same key should return the same object
    // note: due to the @EntitySubclass annotation. all Keys for HostHistory objects will have
    // type HistoryEntry
    VKey<HostHistory> hostHistoryVKey = VKey.createOfy(HostHistory.class, Key.create(hostHistory));
    VKey<HistoryEntry> historyEntryVKey =
        VKey.createOfy(HistoryEntry.class, Key.create(hostHistory.asHistoryEntry()));
    HostHistory hostHistoryFromDb = tm().transact(() -> tm().load(hostHistoryVKey));
    HistoryEntry historyEntryFromDb = tm().transact(() -> tm().load(historyEntryVKey));

    assertThat(hostHistoryFromDb).isEqualTo(historyEntryFromDb);
  }

  private void assertHostHistoriesEqual(HostHistory one, HostHistory two) {
    assertAboutImmutableObjects().that(one).isEqualExceptFields(two, "hostBase");
    assertAboutImmutableObjects()
        .that(one.getHostBase().orElse(null))
        .isEqualExceptFields(two.getHostBase().orElse(null), "repoId");
  }

  private HostHistory createHostHistory(HostBase hostBase, VKey<HostResource> hostVKey) {
    return new HostHistory.Builder()
        .setType(HistoryEntry.Type.HOST_CREATE)
        .setXmlBytes("<xml></xml>".getBytes(UTF_8))
        .setModificationTime(fakeClock.nowUtc())
        .setClientId("TheRegistrar")
        .setTrid(Trid.create("ABC-123", "server-trid"))
        .setBySuperuser(false)
        .setReason("reason")
        .setRequestedByRegistrar(true)
        .setHostBase(hostBase)
        .setHostRepoId(hostVKey)
        .build();
  }
}
