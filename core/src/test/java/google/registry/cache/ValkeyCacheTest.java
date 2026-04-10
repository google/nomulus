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

package google.registry.cache;

import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import io.github.ss_bhatt.testcontainers.valkey.ValkeyContainer;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class ValkeyCacheTest {

  @Container protected static final ValkeyContainer valkey = new ValkeyContainer();

  protected final FakeClock fakeClock = new FakeClock(DateTime.parse("2025-01-01T00:00:00.000Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  protected RedissonClient redissonClient;

  @BeforeEach
  void beforeEach() {
    redissonClient = createClient();
  }

  protected RedissonClient createClient() {
    org.redisson.config.Config config = new org.redisson.config.Config();
    config.useSingleServer().setAddress(valkey.getConnectionString());
    return Redisson.create(config);
  }
}
