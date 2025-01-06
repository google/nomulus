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

package google.registry.rde;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.Cursor.CursorType.RDE_STAGING;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.util.SafeSerializationUtils.safeDeserialize;
import static google.registry.util.SerializeUtils.deserialize;
import static google.registry.util.SerializeUtils.serialize;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PendingDeposit}. */
public class PendingDepositTest {
  private final DateTime now = DateTime.parse("2000-01-01TZ");

  PendingDeposit pendingDeposit =
      PendingDeposit.create("soy", now, FULL, RDE_STAGING, Duration.standardDays(1));

  PendingDeposit manualPendingDeposit =
      PendingDeposit.createInManualOperation("soy", now, FULL, "/", null);

  @Test
  void deserialize_normalDeposit_success() {
    assertThat(deserialize(PendingDeposit.class, serialize(pendingDeposit)))
        .isEqualTo(pendingDeposit);
  }

  @Test
  void deserialize_manualDeposit_success() {
    assertThat(deserialize(PendingDeposit.class, serialize(manualPendingDeposit)))
        .isEqualTo(manualPendingDeposit);
  }

  @Test
  void safeDeserialize_normalDeposit_success() {
    assertThat(safeDeserialize(serialize(pendingDeposit))).isEqualTo(pendingDeposit);
  }

  @Test
  void safeDeserialize_manualDeposit_success() {
    assertThat(safeDeserialize(serialize(manualPendingDeposit))).isEqualTo(manualPendingDeposit);
  }
}
