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

package google.registry.persistence.converter;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import java.util.Map;
import org.joda.money.CurrencyUnit;

/** Hibernate custom type for {@link Map} of {@link CurrencyUnit} to {@link String}. */
public class CurrencyToStringMapUserType extends MapUserType<Map<CurrencyUnit, String>> {

  @SuppressWarnings("unchecked")
  @Override
  public Class<Map<CurrencyUnit, String>> returnedClass() {
    return (Class<Map<CurrencyUnit, String>>) ((Object) Map.class);
  }

  @Override
  Map<String, String> toStringMap(Map<CurrencyUnit, String> map) {
    return map.entrySet().stream()
        .collect(toImmutableMap(e -> e.getKey().getCode(), e -> e.getValue()));
  }

  @Override
  Map<CurrencyUnit, String> toEntity(Map<String, String> map) {
    return map.entrySet().stream()
        .collect(toImmutableMap(e -> CurrencyUnit.of(e.getKey()), e -> e.getValue()));
  }
}
