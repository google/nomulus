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

package google.registry.persistence;

import static java.util.stream.Collectors.toMap;

import google.registry.model.registrar.Registrar.BillingAccountEntry;
import java.util.Map;
import org.hibernate.usertype.UserType;
import org.joda.money.CurrencyUnit;

/**
 * A custom {@link UserType} for storing/retrieving {@link Map<CurrencyUnit, BillingAccountEntry>}
 * objects.
 */
public class CurrencyToBillingMapUserType extends MapUserType {

  @Override
  public Object toEntityTypeMap(Map<String, String> map) {
    return map.entrySet().stream()
        .collect(
            toMap(
                entry -> CurrencyUnit.of(entry.getKey()),
                entry ->
                    new BillingAccountEntry(CurrencyUnit.of(entry.getKey()), entry.getValue())));
  }

  @Override
  public Map<String, String> toDbSupportedMap(Object map) {
    return ((Map<CurrencyUnit, BillingAccountEntry>) map)
        .entrySet().stream()
            .collect(
                toMap(entry -> entry.getKey().getCode(), entry -> entry.getValue().getAccountId()));
  }
}
