// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.ComparisonResult;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ImmutableObjectSubject.checkObjectAcrossDatabases;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

public class ImmutableObjectSubjectTest {

  // Unique id to assign to the "ignored" field so that it always gets a unique value.
  private static int uniqueId = 0;

  @Test
  void testCrossDatabase_nulls() {
    assertAboutImmutableObjects().that(null).isEqualAcrossDatabases(null);
    assertAboutImmutableObjects()
        .that(makeTestAtom(null))
        .isEqualAcrossDatabases(makeTestAtom(null));

    assertThat(checkObjectAcrossDatabases(null, makeTestAtom("foo"), null).isFailure()).isTrue();
    assertThat(checkObjectAcrossDatabases(null, makeTestAtom("foo"), null).isFailure()).isTrue();
  }

  @Test
  void testCrossDatabase_equalObjects() {
    TestImmutableObject actual = makeTestObj();
    assertAboutImmutableObjects().that(actual).isEqualAcrossDatabases(actual);
    assertAboutImmutableObjects().that(actual).isEqualAcrossDatabases(makeTestObj());
    assertThat(checkObjectAcrossDatabases(makeTestObj(), makeTestObj(), null).isFailure())
        .isFalse();
  }

  @Test
  void testCrossDatabase_simpleFieldFailure() {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(makeTestObj().withStringField("bar")));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$TestImmutableObject.stringField:");
    assertThat(
            checkObjectAcrossDatabases(makeTestObj(), makeTestObj().withStringField(null), null)
                .isFailure())
        .isTrue();
  }

  @Test
  void testCrossDatabase_nestedImmutableFailure() {
    // Repeat the null checks to verify that the attribute path is preserved.
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(makeTestObj().withNested(null)));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$TestImmutableObject.nested:"
                + " expected null, got TestImmutableObject");
    e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj().withNested(null))
                    .isEqualAcrossDatabases(makeTestObj()));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$TestImmutableObject.nested:"
                + " expected TestImmutableObject");
    assertThat(e).hasMessageThat().contains("got null.");

    // Test with a field difference.
    e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(
                        makeTestObj().withNested(makeTestObj().withNested(null))));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$"
                + "TestImmutableObject.nested.stringField:");
    assertThat(
            checkObjectAcrossDatabases(makeTestObj(), makeTestObj().withNested(null), null)
                .isFailure())
        .isTrue();
  }

  @Test
  void testCrossDatabase_listFailure() {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(makeTestObj().withList(null)));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$" + "TestImmutableObject.list:");
    e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(
                        makeTestObj().withList(ImmutableList.of(makeTestAtom("wack")))));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$"
                + "TestImmutableObject.list[0].stringField:");
    e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(
                        makeTestObj()
                            .withList(
                                ImmutableList.of(
                                    makeTestAtom("baz"),
                                    makeTestAtom("bot"),
                                    makeTestAtom("boq")))));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$"
                + "TestImmutableObject.list: missing items");
    // Make sure multiple additional items get formatted nicely.
    assertThat(e).hasMessageThat().contains("}, TestImmutableObject");
    e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(makeTestObj().withList(ImmutableList.of())));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$"
                + "TestImmutableObject.list: has additional items");
    assertThat(
            checkObjectAcrossDatabases(
                    makeTestObj(),
                    makeTestObj()
                        .withList(ImmutableList.of(makeTestAtom("baz"), makeTestAtom("gauze"))),
                    null)
                .isFailure())
        .isTrue();
    assertThat(
            checkObjectAcrossDatabases(
                    makeTestObj(), makeTestObj().withList(ImmutableList.of()), null)
                .isFailure())
        .isTrue();
    assertThat(
            checkObjectAcrossDatabases(
                    makeTestObj(),
                    makeTestObj().withList(ImmutableList.of(makeTestAtom("gauze"))),
                    null)
                .isFailure())
        .isTrue();
  }

  @Test
  void testCrossDatabase_setFailure() {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(makeTestObj().withSet(null)));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$"
                + "TestImmutableObject.set: expected null, got ");

    e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(
                        makeTestObj().withSet(ImmutableSet.of(makeTestAtom("jim")))));
    assertThat(e)
        .hasMessageThat()
        .containsMatch(
            Pattern.compile(
                "Set does not contain the expected contents.  "
                    + "It is missing: .*jim.*  It contains additional elements: .*bob",
                Pattern.DOTALL));

    // Trickery here to verify that multiple items that both match existing items in the set trigger
    // an error: we can add two of the same items because equality for purposes of the set includes
    // the DoNotCompare field.
    e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(
                        makeTestObj()
                            .withSet(ImmutableSet.of(makeTestAtom("bob"), makeTestAtom("bob")))));
    assertThat(e)
        .hasMessageThat()
        .containsMatch(
            Pattern.compile(
                "At google.registry.model.ImmutableObjectSubjectTest\\$TestImmutableObject.set: "
                    + "element .*bob.* matches multiple elements in .*bob.*bob",
                Pattern.DOTALL));
    e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(
                        makeTestObj()
                            .withSet(ImmutableSet.of(makeTestAtom("bob"), makeTestAtom("bob"))))
                    .isEqualAcrossDatabases(makeTestObj()));
    assertThat(e)
        .hasMessageThat()
        .containsMatch(
            Pattern.compile(
                "At google.registry.model.ImmutableObjectSubjectTest\\$TestImmutableObject.set: "
                    + "Set does not contain the expected contents.  It contains additional "
                    + "elements: .*bob",
                Pattern.DOTALL));

    assertThat(
            checkObjectAcrossDatabases(
                    makeTestObj(),
                    makeTestObj()
                        .withSet(ImmutableSet.of(makeTestAtom("bob"), makeTestAtom("robert"))),
                    null)
                .isFailure())
        .isTrue();
    assertThat(
            checkObjectAcrossDatabases(
                    makeTestObj(), makeTestObj().withSet(ImmutableSet.of()), null)
                .isFailure())
        .isTrue();
    assertThat(
            checkObjectAcrossDatabases(
                    makeTestObj(),
                    makeTestObj()
                        .withSet(ImmutableSet.of(makeTestAtom("bob"), makeTestAtom("bob"))),
                    null)
                .isFailure())
        .isTrue();
    // We don't test the case where actual's set contains multiple items matching the single item in
    // the expected set: that path is the same as the "additional contents" path.
  }

  @Test
  void testCrossDatabase_mapFailure() {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(makeTestObj().withMap(null)));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$"
                + "TestImmutableObject.map: expected null, got ");

    e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(makeTestObj())
                    .isEqualAcrossDatabases(
                        makeTestObj()
                            .withMap(ImmutableMap.of(makeTestAtom("difk"), makeTestAtom("difv")))));
    assertThat(e)
        .hasMessageThat()
        .containsMatch(
            Pattern.compile(
                "Map does not contain the expected contents.  "
                    + "It is missing: .*difk.*difv.*  It contains additional elements: .*key.*val",
                Pattern.DOTALL));
    assertThat(
            checkObjectAcrossDatabases(
                    makeTestObj(),
                    makeTestObj()
                        .withMap(
                            ImmutableMap.of(
                                makeTestAtom("key"), makeTestAtom("val"),
                                makeTestAtom("otherk"), makeTestAtom("otherv"))),
                    null)
                .isFailure())
        .isTrue();
    assertThat(
            checkObjectAcrossDatabases(
                    makeTestObj(), makeTestObj().withMap(ImmutableMap.of()), null)
                .isFailure())
        .isTrue();
  }

  @Test
  void testCrossDatabase_typeChecks() {
    ComparisonResult result = checkObjectAcrossDatabases("blech", makeTestObj(), "xxx");
    assertThat(result.getMessage()).isEqualTo("At xxx: blech is not an immutable object.");
    assertThat(result.isFailure()).isTrue();
    assertThat(checkObjectAcrossDatabases("blech", makeTestObj(), null).isFailure()).isTrue();

    result = checkObjectAcrossDatabases("blech", ImmutableMap.of(), "xxx");
    assertThat(result.getMessage()).isEqualTo("At xxx: blech is not a Map.");
    assertThat(result.isFailure()).isTrue();
    assertThat(checkObjectAcrossDatabases("blech", ImmutableMap.of(), null).isFailure()).isTrue();

    result = checkObjectAcrossDatabases("blech", ImmutableList.of(), "xxx");
    assertThat(result.getMessage()).isEqualTo("At xxx: blech is not a Collection.");
    assertThat(result.isFailure()).isTrue();
    assertThat(checkObjectAcrossDatabases("blech", ImmutableList.of(), null).isFailure()).isTrue();

    for (ImmutableMap.Entry<String, String> entry : ImmutableMap.of("foo", "bar").entrySet()) {
      result = checkObjectAcrossDatabases("blech", entry, "xxx");
      assertThat(result.getMessage()).isEqualTo("At xxx: blech is not a Map.Entry.");
      assertThat(result.isFailure()).isTrue();
      assertThat(checkObjectAcrossDatabases("blech", entry, "xxx").isFailure()).isTrue();
    }
  }

  @Test
  void testCrossDatabase_checkAdditionalFields() {
    AssertionError e =
        assertThrows(
            AssertionError.class,
            () ->
                assertAboutImmutableObjects()
                    .that(DerivedImmutableObject.create())
                    .isEqualAcrossDatabases(makeTestAtom(null)));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "At google.registry.model.ImmutableObjectSubjectTest$DerivedImmutableObject: "
                + "has additional field extraField");

    assertThat(
            checkObjectAcrossDatabases(DerivedImmutableObject.create(), makeTestAtom(null), null)
                .isFailure())
        .isTrue();
  }

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

    @ImmutableObject.DoNotCompare int ignored;

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
      instance.ignored = ++uniqueId;
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
