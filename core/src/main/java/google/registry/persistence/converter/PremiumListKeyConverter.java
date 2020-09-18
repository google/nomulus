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

import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;

import com.googlecode.objectify.Key;
import google.registry.model.registry.label.PremiumList;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/** JPA converter for a {@link Key} containing a {@link PremiumList} */
@Converter(autoApply = true)
public class PremiumListKeyConverter implements AttributeConverter<Key<PremiumList>, String> {

  @Override
  public String convertToDatabaseColumn(Key<PremiumList> attribute) {
    return (attribute == null) ? null : attribute.getName();
  }

  @Override
  public Key<PremiumList> convertToEntityAttribute(String dbData) {
    return (dbData == null) ? null : Key.create(getCrossTldKey(), PremiumList.class, dbData);
  }
}
