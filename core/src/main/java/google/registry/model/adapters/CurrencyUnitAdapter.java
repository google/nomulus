// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.adapters;

import static com.google.common.base.Strings.nullToEmpty;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import org.joda.money.CurrencyUnit;

/** Adapter to use Joda {@link CurrencyUnit} when marshalling strings. */
public class CurrencyUnitAdapter extends XmlAdapter<String, CurrencyUnit> {

  public static CurrencyUnit convertFromString(String currency) throws UnknownCurrencyException {
    try {
      return CurrencyUnit.of(nullToEmpty(currency).trim());
    } catch (IllegalArgumentException e) {
      throw new UnknownCurrencyException();
    }
  }

  public static String convertFromCurrency(CurrencyUnit currency) {
    return currency == null ? null : currency.toString();
  }

  /** Parses a string into a {@link CurrencyUnit} object. */
  @Override
  public CurrencyUnit unmarshal(String currency) throws UnknownCurrencyException {
    return convertFromString(currency);
  }

  /** Converts {@link CurrencyUnit} to a string. */
  @Override
  public String marshal(CurrencyUnit currency) {
    return convertFromCurrency(currency);
  }

  /** Exception to throw when failing to parse a currency. */
  public static class UnknownCurrencyException extends Exception {}
}
