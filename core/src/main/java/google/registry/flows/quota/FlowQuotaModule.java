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

import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.quota.NoopQuotaManager;
import google.registry.quota.QuotaManager;
import google.registry.quota.ValkeyQuotaManager;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Optional;
import redis.clients.jedis.UnifiedJedis;

@Module
public class FlowQuotaModule {

  @Provides
  @Singleton
  static FlowQuotaManager provideFlowQuotaManager(
      Optional<UnifiedJedis> jedis,
      @Config("domainCreateThrottleWindowDuration") Duration domainCreateThrottleWindowDuration,
      @Config("domainCreateThrottleWindowTokens") int domainCreateThrottleWindowTokens) {
    QuotaManager quotaManager =
        jedis.isPresent() ? new ValkeyQuotaManager(jedis.get(), "flow") : new NoopQuotaManager();
    return new FlowQuotaManager(
        quotaManager, domainCreateThrottleWindowTokens, domainCreateThrottleWindowDuration);
  }
}
