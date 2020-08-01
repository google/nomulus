// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.proxy.quota;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.proxy.ProxyConfig.Quota;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Unit Tests for {@link QuotaConfig} */
class QuotaConfigTest {

  private QuotaConfig quotaConfig;

  private static QuotaConfig loadQuotaConfig(String filename) {
    return new QuotaConfig(
        new Yaml()
            .loadAs(readResourceUtf8(QuotaConfigTest.class, filename), Quota.class),
        "theProtocol");
  }

  private void validateQuota(String userId, int tokenAmount, int refillSeconds) {
    assertThat(quotaConfig.hasUnlimitedTokens(userId)).isFalse();
    assertThat(quotaConfig.getTokenAmount(userId)).isEqualTo(tokenAmount);
    assertThat(quotaConfig.getRefillPeriod(userId))
        .isEqualTo(Duration.standardSeconds(refillSeconds));
    assertThat(quotaConfig.getProtocolName()).isEqualTo("theProtocol");
  }

  @Test
  void testSuccess_regularConfig() {
    quotaConfig = loadQuotaConfig("quota_config_regular.yaml");
    assertThat(quotaConfig.getRefreshPeriod()).isEqualTo(Duration.standardHours(1));
    validateQuota("abc", 10, 60);
    validateQuota("987lol", 500, 10);
    validateQuota("no_match", 100, 60);
  }

  @Test
  void testSuccess_onlyDefault() {
    quotaConfig = loadQuotaConfig("quota_config_default.yaml");
    assertThat(quotaConfig.getRefreshPeriod()).isEqualTo(Duration.standardHours(1));
    validateQuota("abc", 100, 60);
    validateQuota("987lol", 100, 60);
    validateQuota("no_match", 100, 60);
  }

  @Test
  void testSuccess_noRefresh_noRefill() {
    quotaConfig = loadQuotaConfig("quota_config_no_refresh_no_refill.yaml");
    assertThat(quotaConfig.getRefreshPeriod()).isEqualTo(Duration.ZERO);
    assertThat(quotaConfig.getRefillPeriod("no_match")).isEqualTo(Duration.ZERO);
  }

  @Test
  void testFailure_getTokenAmount_throwsOnUnlimitedTokens() {
    quotaConfig = loadQuotaConfig("quota_config_unlimited_tokens.yaml");
    assertThat(quotaConfig.hasUnlimitedTokens("some_user")).isTrue();
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> quotaConfig.getTokenAmount("some_user"));
    assertThat(e)
        .hasMessageThat()
        .contains("User ID some_user is provisioned with unlimited tokens");
  }

  @Test
  void testFailure_duplicateUserId() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> loadQuotaConfig("quota_config_duplicate.yaml"));
    assertThat(e).hasMessageThat().contains("Multiple entries with same key");
  }
}
