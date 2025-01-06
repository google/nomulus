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

package google.registry.util;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.SafeSerializationUtils.safeDeserialize;
import static google.registry.util.SafeSerializationUtils.safeDeserializeCollection;
import static google.registry.util.SafeSerializationUtils.serializeCollection;
import static google.registry.util.SerializeUtils.serialize;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SafeSerializationUtils}. */
public class SafeSerializationUtilsTest {

  @Test
  void deserialize_array_failure() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> safeDeserialize(serialize(new byte[0]))))
        .hasMessageThat()
        .contains("Failed to deserialize:");
  }

  @Test
  void deserialize_null_success() {
    assertThat(safeDeserialize(serialize(null))).isNull();
  }

  @Test
  void deserialize_map_failure() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> safeDeserialize(serialize(ImmutableMap.of()))))
        .hasMessageThat()
        .contains("Failed to deserialize:");
  }

  @Test
  void serializeDeserialize_null_success() {
    assertThat(safeDeserialize(null)).isNull();
  }

  @Test
  void serializeDeserialize_notCollection_success() {
    Integer orig = 1;
    assertThat(safeDeserialize(serialize(orig))).isEqualTo(orig);
  }

  @Test
  void serializeDeserializeCollection_success() {
    ArrayList<Integer> orig = newArrayList(1, 2, 3);
    ImmutableList<Integer> deserialized =
        safeDeserializeCollection(Integer.class, serializeCollection(orig));
    assertThat(deserialized).isEqualTo(orig);
  }

  @Test
  void serializeDeserializeCollection_withMaxSize_success() {
    Integer[] array = new Integer[SafeSerializationUtils.MAX_COLLECTION_SIZE];
    Arrays.fill(array, 1);
    ArrayList<Integer> orig = newArrayList(array);
    assertThat(safeDeserializeCollection(Integer.class, serializeCollection(orig))).isEqualTo(orig);
  }

  @Test
  void serializeDeserializeCollection_tooLarge_Failure() {
    Integer[] array = new Integer[SafeSerializationUtils.MAX_COLLECTION_SIZE + 1];
    Arrays.fill(array, 1);
    ArrayList<Integer> orig = newArrayList(array);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> safeDeserializeCollection(Integer.class, serializeCollection(orig))))
        .hasMessageThat()
        .contains("Too many elements");
  }

  @Test
  void serializeDeserializeCollection_wrong_elementType_success() {
    ArrayList<Integer> orig = newArrayList(1, 2, 3);
    assertThrows(
        IllegalArgumentException.class,
        () -> safeDeserializeCollection(Long.class, serializeCollection(orig)));
  }

  @Test
  void deserializeCollection_null_failure() {
    assertThrows(NullPointerException.class, () -> safeDeserializeCollection(Integer.class, null));
  }
}
