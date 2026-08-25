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
import static org.mockito.Mockito.when;

import google.registry.flows.domain.DomainCheckFlow;
import google.registry.flows.domain.DomainCreateFlow;
import google.registry.flows.quota.FlowQuotaManager.MissingTargetIdException;
import google.registry.flows.quota.FlowQuotaManager.TooManyRequestsException;
import google.registry.model.eppinput.EppInput;
import google.registry.quota.ValkeyQuotaManager;
import io.github.ss_bhatt.testcontainers.valkey.ValkeyContainer;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;

/** Tests for {@link FlowQuotaManager} backed by Valkey. */
@Testcontainers
class FlowQuotaManagerTest {

  private static final int DEFAULT_TOKENS = 3;
  private static final Duration DEFAULT_DURATION = Duration.ofSeconds(10);

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
  void testAcquireQuota_nonDomainCreateFlow_noOp() {
    FlowQuotaManager manager = new FlowQuotaManager(quotaManager, DEFAULT_TOKENS, DEFAULT_DURATION);
    assertDoesNotThrow(() -> manager.acquireQuota(DomainCheckFlow.class, eppInput, "TheRegistrar"));
    assertThat(jedis.keys("*")).isEmpty();
  }

  @Test
  void testAcquireQuota_missingTargetId_throwsMissingTargetIdException() {
    FlowQuotaManager manager = new FlowQuotaManager(quotaManager, DEFAULT_TOKENS, DEFAULT_DURATION);
    when(eppInput.getSingleTargetId()).thenReturn(Optional.empty());
    MissingTargetIdException thrown =
        assertThrows(
            MissingTargetIdException.class,
            () -> manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar"));
    assertThat(thrown).hasMessageThat().contains("Required target identifier is missing");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testAcquireQuota_success() throws Exception {
    when(eppInput.getSingleTargetId()).thenReturn(Optional.of("example.tld"));
    FlowQuotaManager manager = new FlowQuotaManager(quotaManager, DEFAULT_TOKENS, DEFAULT_DURATION);

    manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar");
    assertThat(jedis.get("flow:TheRegistrar:example.tld")).isEqualTo("2");

    manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar");
    assertThat(jedis.get("flow:TheRegistrar:example.tld")).isEqualTo("1");
  }

  @Test
  void testAcquireQuota_normalizesDomainNameToLowerCase() throws Exception {
    when(eppInput.getSingleTargetId()).thenReturn(Optional.of("EXAMPLE.TLD"));
    FlowQuotaManager manager = new FlowQuotaManager(quotaManager, DEFAULT_TOKENS, DEFAULT_DURATION);

    manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar");
    assertThat(jedis.get("flow:TheRegistrar:example.tld")).isEqualTo("2");
  }

  @Test
  void testAcquireQuota_trimsWhitespace() throws Exception {
    when(eppInput.getSingleTargetId()).thenReturn(Optional.of("  example.tld  "));
    FlowQuotaManager manager = new FlowQuotaManager(quotaManager, DEFAULT_TOKENS, DEFAULT_DURATION);

    manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar");
    assertThat(jedis.get("flow:TheRegistrar:example.tld")).isEqualTo("2");
  }

  @Test
  void testAcquireQuota_exceeded_throwsTooManyRequestsException() throws Exception {
    when(eppInput.getSingleTargetId()).thenReturn(Optional.of("example.tld"));
    FlowQuotaManager manager = new FlowQuotaManager(quotaManager, DEFAULT_TOKENS, DEFAULT_DURATION);

    for (int i = 0; i < 3; i++) {
      manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar");
    }

    TooManyRequestsException thrown =
        assertThrows(
            TooManyRequestsException.class,
            () -> manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar"));
    assertThat(thrown).hasMessageThat().contains("Too many requests");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testAcquireQuota_isolatedByRegistrarId() throws Exception {
    when(eppInput.getSingleTargetId()).thenReturn(Optional.of("example.tld"));
    FlowQuotaManager manager = new FlowQuotaManager(quotaManager, DEFAULT_TOKENS, DEFAULT_DURATION);

    for (int i = 0; i < 3; i++) {
      manager.acquireQuota(DomainCreateFlow.class, eppInput, "RegistrarA");
    }
    assertThrows(
        TooManyRequestsException.class,
        () -> manager.acquireQuota(DomainCreateFlow.class, eppInput, "RegistrarA"));

    // RegistrarB has independent quota
    assertDoesNotThrow(() -> manager.acquireQuota(DomainCreateFlow.class, eppInput, "RegistrarB"));
    assertThat(jedis.get("flow:RegistrarA:example.tld")).isEqualTo("0");
    assertThat(jedis.get("flow:RegistrarB:example.tld")).isEqualTo("2");
  }

  @Test
  void testAcquireQuota_isolatedByDomainName() throws Exception {
    FlowQuotaManager manager = new FlowQuotaManager(quotaManager, DEFAULT_TOKENS, DEFAULT_DURATION);

    when(eppInput.getSingleTargetId()).thenReturn(Optional.of("domain1.tld"));
    for (int i = 0; i < 3; i++) {
      manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar");
    }
    assertThrows(
        TooManyRequestsException.class,
        () -> manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar"));

    // Different domain has independent quota
    when(eppInput.getSingleTargetId()).thenReturn(Optional.of("domain2.tld"));
    assertDoesNotThrow(
        () -> manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar"));
    assertThat(jedis.get("flow:TheRegistrar:domain1.tld")).isEqualTo("0");
    assertThat(jedis.get("flow:TheRegistrar:domain2.tld")).isEqualTo("2");
  }

  @Test
  void testAcquireQuota_releaseQuotaAfterDuration() throws Exception {
    FlowQuotaManager manager = new FlowQuotaManager(quotaManager, 2, Duration.ofMillis(50));
    when(eppInput.getSingleTargetId()).thenReturn(Optional.of("example.tld"));

    manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar");
    manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar");
    assertThrows(
        TooManyRequestsException.class,
        () -> manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar"));

    Thread.sleep(150);
    assertDoesNotThrow(
        () -> manager.acquireQuota(DomainCreateFlow.class, eppInput, "TheRegistrar"));
  }
}
