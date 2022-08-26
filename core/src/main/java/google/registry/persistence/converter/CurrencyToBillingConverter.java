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

import java.util.Map;
import javax.persistence.Converter;
import org.joda.money.CurrencyUnit;

/** JPA converter for storing/retrieving {@code Map<CurrencyUnit, String>} objects. */
@Converter(autoApply = true)
public class CurrencyToBillingConverter
    extends StringMapConverterBase<CurrencyUnit, String, Map<CurrencyUnit, String>> {

  @Override
  protected String convertKeyToString(CurrencyUnit key) {
    return key.getCode();
  }

  @Override
  protected String convertValueToString(String value) {
    return value;
  }

  @Override
  protected CurrencyUnit convertStringToKey(String string) {
    return CurrencyUnit.of(string);
  }

  @Override
  protected String convertStringToValue(String string) {
    return string;
  }

  @Override
  protected Map<CurrencyUnit, String> convertMapToDerivedType(Map<CurrencyUnit, String> map) {
    return map;
  }
}
