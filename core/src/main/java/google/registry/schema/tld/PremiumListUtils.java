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
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumList.PremiumEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** Static utility methods for {@link PremiumList}. */
public class PremiumListUtils {

  public static PremiumList parseToPremiumList(String name, List<String> inputData) {
    ImmutableMap<String, PremiumEntry> prices =
        new PremiumList.Builder().setName(name).build().parse(inputData);
    checkArgument(inputData.size() > 0, "Input cannot be empty");
    String line = inputData.get(0);
    List<String> parts = Splitter.on(',').trimResults().splitToList(line);
    CurrencyUnit currency = Money.parse(parts.get(1)).getCurrencyUnit();
    Map<String, BigDecimal> priceAmounts = Maps.transformValues(prices, PremiumEntry::getValue);
    // PremiumList premiumList = PremiumListDao.save(new PremiumList.Builder()
    //     .setName(name)
    //     .setCurrency(currency)
    //     .setCreationTime(DateTime.now(UTC))
    //     .build());
    // priceAmounts.entrySet().stream()
    //     .forEach(
    //         entry ->
    //             jpaTm()
    //                 .insert(PremiumEntry
    //                       .create(premiumList.getRevisionId(), entry.getValue(),
    // entry.getKey())));
    return new PremiumList.Builder()
        .setName(name)
        .setCurrency(currency)
        .setLabelsToPrices(priceAmounts)
        .setCreationTime(DateTime.now(UTC))
        .build();
  }

  private PremiumListUtils() {}
}
