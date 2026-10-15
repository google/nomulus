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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import google.registry.model.tld.label.PremiumList.PremiumEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;

/** Static utility methods for {@link PremiumList}. */
public class PremiumListUtils {

  /**
   * Parses the given CSV input lines into a {@link PremiumList}.
   *
   * <p>The {@code currencyUnit} is explicitly passed in when creating a list via {@code
   * create_premium_list} (which requires the {@code --currency} flag) or when updating an existing
   * list in the database (to ensure the updated entries match the existing list's persisted
   * currency, and to support bare-number price entries that omit the currency code).
   *
   * <p>When {@code currencyUnit} is {@code null} (such as when creating a new list via {@code
   * update_premium_list --upsert}, which does not take a {@code --currency} flag and has no
   * existing database revision to consult), {@link PremiumList#createFromLine} infers the list's
   * currency from the first input line that specifies a currency code and validates that all
   * subsequent lines match it. If {@code currencyUnit} is {@code null} and no input line specifies
   * a currency code, an {@link IllegalArgumentException} is thrown.
   */
  public static PremiumList parseToPremiumList(
      String name,
      @Nullable CurrencyUnit currencyUnit,
      List<String> inputData,
      Instant creationTime) {
    PremiumList partialPremiumList =
        new PremiumList.Builder()
            .setName(name)
            .setCurrency(currencyUnit)
            .setCreationTimestamp(creationTime)
            .build();
    ImmutableMap<String, PremiumEntry> prices = partialPremiumList.parse(inputData);
    checkArgument(
        partialPremiumList.getCurrency() != null,
        "Could not determine currency for premium list from input file");
    Map<String, BigDecimal> priceAmounts = Maps.transformValues(prices, PremiumEntry::getValue);
    return partialPremiumList.asBuilder().setLabelsToPrices(priceAmounts).build();
  }

  private PremiumListUtils() {}
}
