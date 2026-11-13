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

import com.google.common.collect.ImmutableList;
import google.registry.model.domain.fee.BaseFee.AppliedType;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.feestdv1.FeeCheckResponseExtensionItemStdV1;
import java.math.BigDecimal;
import java.time.Period;
import org.junit.jupiter.api.Test;

class FeeTest {

  @Test
  void testCreate_success() {
    Fee fee = Fee.create(BigDecimal.valueOf(10.00), FeeType.CREATE, false);
    assertThat(fee.getCost()).isEqualTo(BigDecimal.valueOf(10.00));
    assertThat(fee.getType()).isEqualTo(FeeType.CREATE);
    assertThat(fee.getRefundable()).isTrue();
    assertThat(fee.getType().isRefundable()).isTrue();
    assertThat(fee.getType().isRegularFee()).isTrue();
    assertThat(fee.hasDefaultAttributes()).isTrue();
    for (FeeType regularType :
        ImmutableList.of(
            FeeType.CREATE,
            FeeType.RENEW,
            FeeType.RESTORE,
            FeeType.TRANSFER,
            FeeType.UPDATE,
            FeeType.CREDIT)) {
      assertThat(regularType.isRefundable()).isTrue();
      assertThat(regularType.isRegularFee()).isTrue();
    }
  }

  @Test
  void testCreate_eapAndXap_nonRefundableAndNonRegular() {
    Fee eapFee = Fee.create(BigDecimal.valueOf(100.00), FeeType.EAP, false, "2026-01-01T00:00:00Z");
    assertThat(eapFee.getRefundable()).isFalse();
    assertThat(eapFee.getType().isRefundable()).isFalse();
    assertThat(eapFee.getType().isRegularFee()).isFalse();
    assertThat(eapFee.hasDefaultAttributes()).isTrue();
    Fee xapFee = Fee.create(BigDecimal.valueOf(100.00), FeeType.XAP, false, "2026-01-01T00:00:00Z");
    assertThat(xapFee.getRefundable()).isFalse();
    assertThat(xapFee.getType().isRefundable()).isFalse();
    assertThat(xapFee.getType().isRegularFee()).isFalse();
    assertThat(xapFee.hasDefaultAttributes()).isTrue();
  }

  @Test
  void testHasDefaultAttributes_parsedFromDescription() {
    assertThat(
            new Fee.Builder()
                .setCost(BigDecimal.valueOf(100.00))
                .setDescription("Expiry Access Period, fee expires: 2026-01-01T00:00:00Z")
                .setRefundable(false)
                .build()
                .hasDefaultAttributes())
        .isTrue();
    assertThat(
            new Fee.Builder()
                .setCost(BigDecimal.valueOf(100.00))
                .setDescription("Early Access Period")
                .build()
                .hasDefaultAttributes())
        .isTrue();
    assertThat(
            new Fee.Builder()
                .setCost(BigDecimal.valueOf(100.00))
                .setDescription("Early Access Period")
                .setRefundable(true)
                .build()
                .hasDefaultAttributes())
        .isFalse();
    assertThat(
            new Fee.Builder()
                .setCost(BigDecimal.valueOf(24.00))
                .setDescription("create")
                .setRefundable(true)
                .build()
                .hasDefaultAttributes())
        .isTrue();
    assertThat(
            new Fee.Builder()
                .setCost(BigDecimal.valueOf(24.00))
                .setDescription("create")
                .setRefundable(false)
                .build()
                .hasDefaultAttributes())
        .isFalse();
    assertThat(
            new Fee.Builder()
                .setCost(BigDecimal.valueOf(24.00))
                .setDescription("unknown")
                .setRefundable(false)
                .build()
                .hasDefaultAttributes())
        .isFalse();
    assertThat(
            new Fee.Builder()
                .setCost(BigDecimal.valueOf(24.00))
                .setDescription("create Early Access Period")
                .setRefundable(false)
                .build()
                .hasDefaultAttributes())
        .isFalse();
    assertThat(
            new Fee.Builder()
                .setCost(BigDecimal.valueOf(24.00))
                .setDescription("create")
                .setGracePeriod(Period.ofDays(5))
                .build()
                .hasDefaultAttributes())
        .isFalse();
    assertThat(
            new Fee.Builder()
                .setCost(BigDecimal.valueOf(24.00))
                .setDescription("create")
                .setApplied(AppliedType.DELAYED)
                .build()
                .hasDefaultAttributes())
        .isFalse();
  }

  @Test
  void testFeeCheckResponseExtensionItemStdV1_setFees_updatesAndResetsStandard() {
    Fee regularFee = Fee.create(BigDecimal.valueOf(10.00), FeeType.CREATE, false);
    Fee eapFee = Fee.create(BigDecimal.valueOf(100.00), FeeType.EAP, false, "2026-01-01T00:00:00Z");
    Fee untypedFee = new Fee.Builder().setCost(BigDecimal.valueOf(10.00)).build();

    FeeCheckResponseExtensionItemStdV1 regularItem =
        new FeeCheckResponseExtensionItemStdV1.Builder()
            .setFees(ImmutableList.of(regularFee, untypedFee))
            .build();
    FeeCheckResponseExtensionItemStdV1 nonStandardItem =
        new FeeCheckResponseExtensionItemStdV1.Builder()
            .setFees(ImmutableList.of(regularFee, eapFee))
            .build();
    assertThat(nonStandardItem).isNotEqualTo(regularItem);

    FeeCheckResponseExtensionItemStdV1 resetToRegularItem =
        new FeeCheckResponseExtensionItemStdV1.Builder()
            .setFees(ImmutableList.of(regularFee, eapFee))
            .setFees(ImmutableList.of(regularFee, untypedFee))
            .build();
    assertThat(resetToRegularItem).isEqualTo(regularItem);
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
