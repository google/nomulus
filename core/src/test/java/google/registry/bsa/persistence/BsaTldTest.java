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

package google.registry.bsa.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.persistence.BsaTld.getActivatedBsaTldNames;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link BsaTld}. */
public class BsaTldTest {

  FakeClock fakeClock = new FakeClock(Instant.parse("2024-01-01T00:00:00Z"));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Test
  void persist() {
    tm().transact(() -> tm().put(new BsaTld("tld", fakeClock.now())));
    BsaTld persisted = tm().transact(() -> tm().loadByKey(BsaTld.createVkey("tld")));
    assertThat(persisted.getTld()).isEqualTo("tld");
    assertThat(persisted.readyTime).isEqualTo(fakeClock.now());
  }

  @Test
  void getActivatedBsaTldNames_noActivatedBsa() {
    assertThat(getActivatedBsaTldNames()).isEmpty();
  }

  @Test
  void getActivatedBsaTldNames_hasActivatedBsa() {
    tm().transact(
            () -> {
              tm().put(new BsaTld("tld1", fakeClock.now()));
              tm().put(new BsaTld("tld2", fakeClock.now()));
            });
    assertThat(getActivatedBsaTldNames()).containsExactly("tld1", "tld2");
  }

  @Test
  void getActivatedBsaTldNames_cached() {
    try {
      BsaTld.setCacheForTest(Optional.of(Duration.ofMinutes(10)));
      tm().transact(() -> tm().put(new BsaTld("tld1", fakeClock.now())));
      assertThat(getActivatedBsaTldNames()).containsExactly("tld1");

      // Add another TLD to the database without invalidating the cache
      tm().transact(() -> tm().put(new BsaTld("tld2", fakeClock.now())));
      // Since it's cached, it should still only contain "tld1"
      assertThat(getActivatedBsaTldNames()).containsExactly("tld1");

      // Reset cache expiry back to 0 (default in unit tests)
      BsaTld.setCacheForTest(Optional.empty());
      // Now it should fetch fresh data from the database
      assertThat(getActivatedBsaTldNames()).containsExactly("tld1", "tld2");
    } finally {
      BsaTld.setCacheForTest(Optional.empty());
    }
  }
}
