// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.fee06.FeeCheckCommandExtensionV06;
import google.registry.model.domain.fee06.FeeCreateCommandExtensionV06;
import google.registry.model.domain.fee06.FeeRenewCommandExtensionV06;
import google.registry.model.domain.fee06.FeeTransferCommandExtensionV06;
import google.registry.model.domain.fee06.FeeUpdateCommandExtensionV06;
import google.registry.model.domain.fee11.FeeCheckCommandExtensionV11;
import google.registry.model.domain.fee11.FeeCreateCommandExtensionV11;
import google.registry.model.domain.fee11.FeeRenewCommandExtensionV11;
import google.registry.model.domain.fee11.FeeTransferCommandExtensionV11;
import google.registry.model.domain.fee11.FeeUpdateCommandExtensionV11;
import google.registry.model.domain.fee12.FeeCheckCommandExtensionV12;
import google.registry.model.domain.fee12.FeeCreateCommandExtensionV12;
import google.registry.model.domain.fee12.FeeRenewCommandExtensionV12;
import google.registry.model.domain.fee12.FeeTransferCommandExtensionV12;
import google.registry.model.domain.fee12.FeeUpdateCommandExtensionV12;
import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension;
import java.math.BigDecimal;

/** A fee, in currency units specified elsewhere in the xml, and with an optional description. */
public class Fee extends BaseFee {
  public static Fee create(BigDecimal cost, String description) {
    Fee instance = new Fee();
    instance.cost = checkNotNull(cost);
    checkArgument(instance.cost.signum() >= 0);
    instance.description = description;
    return instance;
  }

  public static final ImmutableList<
          Class<? extends FeeCheckCommandExtension<
              ? extends FeeCheckCommandExtensionItem, ? extends FeeCheckResponseExtension<?>>>>
      FEE_CHECK_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER =
          ImmutableList.<
                  Class<? extends FeeCheckCommandExtension<
                      ? extends FeeCheckCommandExtensionItem,
                      ? extends FeeCheckResponseExtension<?>>>>of(
              FeeCheckCommandExtensionV12.class,
              FeeCheckCommandExtensionV11.class,
              FeeCheckCommandExtensionV06.class);

  public static final ImmutableList<Class<? extends FeeTransformCommandExtension>>
      FEE_CREATE_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER =
          ImmutableList.<Class<? extends FeeTransformCommandExtension>>of(
              FeeCreateCommandExtensionV12.class,
              FeeCreateCommandExtensionV11.class,
              FeeCreateCommandExtensionV06.class);

  public static final ImmutableList<Class<? extends FeeTransformCommandExtension>>
      FEE_RENEW_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER =
          ImmutableList.<Class<? extends FeeTransformCommandExtension>>of(
              FeeRenewCommandExtensionV12.class,
              FeeRenewCommandExtensionV11.class,
              FeeRenewCommandExtensionV06.class);

  public static final ImmutableList<Class<? extends FeeTransformCommandExtension>>
      FEE_TRANSFER_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER =
          ImmutableList.<Class<? extends FeeTransformCommandExtension>>of(
              FeeTransferCommandExtensionV12.class,
              FeeTransferCommandExtensionV11.class,
              FeeTransferCommandExtensionV06.class);

  public static final ImmutableList<Class<? extends FeeTransformCommandExtension>>
      FEE_UPDATE_COMMAND_EXTENSIONS_IN_PREFERENCE_ORDER =
          ImmutableList.<Class<? extends FeeTransformCommandExtension>>of(
              FeeUpdateCommandExtensionV12.class,
              FeeUpdateCommandExtensionV11.class,
              FeeUpdateCommandExtensionV06.class);

  public static final ImmutableSet<String>
      FEE_EXTENSION_URIS =
          ImmutableSet.<String>of(
              ServiceExtension.FEE_0_12.getUri(),
              ServiceExtension.FEE_0_11.getUri(),
              ServiceExtension.FEE_0_6.getUri());
}
