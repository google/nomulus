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

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.annotation.Id;
import google.registry.testing.AppEngineRule;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ModelUtils}. */
public class ModelUtilsTest {

  @RegisterExtension public AppEngineRule appEngineRule = new AppEngineRule.Builder().build();

  /** Test class for reflection methods. */
  public static class TestClass extends ImmutableObject implements Buildable {

    @Id
    String id;

    String a;

    String b;

    /** Note that there is no getter for {@link #b}.*/
    public String getId() {
      return id;
    }

    public String getA() {
      return a;
    }

    /** Builder for {@link TestClass}. Note that there is no setter for {@link #a}.*/
    public static class Builder extends Buildable.Builder<TestClass> {

      protected Builder() {}

      protected Builder(TestClass instance) {
        super(instance);
      }

      public Builder setId(String id) {
        getInstance().id = id;
        return this;
      }

      public Builder setB(String b) {
        getInstance().b = b;
        return this;
      }
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }
  }

  @BeforeEach
  void resetCaches() {
    ModelUtils.resetCaches();
  }

  @Test
  void testGetAllFields() throws Exception {
    Map<String, Field> expected = ImmutableMap.of(
        "id", TestClass.class.getDeclaredField("id"),
        "a", TestClass.class.getDeclaredField("a"),
        "b", TestClass.class.getDeclaredField("b"));
    // More complicated version of isEqualTo() so that we check for ordering.
    assertThat(ModelUtils.getAllFields(TestClass.class).entrySet())
        .containsExactlyElementsIn(expected.entrySet())
        .inOrder();
    // Test again, to make sure we hit the cache.
    assertThat(ModelUtils.getAllFields(TestClass.class).entrySet())
        .containsExactlyElementsIn(expected.entrySet())
        .inOrder();
  }

  @Test
  void testGetFieldValues() throws Exception {
    TestClass testInstance = new TestClass();
    testInstance.id = "foo";
    testInstance.a = "a";
    testInstance.b = "b";
    assertThat(ModelUtils.getFieldValues(testInstance))
        .containsExactly(
            TestClass.class.getDeclaredField("id"), "foo",
            TestClass.class.getDeclaredField("a"), "a",
            TestClass.class.getDeclaredField("b"), "b")
        .inOrder();
    // Test again, to make sure we aren't caching values.
    testInstance.id = "bar";
    testInstance.a = "1";
    testInstance.b = "2";
    assertThat(ModelUtils.getFieldValues(testInstance))
        .containsExactly(
            TestClass.class.getDeclaredField("id"), "bar",
            TestClass.class.getDeclaredField("a"), "1",
            TestClass.class.getDeclaredField("b"), "2")
        .inOrder();
  }

  @Test
  void testBuildingResetsHashCode() {
    TestClass original = new TestClass();
    original.id = "foo";
    TestClass cloned = original.asBuilder().setId("bar").build();
    assertThat(cloned.hashCode()).isNotEqualTo(original.hashCode());
    cloned.id = "foo";  // Violates immutability contract.
    // The hashCode is now cached and is stale (but that's the expected behavior).
    assertThat(cloned.hashCode()).isNotEqualTo(original.hashCode());
  }
}
