// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import google.registry.model.adapters.CurrencyUnitAdapter.UnknownCurrencyException;
import java.io.IOException;
import org.joda.money.CurrencyUnit;

public class CurrencyJsonAdapter extends TypeAdapter<CurrencyUnit> {

  @Override
  public void write(JsonWriter out, CurrencyUnit value) throws IOException {
    String currency = CurrencyUnitAdapter.convertFromCurrency(value);
    out.value(currency);
  }

  @Override
  public CurrencyUnit read(JsonReader in) throws IOException {
    String currency = in.nextString();
    try {
      return CurrencyUnitAdapter.convertFromString(currency);
    } catch (UnknownCurrencyException e) {
      throw new IOException("Unknown currency");
    }
  }
}
