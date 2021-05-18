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

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;

import com.google.common.net.InternetDomainName;
import google.registry.model.registry.Registry;
import google.registry.schema.tld.PremiumListDao;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** A premium list pricing engine that stores static pricing information in Datastore entities. */
public final class StaticPremiumListPricingEngine implements PremiumPricingEngine {

  /** The name of the pricing engine, as used in {@code Registry.pricingEngineClassName}. */
  public static final String NAME = "google.registry.model.pricing.StaticPremiumListPricingEngine";

  @Inject StaticPremiumListPricingEngine() {}

  @Override
  public DomainPrices getDomainPrices(String fullyQualifiedDomainName, DateTime priceTime) {
    String tld = getTldFromDomainName(fullyQualifiedDomainName);
    String label = InternetDomainName.from(fullyQualifiedDomainName).parts().get(0);
    Registry registry = Registry.get(checkNotNull(tld, "tld"));
    Optional<Money> premiumPrice = Optional.empty();
    if (registry.getPremiumList().isPresent()) {
      premiumPrice =
          PremiumListDao.getPremiumPrice(registry.getPremiumList().get().getName(), label);
    }
    return DomainPrices.create(
        premiumPrice.isPresent(),
        premiumPrice.orElse(registry.getStandardCreateCost()),
        premiumPrice.orElse(registry.getStandardRenewCost(priceTime)));
  }
}
