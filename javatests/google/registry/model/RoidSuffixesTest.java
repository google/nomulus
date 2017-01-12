// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.RoidSuffixes.getRoidSuffixForTld;
import static google.registry.testing.DatastoreHelper.newRegistry;
import static google.registry.testing.DatastoreHelper.persistResource;

import google.registry.testing.AppEngineRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RoidSuffixes}. */
@RunWith(JUnit4.class)
public class RoidSuffixesTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Test
  public void test_newlyCreatedRegistry_isAddedToRoidSuffixesList() {
    persistResource(newRegistry("tld", "MEOW"));
    assertThat(getRoidSuffixForTld("tld", true)).isEqualTo("MEOW");
  }

  @Test
  public void test_shouldAllowTldsWithSameRoidSuffix_whenNotRequiredUnique() {
    persistResource(newRegistry("wtf", "donuts"));
    persistResource(newRegistry("ltd", "donuts"));

    assertThat(getRoidSuffixForTld("wtf", false)).isEqualTo("donuts");
    assertThat(getRoidSuffixForTld("ltd", false)).isEqualTo("donuts");
  }

  @Test(expected = IllegalArgumentException.class)
  public void test_shouldNotAllowTldsWithSameRoidSuffix_whenRequiredUnique() {
    persistResource(newRegistry("wtf", "donuts"));
    persistResource(newRegistry("ltd", "donuts"));

    assertThat(getRoidSuffixForTld("wtf", true)).isEqualTo("donuts");
    assertThat(getRoidSuffixForTld("ltd", true)).isEqualTo("donuts");
  }
}
