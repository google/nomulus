// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.reporting;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.model.EntityTestCase;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link HistoryEntry}. */
public class HistoryEntryTest extends EntityTestCase {

  HistoryEntry historyEntry;

  @Before
  public void setUp() throws Exception {
    createTld("foobar");
    // Set up a new persisted HistoryEntry entity.
    historyEntry = new HistoryEntry.Builder()
      .setParent(newDomainResource("foo.foobar"))
      .setType(HistoryEntry.Type.DOMAIN_CREATE)
      .setPeriod(Period.create(1, Period.Unit.YEARS))
      .setXmlBytes("<xml></xml>".getBytes(UTF_8))
      .setModificationTime(clock.nowUtc())
      .setClientId("foo")
      .setOtherClientId("otherClient")
      .setTrid(Trid.create("ABC-123"))
      .setBySuperuser(false)
      .setReason("reason")
      .setRequestedByRegistrar(false)
      .build();
    persistResource(historyEntry);

  }

  @Test
  public void testPersistence() throws Exception {
    assertThat(ofy().load().entity(historyEntry).now()).isEqualTo(historyEntry);
  }

  @Test
  public void testIndexing() throws Exception {
    verifyIndexing(historyEntry, "modificationTime", "clientId");
  }
}
