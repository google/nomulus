// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.DiffUtils.prettyPrintEntityDeepDiff;
import static google.registry.util.DiffUtils.prettyPrintSetDiff;

import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DiffUtils} */
class DiffUtilsTest {

  @Test
  void test_prettyPrintSetDiff_emptySets() {
    assertThat(prettyPrintSetDiff(ImmutableSet.of(), ImmutableSet.of()))
        .isEqualTo("NO DIFFERENCES");
  }

  @Test
  void test_prettyPrintSetDiff_noDifferences() {
    assertThat(prettyPrintSetDiff(ImmutableSet.of("c", "x", "m"), ImmutableSet.of("m", "x", "c")))
        .isEqualTo("NO DIFFERENCES");
  }

  @Test
  void test_prettyPrintSetDiff_addedElements() {
    assertThat(prettyPrintSetDiff(ImmutableSet.of("z"), ImmutableSet.of("a", "b", "z")))
        .isEqualTo("\n    ADDED: [a, b]\n    FINAL CONTENTS: [a, b, z]");
  }

  @Test
  void test_prettyPrintSetDiff_removedElements() {
    assertThat(prettyPrintSetDiff(ImmutableSet.of("x", "y", "z"), ImmutableSet.of("y")))
        .isEqualTo("\n    REMOVED: [x, z]\n    FINAL CONTENTS: [y]");
  }

  @Test
  void test_prettyPrintSetDiff_addedAndRemovedElements() {
    assertThat(prettyPrintSetDiff(ImmutableSet.of("a", "b", "c"), ImmutableSet.of("a", "y", "z")))
        .isEqualTo("\n    ADDED: [y, z]\n    REMOVED: [b, c]\n    FINAL CONTENTS: [a, y, z]");
  }

  @Test
  void test_emptyToNullCollection_doesntDisplay() {
    Map<String, Object> mapA = new HashMap<>();
    mapA.put("a", "jim");
    mapA.put("b", null);
    Map<String, Object> mapB = new HashMap<>();
    mapB.put("a", "tim");
    mapB.put("b", ImmutableSet.of());
    // This ensures that it is not outputting a diff of b: null -> [].
    assertThat(prettyPrintEntityDeepDiff(mapA, mapB)).isEqualTo("a: jim -> tim\n");
  }

  @Test
  void test_prettyPrintSetDiff_addedAndRemovedElements_objects() {
    DummyObject a = DummyObject.create("a");
    DummyObject b = DummyObject.create("b");
    DummyObject c = DummyObject.create("c");

    assertThat(prettyPrintSetDiff(ImmutableSet.of(a, b), ImmutableSet.of(a, c)))
        .isEqualTo(
            "\n"
                + "    ADDED:\n"
                + "        {c}\n"
                + "    REMOVED:\n"
                + "        {b}\n"
                + "    FINAL CONTENTS:\n"
                + "        {a},\n"
                + "        {c}");
  }

  private static class DummyObject {
    String id;

    static DummyObject create(String id) {
      DummyObject instance = new DummyObject();
      instance.id = id;
      return instance;
    }

    @Override
    public String toString() {
      return String.format("{%s}", id);
    }
  }
}
