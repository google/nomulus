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

import com.googlecode.objectify.Key;
import google.registry.persistence.VKey;
import java.io.Serializable;
import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;

/** Converts VKey to a string column. */
public abstract class VKeyConverter<T, C extends Serializable>
    implements AttributeConverter<VKey<? extends T>, C> {
  @Override
  @Nullable
  public C convertToDatabaseColumn(@Nullable VKey<? extends T> attribute) {
    return attribute == null ? null : (C) attribute.getSqlKey();
  }

  @Override
  @Nullable
  public VKey<? extends T> convertToEntityAttribute(@Nullable C dbData) {
    if (dbData == null) {
      return null;
    }
    Class<? extends T> clazz = getAttributeClass();
    Key ofyKey = null;
    if (!hasCompositeOfyKey()) {
      // If this isn't a composite key, we can create the Ofy key from the SQL key.
      ofyKey =
          dbData instanceof String
              ? Key.create(clazz, (String) dbData)
              : Key.create(clazz, (Long) dbData);
      return VKey.create(clazz, dbData, ofyKey);
    } else {
      // We don't know how to create the Ofy key and probably don't have everything necessary to do
      // it anyway, so just create an asymmetric key - the containing object will have to convert it
      // into a symmetric key.
      return VKey.createSql(clazz, dbData);
    }
  }

  protected boolean hasCompositeOfyKey() {
    return false;
  }

  /** Returns the class of the attribute. */
  protected abstract Class<T> getAttributeClass();
}
