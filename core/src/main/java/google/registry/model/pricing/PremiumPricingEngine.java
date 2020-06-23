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

package google.registry.model.pricing;

import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * A plugin interface for premium pricing engines.
 *
 * <p>A premium pricing engine is responsible solely for determining whether a given label is
 * premium or not, and if it is, how much it should cost.
 */
public interface PremiumPricingEngine {

  /**
   * Returns the prices for the given fully qualified domain name at the given time.
   *
   * <p>Note that the fullyQualifiedDomainName must only contain a single part left of the TLD, i.e.
   * subdomains are not allowed, but multi-part TLDs are.
   */
  DomainPrices getDomainPrices(String fullyQualifiedDomainName, DateTime priceTime);

  /**
   * A class containing information on premium prices for a specific domain name.
   *
   * <p>Any implementation of PremiumPricingEngine is responsible for determining all of these.
   */
  class DomainPrices {

    private boolean isPremium;
    // TODO(b/26901539): Refactor return values to support an arbitrary list of costs for each of
    // create, renew, restore, and transfer.
    private Money createCost;
    private Money renewCost;

    static DomainPrices create(boolean isPremium, Money createCost, Money renewCost) {
      DomainPrices instance = new DomainPrices();
      instance.isPremium = isPremium;
      instance.createCost = createCost;
      instance.renewCost = renewCost;
      return instance;
    }

    /** Returns whether the domain is premium. */
    public boolean isPremium() {
      return isPremium;
    }

    /** Returns the cost to create the domain. */
    public Money getCreateCost() {
      return createCost;
    }

    /** Returns the cost to renew the domain. */
    public Money getRenewCost() {
      return renewCost;
    }
  }
}
