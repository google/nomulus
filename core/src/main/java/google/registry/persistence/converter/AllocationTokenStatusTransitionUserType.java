// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import java.util.Map;
import org.joda.time.DateTime;

/** Hibernate custom type for {@link TimedTransitionProperty} of {@link TokenStatus}. */
public class AllocationTokenStatusTransitionUserType
    extends MapUserType<TimedTransitionProperty<TokenStatus>> {

  @SuppressWarnings("unchecked")
  @Override
  public Class<TimedTransitionProperty<TokenStatus>> returnedClass() {
    return (Class<TimedTransitionProperty<TokenStatus>>) ((Object) TimedTransitionProperty.class);
  }

  @Override
  Map<String, String> toStringMap(TimedTransitionProperty<TokenStatus> map) {
    return map.toValueMap().entrySet().stream()
        .collect(toImmutableMap(e -> e.getKey().toString(), e -> e.getValue().name()));
  }

  @Override
  TimedTransitionProperty<TokenStatus> toEntity(Map<String, String> map) {
    ImmutableSortedMap<DateTime, TokenStatus> valueMap =
        map.entrySet().stream()
            .collect(
                toImmutableSortedMap(
                    Ordering.natural(),
                    e -> DateTime.parse(e.getKey()),
                    e -> TokenStatus.valueOf(e.getValue())));
    return TimedTransitionProperty.fromValueMap(valueMap);
  }
}
