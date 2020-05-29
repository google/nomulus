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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import org.junit.jupiter.api.Test;

/** Tests for {@link HostHistory}. */
public class HostHistoryTest extends EntityTestCase {

  public HostHistoryTest() {
    super(true);
  }

  @Test
  public void testPersistence() {
    saveRegistrar("registrar1");

    HostResource host =
        new HostResource.Builder()
            .setRepoId("host1")
            .setFullyQualifiedHostName("ns1.example.com")
            .setCreationClientId("TheRegistrar")
            .setPersistedCurrentSponsorClientId("TheRegistrar")
            .setInetAddresses(ImmutableSet.of())
            .build();
    jpaTm().transact(() -> jpaTm().saveNew(host));
    VKey<HostResource> hostVKey = VKey.createSql(HostResource.class, "host1");
    HostResource hostFromDb = jpaTm().transact(() -> jpaTm().load(hostVKey));
    HostHistory hostHistory =
        new HostHistory.Builder()
            .setId(1L)
            .setType(HistoryEntry.Type.HOST_CREATE)
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(fakeClock.nowUtc())
            .setClientId("registrar1")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(true)
            .setHostBase(hostFromDb)
            .setHostResource(hostVKey)
            .build();
    jpaTm().transact(() -> jpaTm().saveNew(hostHistory));
    jpaTm()
        .transact(
            () -> {
              HostHistory fromDatabase = jpaTm().load(VKey.createSql(HostHistory.class, 1L));
              assertHostHistoriesEqual(fromDatabase, hostHistory);
            });
  }

  private void assertHostHistoriesEqual(HostHistory one, HostHistory two) {
    // enough of the fields get changed during serialization that we can't depend on .equals()
    assertThat(one.getClientId()).isEqualTo(two.getClientId());
    assertThat(one.getHostResource()).isEqualTo(two.getHostResource());
    assertThat(one.getBySuperuser()).isEqualTo(two.getBySuperuser());
    assertThat(one.getRequestedByRegistrar()).isEqualTo(two.getRequestedByRegistrar());
    assertThat(one.getReason()).isEqualTo(two.getReason());
    assertThat(one.getTrid()).isEqualTo(two.getTrid());
    assertThat(one.getType()).isEqualTo(two.getType());
    assertThat(one.getHostBase().getFullyQualifiedHostName())
        .isEqualTo(two.getHostBase().getFullyQualifiedHostName());
  }
}
