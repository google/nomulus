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

package google.registry.model.domain.fee;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.domain.fee.BaseFee.FeeType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FeeTest {

  @Test
  void testCreate_success() {
    Fee fee = Fee.create(BigDecimal.valueOf(10.00), FeeType.CREATE, false);
    assertThat(fee.getCost()).isEqualTo(BigDecimal.valueOf(10.00));
    assertThat(fee.getType()).isEqualTo(FeeType.CREATE);
    assertThat(fee.getRefundable()).isTrue();
    assertThat(fee.getType().isRefundable()).isTrue();
    assertThat(fee.getType().isStandard()).isTrue();
  }

  @Test
  void testCreate_eapAndXap_nonRefundableAndNonStandard() {
    Fee eapFee = Fee.create(BigDecimal.valueOf(100.00), FeeType.EAP, false, "2026-01-01T00:00:00Z");
    assertThat(eapFee.getRefundable()).isFalse();
    assertThat(eapFee.getType().isRefundable()).isFalse();
    assertThat(eapFee.getType().isStandard()).isFalse();
    Fee xapFee = Fee.create(BigDecimal.valueOf(100.00), FeeType.XAP, false, "2026-01-01T00:00:00Z");
    assertThat(xapFee.getRefundable()).isFalse();
    assertThat(xapFee.getType().isRefundable()).isFalse();
    assertThat(xapFee.getType().isStandard()).isFalse();
  }

  @Test
  void testCreate_nullCost() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Fee.create(null, FeeType.CREATE, false));
    assertThat(thrown).hasMessageThat().contains("Cost cannot be null");
  }

  @Test
  void testCreate_negativeCost() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> Fee.create(BigDecimal.valueOf(-5.00), FeeType.CREATE, false));
    assertThat(thrown).hasMessageThat().contains("Cost must be a non-negative number");
  }

  @Test
  void testCreate_nullType() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> Fee.create(BigDecimal.valueOf(10.00), null, false));
    assertThat(thrown).hasMessageThat().contains("Must specify the type of the fee");
  }
}
