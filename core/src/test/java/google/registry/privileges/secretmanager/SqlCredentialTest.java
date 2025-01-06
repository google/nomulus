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

package google.registry.privileges.secretmanager;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link SqlCredential}. */
public class SqlCredentialTest {

  @Test
  void secrets_arentWrittenOutByToString() {
    SqlCredential cred = SqlCredential.create("joshua", "rosebud");
    String credStr = cred.toString();
    assertThat(credStr).doesNotContain("joshua");
    assertThat(credStr).doesNotContain("rosebud");
  }
}
