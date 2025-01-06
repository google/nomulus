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

import google.registry.bsa.api.UnblockableDomain.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UnblockableDomain}. */
class UnblockableDomainTest {

  UnblockableDomain unit;

  @BeforeEach
  void setup() {
    unit = new UnblockableDomain("buy.app", Reason.REGISTERED);
  }

  @Test
  void serialize_success() {
    assertThat(unit.serialize()).isEqualTo("buy.app,REGISTERED");
  }

  @Test
  void deserialize_success() {
    assertThat(UnblockableDomain.deserialize("buy.app,REGISTERED")).isEqualTo(unit);
  }

  @Test
  void alt_of() {
    assertThat(UnblockableDomain.of("buy", "app", Reason.REGISTERED)).isEqualTo(unit);
  }
}
