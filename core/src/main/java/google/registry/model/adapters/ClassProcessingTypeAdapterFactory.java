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

package google.registry.model.adapters;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;

/**
 * Adapter factory that allows for (de)serialization of Class objects in GSON.
 *
 * <p>GSON's built-in adapter for Class objects throws an exception, but there are situations where
 * we want to (de)serialize these, such as in VKeys. This instructs GSON to look for our custom
 * {@link ClassTypeAdapter} rather than the default.
 */
public class ClassProcessingTypeAdapterFactory implements TypeAdapterFactory {

  @Override
  @SuppressWarnings("unchecked")
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
    if (Class.class.isAssignableFrom(typeToken.getRawType())) {
      // in this case, T is a class object
      return (TypeAdapter<T>) new ClassTypeAdapter();
    }
    return null;
  }
}
