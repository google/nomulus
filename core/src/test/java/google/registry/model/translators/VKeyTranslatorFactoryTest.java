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

package google.registry.model.translators;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveContact;

import com.googlecode.objectify.Key;
import google.registry.model.common.ClassPathManager;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.TestObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link VKeyTranslatorFactory}. */
public class VKeyTranslatorFactoryTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withCloudSql().withOfyTestEntities(TestObject.class).build();

  VKeyTranslatorFactoryTest() {}

  @BeforeAll
  static void beforeAll() {
    ClassPathManager.addTestEntityClass(TestObject.class);
  }

  @Test
  void testEntityWithFlatKey() {
    // Creating an objectify key instead of a datastore key as this should get a correctly formatted
    // key path.
    DomainBase domain = newDomainBase("example.com", "ROID-1", persistActiveContact("contact-1"));
    Key<DomainBase> key = Key.create(domain);
    VKey<DomainBase> vkey = VKeyTranslatorFactory.createVKey(key);
    assertThat(vkey.getKind()).isEqualTo(DomainBase.class);
    assertThat(vkey.getOfyKey()).isEqualTo(key);
    assertThat(vkey.getSqlKey()).isEqualTo("ROID-1");
  }

  @Test
  void testEntityWithAncestor() {
    Key<DomainBase> domainKey = Key.create(DomainBase.class, "ROID-1");
    Key<HistoryEntry> historyEntryKey = Key.create(domainKey, HistoryEntry.class, 10L);

    VKey<HistoryEntry> vkey = VKeyTranslatorFactory.createVKey(historyEntryKey);

    assertThat(vkey.getKind()).isEqualTo(DomainHistory.class);
    assertThat(vkey.getOfyKey()).isEqualTo(historyEntryKey);
    assertThat(vkey.getSqlKey()).isEqualTo(new DomainHistoryId("ROID-1", 10L));
  }

  @Test
  void testExtraEntityClass() {
    TestObject testObject = TestObject.create("id", "field");
    Key<TestObject> key = Key.create(testObject);
    assertThat(VKeyTranslatorFactory.createVKey(key).getSqlKey()).isEqualTo("id");
  }
}
