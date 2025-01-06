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
import static google.registry.util.SerializeUtils.serialize;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SafeObjectInputStream}. */
public class SafeObjectInputStreamTest {

  @Test
  void javaUnitarySuccess() throws Exception {
    String orig = "some string";
    try (SafeObjectInputStream sois =
        new SafeObjectInputStream(new ByteArrayInputStream(serialize(orig)))) {
      assertThat(sois.readObject()).isEqualTo(orig);
    }
  }

  @Test
  void javaCollectionFailure() throws Exception {
    ArrayList<String> orig = newArrayList("a");
    try (SafeObjectInputStream sois =
        new SafeObjectInputStream(new ByteArrayInputStream(serialize(orig)))) {
      assertThrows(ClassNotFoundException.class, () -> sois.readObject());
    }
  }

  @Test
  void javaMapFailure() throws Exception {
    HashMap<Object, Object> orig = Maps.newHashMap();
    try (SafeObjectInputStream sois =
        new SafeObjectInputStream(new ByteArrayInputStream(serialize(orig)))) {
      assertThrows(ClassNotFoundException.class, () -> sois.readObject());
    }
  }

  @Test
  void javaArrayFailure() throws Exception {
    int[] orig = new int[] {1};
    try (SafeObjectInputStream sois =
        new SafeObjectInputStream(new ByteArrayInputStream(serialize(orig)))) {
      // For array, the parent class converts ClassNotFoundException in an undocumented way. Safer
      // to catch Exception than the one thrown by the current JVM.
      assertThrows(Exception.class, () -> sois.readObject());
    }
  }

  @Test
  void nonJavaNonNomulusUnitaryFailure() throws Exception {
    Serializable orig = Duration.millis(1);
    try (SafeObjectInputStream sois =
        new SafeObjectInputStream(new ByteArrayInputStream(serialize(orig)))) {
      assertThrows(ClassNotFoundException.class, () -> sois.readObject());
    }
  }

  @Test
  void nonJavaCollectionFailure() throws Exception {
    ImmutableList<String> orig = ImmutableList.of("a");
    try (SafeObjectInputStream sois =
        new SafeObjectInputStream(new ByteArrayInputStream(serialize(orig)))) {
      assertThrows(ClassNotFoundException.class, () -> sois.readObject());
    }
  }

  @Test
  void nomulusEntitySuccess() throws Exception {
    NomulusEntity orig = new NomulusEntity(1);
    byte[] serialized = serialize(orig);
    try (SafeObjectInputStream sois =
        new SafeObjectInputStream(new ByteArrayInputStream(serialized))) {
      Object deserialized = sois.readObject();
      assertThat(deserialized).isEqualTo(orig);
    }
  }

  static class NomulusEntity implements Serializable {
    Integer value;

    NomulusEntity(int value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof NomulusEntity that)) {
        return false;
      }
      return Objects.equal(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }
  }
}
