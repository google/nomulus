// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link DigestType}. */
class DigestTypeTest {

  @Test
  void testFromWireValue_sha1_returnsSha1() {
    assertThat(DigestType.fromWireValue(1)).hasValue(DigestType.SHA1);
  }

  @Test
  void testFromWireValue_sha256_returnsSha256() {
    assertThat(DigestType.fromWireValue(2)).hasValue(DigestType.SHA256);
  }

  @Test
  void testFromWireValue_gost_returnsEmpty() {
    assertThat(DigestType.fromWireValue(3)).isEmpty();
  }

  @Test
  void testFromWireValue_sha384_returnsSha384() {
    assertThat(DigestType.fromWireValue(4)).hasValue(DigestType.SHA384);
  }

  @Test
  void testFromWireValue_invalid_returnsEmpty() {
    assertThat(DigestType.fromWireValue(5)).isEmpty();
  }
}
