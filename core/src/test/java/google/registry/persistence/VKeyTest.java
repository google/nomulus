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
package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;

import com.googlecode.objectify.Key;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.TestObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link VKey}. */
class VKeyTest {

  @RegisterExtension
  final AppEngineExtension appEngineRule =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestObject.class)
          .build();

  @Test
  void testOptionalAccessors() {
    VKey<TestObject> key =
        VKey.create(TestObject.class, "foo", Key.create(TestObject.create("foo")));
    assertThat(key.maybeGetSqlKey().isPresent()).isTrue();
    assertThat(key.maybeGetOfyKey().isPresent()).isTrue();

    assertThat(VKey.createSql(TestObject.class, "foo").maybeGetSqlKey().get()).isEqualTo("foo");
  }
}
