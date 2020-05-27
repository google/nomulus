// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.converter;

import static google.registry.model.registrar.Registrar.BillingAccountEntry;

import com.google.common.collect.Maps;
import java.util.Map;
import javax.persistence.Converter;
import org.joda.money.CurrencyUnit;

/** JPA converter for storing/retrieving {@code Map<CurrencyUnit, BillingAccountEntry>} objects. */
@Converter(autoApply = true)
public class CurrencyToBillingConverter
    extends StringMapConverterBase<CurrencyUnit, BillingAccountEntry> {

  @Override
  Map.Entry<String, String> convertToDatabaseMapEntry(
      Map.Entry<CurrencyUnit, BillingAccountEntry> entry) {
    return Maps.immutableEntry(entry.getKey().getCode(), entry.getValue().getAccountId());
  }

  @Override
  Map.Entry<CurrencyUnit, BillingAccountEntry> convertToEntityMapEntry(
      Map.Entry<String, String> entry) {
    CurrencyUnit currencyUnit = CurrencyUnit.of(entry.getKey());
    BillingAccountEntry billingAccountEntry =
        new BillingAccountEntry(currencyUnit, entry.getValue());
    return Maps.immutableEntry(currencyUnit, billingAccountEntry);
  }
}
