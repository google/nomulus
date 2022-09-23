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
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistActiveContact;

import com.googlecode.objectify.Key;
import google.registry.model.common.ClassPathManager;
import google.registry.model.domain.Domain;
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
    Domain domain = newDomain("example.com", "ROID-1", persistActiveContact("contact-1"));
    Key<Domain> key = Key.create(domain);
    VKey<Domain> vkey = VKeyTranslatorFactory.createVKey(key);
    assertThat(vkey.getKind()).isEqualTo(Domain.class);
    assertThat(vkey.getOfyKey()).isEqualTo(key);
    assertThat(vkey.getSqlKey()).isEqualTo("ROID-1");
  }

  @Test
  void testExtraEntityClass() {
    TestObject testObject = TestObject.create("id", "field");
    Key<TestObject> key = Key.create(testObject);
    assertThat(VKeyTranslatorFactory.createVKey(key).getSqlKey()).isEqualTo("id");
  }
}
