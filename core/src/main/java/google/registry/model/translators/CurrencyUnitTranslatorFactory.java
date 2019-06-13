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

package google.registry.model.translators;

import org.joda.money.CurrencyUnit;

/** Stores {@link CurrencyUnit} as a canonicalized string. */
public class CurrencyUnitTranslatorFactory
    extends AbstractSimpleTranslatorFactory<CurrencyUnit, String> {

  public CurrencyUnitTranslatorFactory() {
    super(CurrencyUnit.class);
  }

  @Override
  SimpleTranslator<CurrencyUnit, String> createTranslator() {
    return new SimpleTranslator<CurrencyUnit, String>(){
      @Override
      public CurrencyUnit loadValue(String datastoreValue) {
        return CurrencyUnit.of(datastoreValue);
      }

      @Override
      public String saveValue(CurrencyUnit pojoValue) {
        return pojoValue.toString();
      }};
  }
}
