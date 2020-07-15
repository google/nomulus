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

package google.registry.schema.tld;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/** Static utility methods for {@link PremiumList}. */
public class PremiumListUtils {

  public static PremiumList parseToPremiumList(String name, String inputData) {
    List<String> inputDataPreProcessed =
        Splitter.on('\n').omitEmptyStrings().splitToList(inputData);

    ImmutableMap<String, PremiumListEntry> prices =
        new PremiumList.Builder().setName(name).build().parse(inputDataPreProcessed);
    ImmutableSet<CurrencyUnit> currencies =
        prices.values().stream()
            .map(e -> e.getValue().getCurrencyUnit())
            .distinct()
            .collect(toImmutableSet());
    checkArgument(
        currencies.size() == 1,
        "The Cloud SQL schema requires exactly one currency, but got: %s",
        ImmutableSortedSet.copyOf(currencies));
    CurrencyUnit currency = Iterables.getOnlyElement(currencies);

    Map<String, BigDecimal> priceAmounts =
        Maps.transformValues(prices, ple -> ple.getValue().getAmount());
    return new PremiumList.Builder()
        .setName(name)
        .setCurrency(currency)
        .setLabelsToPrices(priceAmounts)
        .setCreationTime(DateTime.now(UTC))
        .build();
  }

  private PremiumListUtils() {}
}
