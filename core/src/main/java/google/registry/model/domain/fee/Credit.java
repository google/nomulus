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

package google.registry.model.domain.fee;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import java.math.BigDecimal;

/** A credit, in currency units specified elsewhere in the xml, and with an optional description. */
public class Credit extends BaseFee {

  /** Creates a Credit for the given amount and type with the default description. */
  public static Credit create(BigDecimal cost, FeeType type, Object... descriptionArgs) {
    checkArgumentNotNull(type, "Must specify the type of the credit");
    return createWithCustomDescription(cost, type, type.renderDescription(descriptionArgs));
  }

  /** Creates a Credit for the given amount and type with a custom description. */
  public static Credit createWithCustomDescription(
      BigDecimal cost, FeeType type, String description) {
    Credit instance = new Credit();
    instance.cost = checkNotNull(cost);
    checkArgument(instance.cost.signum() <= 0, "A credit cannot have a positive cost");
    instance.type = checkNotNull(type);
    instance.description = description;
    return instance;
  }
}
