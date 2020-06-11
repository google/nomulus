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
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension;
import java.math.BigDecimal;
import org.joda.time.DateTime;

/**
 * A fee, in currency units specified elsewhere in the xml, with type of the fee an optional fee
 * description.
 */
public class Fee extends BaseFee {

  /** Creates a Fee for the given cost and type with the default description. */
  public static Fee create(
      BigDecimal cost, FeeType type, boolean isPremium, Object... descriptionArgs) {
    checkArgumentNotNull(type, "Must specify the type of the fee");
    return createWithCustomDescription(
        cost, type, isPremium, type.renderDescription(descriptionArgs));
  }

  /** Creates a Fee for the given cost, type, and valid date range with the default description. */
  public static Fee create(
      BigDecimal cost,
      FeeType type,
      boolean isPremium,
      Range<DateTime> validDateRange,
      Object... descriptionArgs) {
    Fee instance = create(cost, type, isPremium, descriptionArgs);
    instance.validDateRange = validDateRange;
    return instance;
  }

  /** Creates a Fee for the given cost and type with a custom description. */
  private static Fee createWithCustomDescription(
      BigDecimal cost, FeeType type, boolean isPremium, String description) {
    Fee instance = new Fee();
    instance.cost = checkNotNull(cost);
    checkArgument(instance.cost.signum() >= 0, "Cost must be a positive number");
    instance.type = checkNotNull(type);
    instance.isPremium = isPremium;
    instance.description = description;
    return instance;
  }

  public static final ImmutableSet<String> FEE_EXTENSION_URIS =
      ImmutableSet.of(
          ServiceExtension.FEE_0_12.getUri(),
          ServiceExtension.FEE_0_11.getUri(),
          ServiceExtension.FEE_0_6.getUri());
}
