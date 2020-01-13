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

import com.google.common.collect.ImmutableList;
import com.google.common.testing.NullPointerTester;
import com.google.common.util.concurrent.UncheckedExecutionException;
import google.registry.testing.AppEngineRule;
import java.util.function.Function;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Concurrent}. */
@RunWith(JUnit4.class)
public class ConcurrentTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Test
  public void testTransform_emptyList_returnsEmptyList() {
    assertThat(Concurrent.transform(ImmutableList.of(), x -> x)).isEmpty();
  }

  @Test
  public void testTransform_addIntegers() {
    assertThat(Concurrent.transform(ImmutableList.of(1, 2, 3), input -> input + 1))
        .containsExactly(2, 3, 4)
        .inOrder();
  }

  @Test
  public void testTransform_throwsException_isSinglyWrappedByUee() {
    UncheckedExecutionException e =
        assertThrows(
            UncheckedExecutionException.class,
            () ->
                Concurrent.transform(
                    ImmutableList.of(1, 2, 3),
                    input -> {
                      throw new RuntimeException("hello");
                    }));
    assertThat(e).hasCauseThat().isInstanceOf(RuntimeException.class);
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("hello");
  }

  @Test
  public void testNullness() {
    NullPointerTester tester = new NullPointerTester().setDefault(Function.class, x -> x);
    tester.testAllPublicStaticMethods(Concurrent.class);
  }
}
