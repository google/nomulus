// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import google.registry.model.Buildable;
import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Period;

/**
 * A fee, in currency units specified elsewhere in the XML, with a type and an optional description.
 */
public class Fee extends BaseFee {

  public static final ImmutableSet<String> FEE_EXTENSION_URIS =
      ImmutableSet.of(
          ServiceExtension.FEE_1_00.getUri(),
          ServiceExtension.FEE_0_12.getUri(),
          ServiceExtension.FEE_0_11.getUri(),
          ServiceExtension.FEE_0_6.getUri());

  @Override
  public Fee clone() {
    return (Fee) super.clone();
  }

  /** Creates a Fee for the given cost and type with the default description. */
  public static Fee create(
      BigDecimal cost, FeeType type, boolean isPremium, Object... descriptionArgs) {
    checkArgumentNotNull(type, "Must specify the type of the fee");
    checkArgumentNotNull(cost, "Cost cannot be null");
    checkArgument(cost.signum() >= 0, "Cost must be a non-negative number");
    Fee instance = new Fee();
    instance.cost = cost;
    instance.type = type;
    instance.isPremium = isPremium;
    instance.description = type.renderDescription(descriptionArgs);
    return instance;
  }

  /** Creates a Fee for the given cost, type, and valid date range with the default description. */
  public static Fee create(
      BigDecimal cost,
      FeeType type,
      boolean isPremium,
      Range<Instant> validDateRange,
      Object... descriptionArgs) {
    Fee instance = create(cost, type, isPremium, descriptionArgs);
    instance.validDateRange = validDateRange;
    return instance;
  }

  /** Builder for {@link Fee}. */
  public static class Builder extends Buildable.Builder<Fee> {

    /** Sets the cost of the fee. */
    public Builder setCost(BigDecimal cost) {
      getInstance().cost = cost;
      return this;
    }

    /** Sets the description of the fee. */
    public Builder setDescription(String description) {
      getInstance().description = description;
      return this;
    }

    /** Sets whether the fee is refundable. */
    public Builder setRefundable(Boolean refundable) {
      getInstance().refundable = refundable;
      return this;
    }

    /** Sets the grace period of the fee. */
    public Builder setGracePeriod(Period gracePeriod) {
      getInstance().gracePeriod = gracePeriod;
      return this;
    }

    /** Sets when the fee is applied. */
    public Builder setApplied(AppliedType applied) {
      getInstance().applied = applied;
      return this;
    }
  }
}
