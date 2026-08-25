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

package google.registry.flows.quota;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import google.registry.flows.Flow;
import google.registry.flows.quota.FlowQuotaManager.TooManyRequestsException;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppOutput.ResponseOrGreeting;
import google.registry.quota.ValkeyQuotaManager;
import io.github.ss_bhatt.testcontainers.valkey.ValkeyContainer;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;

/** Tests for {@link FlowQuotaManager} backed by Valkey. */
@Testcontainers
class FlowQuotaManagerTest {

  @Container private static final ValkeyContainer valkey = new ValkeyContainer();

  private RedisClient jedis;
  private ValkeyQuotaManager quotaManager;
  private final EppInput eppInput = mock(EppInput.class);

  @BeforeEach
  void setUp() {
    jedis =
        RedisClient.builder()
            .hostAndPort(new HostAndPort(valkey.getHost(), valkey.getFirstMappedPort()))
            .build();
    jedis.flushAll();
    quotaManager = new ValkeyQuotaManager(jedis, "flow");
  }

  @Test
  void testAcquireQuota_noConfiguredParameters_noOp() {
    FlowQuotaManager manager = FlowQuotaManager.create(quotaManager, ImmutableList.of());
    assertDoesNotThrow(() -> manager.acquireQuota(FlowA.class, eppInput, "TheRegistrar"));
    assertThat(jedis.keys("*")).isEmpty();
  }

  @Test
  void testAcquireQuota_unconfiguredFlow_noOp() {
    FlowQuotaParameters paramA =
        createParameters(FlowA.class, "quota-a", 10, Duration.ofMinutes(1));
    FlowQuotaManager manager = FlowQuotaManager.create(quotaManager, ImmutableList.of(paramA));
    assertDoesNotThrow(() -> manager.acquireQuota(FlowB.class, eppInput, "TheRegistrar"));
    assertThat(jedis.keys("*")).isEmpty();
  }

  @Test
  void testAcquireQuota_success() throws Exception {
    FlowQuotaParameters paramA = createParameters(FlowA.class, "quota-a", 2, Duration.ofMinutes(1));
    FlowQuotaManager manager = FlowQuotaManager.create(quotaManager, ImmutableList.of(paramA));

    manager.acquireQuota(FlowA.class, eppInput, "TheRegistrar");
    assertThat(jedis.get("flow:TheRegistrar:quota-a")).isEqualTo("1");

    manager.acquireQuota(FlowA.class, eppInput, "TheRegistrar");
    assertThat(jedis.get("flow:TheRegistrar:quota-a")).isEqualTo("0");
  }

  @Test
  void testAcquireQuota_exceeded_throwsTooManyRequestsException() throws Exception {
    FlowQuotaParameters paramA = createParameters(FlowA.class, "quota-a", 2, Duration.ofMinutes(1));
    FlowQuotaManager manager = FlowQuotaManager.create(quotaManager, ImmutableList.of(paramA));

    manager.acquireQuota(FlowA.class, eppInput, "TheRegistrar");
    manager.acquireQuota(FlowA.class, eppInput, "TheRegistrar");

    TooManyRequestsException thrown =
        assertThrows(
            TooManyRequestsException.class,
            () -> manager.acquireQuota(FlowA.class, eppInput, "TheRegistrar"));
    assertThat(thrown).hasMessageThat().contains("Too many requests");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testAcquireQuota_releaseQuotaAfterDuration() throws Exception {
    FlowQuotaParameters paramA = createParameters(FlowA.class, "quota-a", 2, Duration.ofMillis(10));
    FlowQuotaManager manager = FlowQuotaManager.create(quotaManager, ImmutableList.of(paramA));

    manager.acquireQuota(FlowA.class, eppInput, "TheRegistrar");
    Thread.sleep(30);
    assertDoesNotThrow(() -> manager.acquireQuota(FlowA.class, eppInput, "TheRegistrar"));
  }

  @Test
  void testAcquireQuota_isolatedByRegistrarId() throws Exception {
    FlowQuotaParameters paramA = createParameters(FlowA.class, "quota-a", 1, Duration.ofMinutes(1));
    FlowQuotaManager manager = FlowQuotaManager.create(quotaManager, ImmutableList.of(paramA));

    manager.acquireQuota(FlowA.class, eppInput, "RegistrarA");
    assertThrows(
        TooManyRequestsException.class,
        () -> manager.acquireQuota(FlowA.class, eppInput, "RegistrarA"));

    // RegistrarB has independent quota
    assertDoesNotThrow(() -> manager.acquireQuota(FlowA.class, eppInput, "RegistrarB"));
    assertThat(jedis.get("flow:RegistrarA:quota-a")).isEqualTo("0");
    assertThat(jedis.get("flow:RegistrarB:quota-a")).isEqualTo("0");
  }

  @Test
  void testAcquireQuota_multipleFlowParameters() throws Exception {
    FlowQuotaParameters paramA = createParameters(FlowA.class, "quota-a", 2, Duration.ofMinutes(1));
    FlowQuotaParameters paramB = createParameters(FlowB.class, "quota-b", 5, Duration.ofMinutes(5));
    FlowQuotaManager manager =
        FlowQuotaManager.create(quotaManager, ImmutableList.of(paramA, paramB));

    manager.acquireQuota(FlowA.class, eppInput, "TheRegistrar");
    manager.acquireQuota(FlowB.class, eppInput, "TheRegistrar");

    assertThat(jedis.get("flow:TheRegistrar:quota-a")).isEqualTo("1");
    assertThat(jedis.get("flow:TheRegistrar:quota-b")).isEqualTo("4");
  }

  static class FlowA implements Flow {
    @Override
    public ResponseOrGreeting run() {
      return null;
    }
  }

  static class FlowB implements Flow {
    @Override
    public ResponseOrGreeting run() {
      return null;
    }
  }

  private static FlowQuotaParameters createParameters(
      Class<? extends Flow> flowClass, String quotaPrefix, int maxQuota, Duration window) {
    return new FlowQuotaParameters() {
      @Override
      public Class<? extends Flow> getFlowClass() {
        return flowClass;
      }

      @Override
      public String getQuotaId(EppInput input, String registrarId) {
        return registrarId + ":" + quotaPrefix;
      }

      @Override
      public int getMaxQuotaAllowed() {
        return maxQuota;
      }

      @Override
      public Duration getWindowDuration() {
        return window;
      }
    };
  }
}
