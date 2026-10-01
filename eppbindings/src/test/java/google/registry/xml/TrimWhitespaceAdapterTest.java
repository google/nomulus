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

package google.registry.xml;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link TrimWhitespaceAdapter}. */
class TrimWhitespaceAdapterTest {

  @Test
  void testUnmarshal() {
    TrimWhitespaceAdapter adapter = new TrimWhitespaceAdapter();
    assertThat(adapter.unmarshal("blah")).isEqualTo("blah");
    assertThat(adapter.unmarshal("")).isEmpty();
    assertThat(adapter.unmarshal(null)).isNull();
    assertThat(adapter.unmarshal("\n    test foo bar   \n   \r")).isEqualTo("test foo bar");
  }

  @Test
  void testMarshal() {
    TrimWhitespaceAdapter adapter = new TrimWhitespaceAdapter();
    assertThat(adapter.marshal("blah")).isEqualTo("blah");
    assertThat(adapter.marshal("")).isEmpty();
    assertThat(adapter.marshal(null)).isNull();
    assertThat(adapter.marshal("\n    test foo bar   \n   \r"))
        .isEqualTo("\n    test foo bar   \n   \r");
  }
}
