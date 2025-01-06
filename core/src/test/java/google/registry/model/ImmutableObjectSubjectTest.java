// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model;

import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

public class ImmutableObjectSubjectTest {

  @Test
  void testHasCorrectHashValue() {
    TestImmutableObject object = makeTestObj();
    assertAboutImmutableObjects().that(object).hasCorrectHashValue();

    object.stringField = "changed value!";
    assertThrows(
        AssertionError.class,
        () -> assertAboutImmutableObjects().that(object).hasCorrectHashValue());
  }

  /** Make a test object with all fields set up. */
  TestImmutableObject makeTestObj() {
    return TestImmutableObject.create(
        "foo",
        makeTestAtom("bar"),
        ImmutableList.of(makeTestAtom("baz")),
        ImmutableSet.of(makeTestAtom("bob")),
        ImmutableMap.of(makeTestAtom("key"), makeTestAtom("val")));
  }

  /** Make a test object without the collection fields. */
  TestImmutableObject makeTestAtom(String stringField) {
    return TestImmutableObject.create(stringField, null, null, null, null);
  }

  static class TestImmutableObject extends ImmutableObject {
    String stringField;
    TestImmutableObject nested;
    ImmutableList<TestImmutableObject> list;
    ImmutableSet<TestImmutableObject> set;
    ImmutableMap<TestImmutableObject, TestImmutableObject> map;

    static TestImmutableObject create(
        String stringField,
        TestImmutableObject nested,
        ImmutableList<TestImmutableObject> list,
        ImmutableSet<TestImmutableObject> set,
        ImmutableMap<TestImmutableObject, TestImmutableObject> map) {
      TestImmutableObject instance = new TestImmutableObject();
      instance.stringField = stringField;
      instance.nested = nested;
      instance.list = list;
      instance.set = set;
      instance.map = map;
      return instance;
    }

    TestImmutableObject withStringField(@Nullable String stringField) {
      TestImmutableObject result = ImmutableObject.clone(this);
      result.stringField = stringField;
      return result;
    }

    TestImmutableObject withNested(@Nullable TestImmutableObject nested) {
      TestImmutableObject result = ImmutableObject.clone(this);
      result.nested = nested;
      return result;
    }

    TestImmutableObject withList(@Nullable ImmutableList<TestImmutableObject> list) {
      TestImmutableObject result = ImmutableObject.clone(this);
      result.list = list;
      return result;
    }

    TestImmutableObject withSet(@Nullable ImmutableSet<TestImmutableObject> set) {
      TestImmutableObject result = ImmutableObject.clone(this);
      result.set = set;
      return result;
    }

    TestImmutableObject withMap(
        @Nullable ImmutableMap<TestImmutableObject, TestImmutableObject> map) {
      TestImmutableObject result = ImmutableObject.clone(this);
      result.map = map;
      return result;
    }
  }

  static class DerivedImmutableObject extends TestImmutableObject {
    String extraField;

    static DerivedImmutableObject create() {
      return new DerivedImmutableObject();
    }
  }
}
