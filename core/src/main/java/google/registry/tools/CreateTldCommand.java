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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.tld.Registries.getTlds;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldState;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** Command to create a TLD. */
@Parameters(separators = " =", commandDescription = "Create new TLD(s)")
class CreateTldCommand extends CreateOrUpdateTldCommand {

  @Nullable
  @Parameter(
      names = "--initial_tld_state",
      description = "Initial state of the TLD (cannot be combined with a transitions list)")
  TldState initialTldState;

  @Nullable
  @Parameter(
      names = "--initial_renew_billing_cost",
      description = "Initial per-year billing cost for renewing a domain "
          + "(cannot be combined with a transitions list)")
  private Money initialRenewBillingCost;

  @Override
  protected void initTldCommand() {
    checkArgument(initialTldState == null || tldStateTransitions.isEmpty(),
        "Don't pass both --initial_tld_state and --tld_state_transitions");
    checkArgument(initialRenewBillingCost == null || renewBillingCostTransitions.isEmpty(),
        "Don't pass both --initial_renew_billing_cost and --renew_billing_cost_transitions");
    if (initialRenewBillingCost != null) {
      renewBillingCostTransitions = ImmutableSortedMap.of(START_OF_TIME, initialRenewBillingCost);
    }
    checkArgument(mainParameters.size() == 1, "Can't create more than one TLD at a time");
    checkArgument(
        !Strings.isNullOrEmpty(roidSuffix),
        "The roid suffix is required when creating a TLD");
  }

  @Override
  void setCommandSpecificProperties(Registry.Builder builder) {
    // Pick up the currency from the create cost. Since all costs must be in one currency, and that
    // condition is enforced by the builder, it doesn't matter which cost we choose it from.
    CurrencyUnit currency = createBillingCost != null
        ? createBillingCost.getCurrencyUnit()
        : Registry.DEFAULT_CURRENCY;

    builder.setCurrency(currency);

    // If this is a non-default currency and the user hasn't specified an EAP fee schedule, set the
    // EAP fee schedule to a matching currency.
    if (!currency.equals(Registry.DEFAULT_CURRENCY) && eapFeeSchedule.isEmpty()) {
      builder.setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(currency)));
    }
  }

  @Override
  Registry getOldRegistry(String tld) {
    checkState(!getTlds().contains(tld), "TLD '%s' already exists", tld);
    return null;
  }

  @Override
  ImmutableSet<String> getAllowedRegistrants(Registry oldRegistry) {
    return ImmutableSet.copyOf(nullToEmpty(allowedRegistrants));
  }

  @Override
  ImmutableSet<String> getAllowedNameservers(Registry oldRegistry) {
    return ImmutableSet.copyOf(nullToEmpty(allowedNameservers));
  }

  @Override
  ImmutableSet<String> getReservedLists(Registry oldRegistry) {
    return ImmutableSet.copyOf(nullToEmpty(reservedListNames));
  }

  @Override
  Optional<Map.Entry<DateTime, TldState>> getTldStateTransitionToAdd() {
    return initialTldState != null
        ? Optional.of(Maps.immutableEntry(START_OF_TIME, initialTldState))
        : Optional.empty();
  }
}
