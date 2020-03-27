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
import google.registry.testing.AppEngineRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import google.registry.testing.TestObject;

@RunWith(JUnit4.class)
public class VKeyTest {

  @Rule
  public final AppEngineRule appEngineRule =
      AppEngineRule.builder().withDatastoreAndCloudSql().build();

  public VKeyTest() {}

  @Test
  public void testOptionalAccessors() {
    VKey<TestObject> key = VKey.create(TestObject.class, null, null);
    assertThat(key.maybeGetSqlKey().isPresent()).isFalse();
    assertThat(key.maybeGetOfyKey().isPresent()).isFalse();

    Key<TestObject> ofyKey = Key.create(TestObject.create("foo"));
    assertThat(VKey.createOfy(TestObject.class, ofyKey).maybeGetOfyKey().get()).isEqualTo(ofyKey);
    assertThat(VKey.createSql(TestObject.class, "foo").maybeGetSqlKey().get()).isEqualTo("foo");
  }
}
