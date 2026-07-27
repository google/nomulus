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

package google.registry.eppserver.quota;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalConnectionLimiterTest {

  private LocalConnectionLimiter limiter;

  @BeforeEach
  void setUp() {
    // Set a small limit of 2 for testing purposes.
    limiter = new LocalConnectionLimiter(2, 2);
  }

  @Test
  void testAcquireIp_successUpToLimit() {
    assertThat(limiter.acquireIp("192.168.1.1")).isTrue();
    assertThat(limiter.acquireIp("192.168.1.1")).isTrue();
  }

  @Test
  void testAcquireIp_rejectsOverLimit() {
    assertThat(limiter.acquireIp("192.168.1.1")).isTrue();
    assertThat(limiter.acquireIp("192.168.1.1")).isTrue();
    // 3rd attempt from same IP should be rejected
    assertThat(limiter.acquireIp("192.168.1.1")).isFalse();
  }

  @Test
  void testAcquireIp_independentAcrossIps() {
    assertThat(limiter.acquireIp("192.168.1.1")).isTrue();
    assertThat(limiter.acquireIp("192.168.1.1")).isTrue();
    assertThat(limiter.acquireIp("192.168.1.1")).isFalse();

    // A different IP should still be allowed
    assertThat(limiter.acquireIp("10.0.0.1")).isTrue();
  }

  @Test
  void testReleaseIp_freesSlot() {
    assertThat(limiter.acquireIp("192.168.1.1")).isTrue();
    assertThat(limiter.acquireIp("192.168.1.1")).isTrue();
    assertThat(limiter.acquireIp("192.168.1.1")).isFalse();

    limiter.releaseIp("192.168.1.1");
    // Now we should be able to acquire again
    assertThat(limiter.acquireIp("192.168.1.1")).isTrue();
  }

  @Test
  void testAcquireRegistrar_successUpToLimit() {
    assertThat(limiter.acquireRegistrar("registrar_1")).isTrue();
    assertThat(limiter.acquireRegistrar("registrar_1")).isTrue();
  }

  @Test
  void testAcquireRegistrar_rejectsOverLimit() {
    assertThat(limiter.acquireRegistrar("registrar_1")).isTrue();
    assertThat(limiter.acquireRegistrar("registrar_1")).isTrue();
    // 3rd attempt from same registrar should be rejected
    assertThat(limiter.acquireRegistrar("registrar_1")).isFalse();
  }

  @Test
  void testAcquireRegistrar_independentAcrossRegistrars() {
    assertThat(limiter.acquireRegistrar("registrar_1")).isTrue();
    assertThat(limiter.acquireRegistrar("registrar_1")).isTrue();
    assertThat(limiter.acquireRegistrar("registrar_1")).isFalse();

    // A different registrar should still be allowed
    assertThat(limiter.acquireRegistrar("registrar_2")).isTrue();
  }

  @Test
  void testReleaseRegistrar_freesSlot() {
    assertThat(limiter.acquireRegistrar("registrar_1")).isTrue();
    assertThat(limiter.acquireRegistrar("registrar_1")).isTrue();
    assertThat(limiter.acquireRegistrar("registrar_1")).isFalse();

    limiter.releaseRegistrar("registrar_1");
    // Now we should be able to acquire again
    assertThat(limiter.acquireRegistrar("registrar_1")).isTrue();
  }
}
