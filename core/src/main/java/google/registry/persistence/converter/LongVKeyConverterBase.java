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

import google.registry.persistence.VKey;
import google.registry.util.TypeUtils.TypeInstantiator;
import javax.persistence.AttributeConverter;

/** Base class to convert VKey to a long column. */
public abstract class LongVKeyConverterBase<T>
    implements AttributeConverter<VKey<? extends T>, Long> {

  @Override
  public Long convertToDatabaseColumn(VKey<? extends T> attribute) {
    return attribute == null ? null : (Long) attribute.getSqlKey();
  }

  @Override
  public VKey<? extends T> convertToEntityAttribute(Long dbData) {
    return dbData == null
        ? null
        : VKey.createSql(new TypeInstantiator<T>(getClass()) {}.getExactType(), dbData);
  }
}
