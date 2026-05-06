// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.tld.label;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import google.registry.model.tld.label.PremiumList.PremiumEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.joda.money.CurrencyUnit;

/** Static utility methods for {@link PremiumList}. */
public class PremiumListUtils {

  public static PremiumList parseToPremiumList(
      String name, CurrencyUnit currencyUnit, List<String> inputData, Instant creationTime) {
    PremiumList partialPremiumList =
        new PremiumList.Builder()
            .setName(name)
            .setCurrency(currencyUnit)
            .setCreationTimestamp(creationTime)
            .build();
    ImmutableMap<String, PremiumEntry> prices = partialPremiumList.parse(inputData);
    Map<String, BigDecimal> priceAmounts = Maps.transformValues(prices, PremiumEntry::getValue);
    return partialPremiumList.asBuilder().setLabelsToPrices(priceAmounts).build();
  }

  private PremiumListUtils() {}
}
