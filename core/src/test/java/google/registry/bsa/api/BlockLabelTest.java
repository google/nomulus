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

package google.registry.bsa.api;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;
import google.registry.bsa.api.BlockLabel.LabelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BlockLabel}. */
class BlockLabelTest {

  BlockLabel label;

  @BeforeEach
  void setup() {
    label = BlockLabel.create("buy", LabelType.CREATE, ImmutableSet.of("JA", "EXTENDED_LATIN"));
  }

  @Test
  void serialize_success() {
    assertThat(label.serialize()).isEqualTo("buy,CREATE,EXTENDED_LATIN,JA");
  }

  @Test
  void deserialize_success() {
    assertThat(BlockLabel.deserialize("buy,CREATE,EXTENDED_LATIN,JA")).isEqualTo(label);
  }

  @Test
  void emptyIdns() {
    label = BlockLabel.create("buy", LabelType.CREATE, ImmutableSet.of());
    assertThat(label.serialize()).isEqualTo("buy,CREATE");
    assertThat(BlockLabel.deserialize("buy,CREATE")).isEqualTo(label);
  }
}
