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

package google.registry.schema.history;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.SqlHelper.newHostResource;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HostHistory}. */
public class HostHistoryTest extends EntityTestCase {

  private HostResource host;
  private HostHistory hostHistory;

  public HostHistoryTest() {
    super(true);
  }

  @BeforeEach
  public void setup() {
    host = newHostResource("host1");
    hostHistory =
        new HostHistory.Builder()
            .setHostResource(host)
            .setBySuperuser(false)
            .setRegistrarId("TheRegistrar")
            .setReason("reason")
            .setRequestedByRegistrar(true)
            .setType(HistoryEntry.Type.HOST_UPDATE)
            .setXmlBytes(new byte[] {1, 2, 3})
            .setTrid(Trid.create("clientId", "serverId"))
            .setDomainTransactionRecords(ImmutableSet.of())
            .build();
    jpaTm().transact(() -> jpaTm().saveNew(host));
  }

  @Test
  public void testPersistence() {
    long revisionId =
        jpaTm().transact(() -> jpaTm().getEntityManager().merge(hostHistory)).revisionId;
    jpaTm()
        .transact(
            () -> {
              HostHistory fromDatabase =
                  jpaTm().getEntityManager().find(HostHistory.class, revisionId);
              hostHistory.creationTime = fromDatabase.creationTime;
              hostHistory.revisionId = revisionId;
              assertThat(fromDatabase).isEqualTo(hostHistory);
            });
  }
}
