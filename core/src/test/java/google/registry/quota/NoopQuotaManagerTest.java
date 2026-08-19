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

package google.registry.quota;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NoopQuotaManagerTest {

  private final NoopQuotaManager quotaManager = new NoopQuotaManager();

  @Test
  void testAcquireQuota_returnsTrue() {
    assertThat(quotaManager.acquireQuota("user1", 10, Duration.ofMinutes(1))).isTrue();
  }

  @Test
  void testRefreshQuota_noop() {
    assertDoesNotThrow(() -> quotaManager.refreshQuota("user1", Duration.ofMinutes(1)));
  }

  @Test
  void testReleaseQuota_noop() {
    assertDoesNotThrow(() -> quotaManager.releaseQuota("user1", 10));
  }
}
