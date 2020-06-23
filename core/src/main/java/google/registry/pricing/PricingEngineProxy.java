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

package google.registry.pricing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;

import google.registry.model.pricing.PremiumPricingEngine;
import google.registry.model.pricing.PremiumPricingEngine.DomainPrices;
import google.registry.model.registry.Registry;
import java.util.Map;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * A global proxy providing static methods for getting premium prices that dispatches requests
 * correctly to the relevant {@link PremiumPricingEngine} implementation per TLD.
 */
public final class PricingEngineProxy {

  private static final Map<String, PremiumPricingEngine>
      premiumPricingEngines = DaggerPricingComponent.create().premiumPricingEngines();

  /** Returns the billing cost for registering the specified domain name for this many years. */
  public static Money getDomainCreateCost(String domainName, DateTime priceTime, int years) {
    checkArgument(years > 0, "Number of years must be positive");
    return getPricesForDomainName(domainName, priceTime).getCreateCost().multipliedBy(years);
  }

  /** Returns the billing cost for renewing the specified domain name for this many years. */
  public static Money getDomainRenewCost(String domainName, DateTime priceTime, int years) {
    checkArgument(years > 0, "Number of years must be positive");
    return getPricesForDomainName(domainName, priceTime).getRenewCost().multipliedBy(years);
  }

  /** Returns true if the specified domain name is premium. */
  public static boolean isDomainPremium(String domainName, DateTime priceTime) {
    return getPricesForDomainName(domainName, priceTime).isPremium();
  }

  /**
   * Returns the full {@link DomainPrices} details for the given domain name by dispatching to the
   * appropriate {@link PremiumPricingEngine} based on what is configured for the TLD that the
   * domain is under.
   */
  public static DomainPrices getPricesForDomainName(String domainName, DateTime priceTime) {
    String tld = getTldFromDomainName(domainName);
    String clazz = Registry.get(tld).getPremiumPricingEngineClassName();
    PremiumPricingEngine engine = premiumPricingEngines.get(clazz);
    checkState(engine != null, "Could not load pricing engine %s for TLD %s", clazz, tld);
    return engine.getDomainPrices(domainName, priceTime);
  }

  private PricingEngineProxy() {}
}
