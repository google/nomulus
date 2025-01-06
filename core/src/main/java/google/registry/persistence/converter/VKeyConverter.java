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

import google.registry.persistence.VKey;
import jakarta.persistence.AttributeConverter;
import java.io.Serializable;
import javax.annotation.Nullable;

/**
 * Converts {@link VKey} to/from a type that can be directly stored in the database.
 *
 * <p>Typically the converted type is {@link String} or {@link Long}.
 */
public abstract class VKeyConverter<T, C extends Serializable>
    implements AttributeConverter<VKey<? extends T>, C> {

  @Override
  @Nullable
  public C convertToDatabaseColumn(@Nullable VKey<? extends T> attribute) {
    if (attribute == null) {
      return null;
    }
    try {
      return getKeyClass().cast(attribute.getKey());
    } catch (ClassCastException e) {
      throw new RuntimeException(
          String.format(
              "Cannot cast SQL key %s of type %s to type %s",
              attribute.getKey(), attribute.getKey().getClass(), getKeyClass()),
          e);
    }
  }

  @Override
  @Nullable
  public VKey<? extends T> convertToEntityAttribute(@Nullable C dbData) {
    if (dbData == null) {
      return null;
    }
    return VKey.create(getEntityClass(), dbData);
  }

  /** Returns the class of the entity that the VKey represents. */
  protected abstract Class<T> getEntityClass();

  /** Returns the class of the key that the VKey holds. */
  protected abstract Class<C> getKeyClass();
}
