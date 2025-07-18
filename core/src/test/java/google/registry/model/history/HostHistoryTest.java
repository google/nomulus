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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.newHostWithRoid;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.model.EntityTestCase;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.Host;
import google.registry.model.host.HostBase;
import google.registry.model.host.HostHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.util.SerializeUtils;
import org.junit.jupiter.api.Test;

/** Tests for {@link HostHistory}. */
public class HostHistoryTest extends EntityTestCase {

  HostHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testPersistence() {
    Host host = newHostWithRoid("ns1.example.com", "host1");
    persistResource(host);
    Host hostFromDb = loadByEntity(host);
    HostHistory hostHistory = createHostHistory(hostFromDb);
    persistResource(hostHistory);
    tm().transact(
            () -> {
              HostHistory fromDatabase = tm().loadByKey(hostHistory.createVKey());
              assertHostHistoriesEqual(fromDatabase, hostHistory);
              assertThat(fromDatabase.getRepoId()).isEqualTo(hostHistory.getRepoId());
            });
  }

  @Test
  void testSerializable() {
    Host host = newHostWithRoid("ns1.example.com", "host1");
    persistResource(host);
    Host hostFromDb = loadByEntity(host);
    HostHistory hostHistory = createHostHistory(hostFromDb);
    persistResource(hostHistory);
    HostHistory fromDatabase = tm().transact(() -> tm().loadByKey(hostHistory.createVKey()));
    assertThat(SerializeUtils.serializeDeserialize(fromDatabase)).isEqualTo(fromDatabase);
  }

  private static void assertHostHistoriesEqual(HostHistory one, HostHistory two) {
    assertAboutImmutableObjects().that(one).isEqualExceptFields(two, "resource");
    assertAboutImmutableObjects()
        .that(one.getHostBase().get())
        .isEqualExceptFields(two.getHostBase().get(), "repoId");
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
        .build();
  }
}
