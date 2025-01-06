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

package google.registry.bsa.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link BsaLabel}. */
public class BsaLabelTest {

  FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Test
  void persist() {
    tm().transact(() -> tm().put(new BsaLabel("label", fakeClock.nowUtc())));
    BsaLabel persisted = tm().transact(() -> tm().loadByKey(BsaLabel.vKey("label")));
    assertThat(persisted.getLabel()).isEqualTo("label");
    assertThat(persisted.creationTime).isEqualTo(fakeClock.nowUtc());
  }

  @Test
  void isLabelBlocked_no() {
    assertThat(tm().transact(() -> BsaLabelUtils.isLabelBlocked("abc"))).isFalse();
  }

  @Test
  void isLabelBlocked_yes() {
    tm().transact(() -> tm().put(new BsaLabel("abc", fakeClock.nowUtc())));
    assertThat(tm().transact(() -> BsaLabelUtils.isLabelBlocked("abc"))).isTrue();
  }
}
