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
    jpaTm().transact(() -> jpaTm().insert(host));
    VKey<HostResource> hostVKey =
        VKey.create(HostResource.class, "host1", Key.create(HostResource.class, "host1"));
    HostResource hostFromDb = jpaTm().transact(() -> jpaTm().load(hostVKey));
    HostHistory hostHistory = createHostHistory(hostFromDb, host.getRepoId());
    hostHistory.id = null;
    jpaTm().transact(() -> jpaTm().insert(hostHistory));
    jpaTm()
        .transact(
            () -> {
              HostHistory fromDatabase = jpaTm().load(hostHistory.createVKey());
              assertHostHistoriesEqual(fromDatabase, hostHistory);
              assertThat(fromDatabase.getParentVKey().getSqlKey())
                  .isEqualTo(hostHistory.getParentVKey().getSqlKey());
            });
  }

  @Test
  void testLegacyPersistence_nullHostBase() {
    saveRegistrar("TheRegistrar");
    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    jpaTm().transact(() -> jpaTm().insert(host));

    HostResource hostFromDb = jpaTm().transact(() -> jpaTm().load(host.createVKey()));
    HostHistory hostHistory =
        createHostHistory(hostFromDb, host.getRepoId()).asBuilder().setHostBase(null).build();
    hostHistory.id = null;
    jpaTm().transact(() -> jpaTm().insert(hostHistory));

    jpaTm()
        .transact(
            () -> {
              HostHistory fromDatabase = jpaTm().load(hostHistory.createVKey());
              assertHostHistoriesEqual(fromDatabase, hostHistory);
              assertThat(fromDatabase.getParentVKey().getSqlKey())
                  .isEqualTo(hostHistory.getParentVKey().getSqlKey());
            });
  }

  @Test
  void testOfySave() {
    saveRegistrar("registrar1");

    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    tm().transact(() -> tm().insert(host));
    VKey<HostResource> hostVKey =
        VKey.create(HostResource.class, "host1", Key.create(HostResource.class, "host1"));
    HostResource hostFromDb = tm().transact(() -> tm().load(hostVKey));
    HostHistory hostHistory = createHostHistory(hostFromDb, host.getRepoId());
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().insert(hostHistory));

    // retrieving a HistoryEntry or a HostHistory with the same key should return the same object
    // note: due to the @EntitySubclass annotation. all Keys for HostHistory objects will have
    // type HistoryEntry
    VKey<HostHistory> hostHistoryVKey = hostHistory.createVKey();
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

  private HostHistory createHostHistory(HostBase hostBase, String hostRepoId) {
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
        .setHostRepoId(hostRepoId)
        .build();
  }
}
