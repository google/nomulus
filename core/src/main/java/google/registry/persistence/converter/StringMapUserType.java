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

import com.google.common.collect.ImmutableMap;
import java.util.Map;

/** Hibernate custom type for {@link Map} of String to String values. */
public class StringMapUserType extends MapUserType<Map<String, String>> {

  @SuppressWarnings("unchecked")
  @Override
  public Class<Map<String, String>> returnedClass() {
    return (Class<Map<String, String>>) ((Object) Map.class);
  }

  @Override
  Map<String, String> toStringMap(Map<String, String> map) {
    return ImmutableMap.copyOf(map);
  }

  @Override
  Map<String, String> toEntity(Map<String, String> map) {
    return ImmutableMap.copyOf(map);
  }
}
