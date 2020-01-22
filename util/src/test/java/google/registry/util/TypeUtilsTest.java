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
import static org.junit.Assert.assertThrows;

import java.io.Serializable;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TypeUtils}. */
@RunWith(JUnit4.class)
public class TypeUtilsTest {
  @Test
  public void test_getClassFromString_validClass() {
    Class<? extends Serializable> clazz =
        TypeUtils.getClassFromString("java.util.ArrayList", Serializable.class);
    assertThat(clazz).isEqualTo(ArrayList.class);
  }

  @Test
  public void test_getClassFromString_notAssignableFrom() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> TypeUtils.getClassFromString("java.util.ArrayList", Integer.class));
    assertThat(thrown).hasMessageThat().contains("ArrayList does not implement/extend Integer");
  }

  @Test
  public void test_getClassFromString_unknownClass() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> TypeUtils.getClassFromString("com.fake.company.nonexistent.Class", Object.class));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Failed to load class com.fake.company.nonexistent.Class");
  }

  public static class ExampleClass {
    String val;

    public ExampleClass(String val) {
      this.val = val;
    }
  }

  @Test
  public void test_instantiateWithArg() {
    Class<ExampleClass> clazz =
        TypeUtils.getClassFromString(
            "google.registry.util.TypeUtilsTest$ExampleClass", ExampleClass.class);

    ExampleClass result = TypeUtils.instantiate(clazz, "test");
    assertThat(result.val).isEqualTo("test");
  }
}
