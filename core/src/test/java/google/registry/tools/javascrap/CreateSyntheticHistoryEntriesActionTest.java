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

package google.registry.tools.javascrap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link CreateSyntheticHistoryEntriesAction}. */
public class CreateSyntheticHistoryEntriesActionTest
    extends MapreduceTestCase<CreateSyntheticHistoryEntriesAction> {

  private ContactResource contact1;
  private ContactResource contact2;
  private DomainBase domain1;
  private DomainBase domain2;
  private HostResource host1;
  private HostResource host2;

  @BeforeEach
  void beforeEach() {
    action = new CreateSyntheticHistoryEntriesAction();
    action.mrRunner = makeDefaultRunner();
    action.response = new FakeResponse();

    createTld("tld");
    domain1 = persistActiveDomain("example.tld");
    contact1 = loadByKey(domain1.getAdminContact());
    domain2 = persistActiveDomain("exampletwo.tld");
    contact2 = loadByKey(domain2.getAdminContact());
    host1 = persistActiveHost("ns1.foobar.tld");
    host2 = persistActiveHost("ns1.baz.tld");
  }

  @Test
  void testSyntheticEntryCreation() throws Exception {
    assertThat(HistoryEntryDao.loadAllHistoryObjects(START_OF_TIME, END_OF_TIME)).isEmpty();
    action.run();
    executeTasksUntilEmpty("mapreduce");

    for (EppResource resource :
        ImmutableList.of(contact1, contact2, domain1, domain2, host1, host2, domain1)) {
      HistoryEntry historyEntry =
          Iterables.getOnlyElement(
              HistoryEntryDao.loadHistoryObjectsForResource(resource.createVKey()));
      assertThat(historyEntry.getParent()).isEqualTo(Key.create(resource));
      assertThat(historyEntry.getType()).isEqualTo(HistoryEntry.Type.SYNTHETIC);
    }
    assertThat(HistoryEntryDao.loadAllHistoryObjects(START_OF_TIME, END_OF_TIME)).hasSize(6);
  }
}
