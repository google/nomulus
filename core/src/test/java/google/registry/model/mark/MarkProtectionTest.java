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

package google.registry.model.mark;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MarkProtection}. */
final class MarkProtectionTest {

  @Test
  void testDeadCodeWeDontWantToDelete() {
    MarkProtection mp = new MarkProtection();
    mp.countryCode = "US";
    assertThat(mp.getCountryCode()).isEqualTo("US");
    mp.region = "New York";
    assertThat(mp.getRegion()).isEqualTo("New York");
    mp.rulingCountryCodes = ImmutableList.of("US", "CN");
    assertThat(mp.getRulingCountryCodes()).isEqualTo(ImmutableList.of("US", "CN"));
  }
}
