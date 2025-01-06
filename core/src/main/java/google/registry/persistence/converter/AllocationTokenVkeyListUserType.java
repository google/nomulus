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

import static com.google.common.collect.ImmutableList.toImmutableList;

import google.registry.model.domain.token.AllocationToken;
import google.registry.persistence.VKey;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Hibernate custom type for {@link List} of {@link VKey} of {@link AllocationToken}. */
public class AllocationTokenVkeyListUserType
    extends StringCollectionUserType<VKey<AllocationToken>, List<VKey<AllocationToken>>> {

  @Override
  String[] toJdbcObject(List<VKey<AllocationToken>> collection) {
    return collection.stream()
        .map(VKey::getKey)
        .map(Object::toString)
        .toList()
        .toArray(new String[0]);
  }

  @Nullable
  @Override
  List<VKey<AllocationToken>> toEntity(@Nullable String[] data) {
    return data == null
        ? null
        : Stream.of(data)
            .map(value -> VKey.create(AllocationToken.class, value))
            .collect(toImmutableList());
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<List<VKey<AllocationToken>>> returnedClass() {
    return (Class<List<VKey<AllocationToken>>>) ((Object) List.class);
  }
}
