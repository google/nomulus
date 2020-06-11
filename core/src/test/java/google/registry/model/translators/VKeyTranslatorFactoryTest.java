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
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveContact;

import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class VKeyTranslatorFactoryTest {

  @RegisterExtension public final AppEngineRule appEngine =
      AppEngineRule.builder()
          .withDatastore()
          .build();

  public VKeyTranslatorFactoryTest() {}

  @Test
  void testEntityWithVKeyCreate() {
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
  void testEntityWithoutVKeyCreate() {
    CommitLogBucket bucket = new CommitLogBucket.Builder().build();
    Key<CommitLogBucket> key = Key.create(bucket);
    VKey<CommitLogBucket> vkey = VKeyTranslatorFactory.createVKey(key);
    assertThat(vkey.getKind()).isEqualTo(CommitLogBucket.class);
    assertThat(vkey.getOfyKey()).isEqualTo(key);
    assertThat(vkey.maybeGetSqlKey().isPresent()).isFalse();
  }

  @Test
  void testUrlSafeKey() {
    // Creating an objectify key instead of a datastore key as this should get a correctly formatted
    // key path.
    DomainBase domain = newDomainBase("example.com", "ROID-1", persistActiveContact("contact-1"));
    Key<DomainBase> key = Key.create(domain);
    VKey<DomainBase> vkey = (VKey<DomainBase>) VKeyTranslatorFactory.createVKey(key.getString());
    assertThat(vkey.getKind()).isEqualTo(DomainBase.class);
    assertThat(vkey.getOfyKey()).isEqualTo(key);
    assertThat(vkey.getSqlKey()).isEqualTo("ROID-1");
  }
}
