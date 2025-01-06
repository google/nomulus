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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkArgument;

import java.lang.reflect.Field;

/** Utility class for extracting encapsulated contents of objects for testing. */
public final class ReflectiveFieldExtractor {

  /** Extracts private {@code fieldName} on {@code object} without public getter. */
  public static <T> T extractField(Class<T> returnType, Object object, String fieldName)
      throws NoSuchFieldException, SecurityException, IllegalAccessException {
    Field field = object.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    Object value = field.get(object);
    checkArgument(value == null || returnType.isInstance(value));
    @SuppressWarnings("unchecked")
    T result = (T) value;
    return result;
  }

  private ReflectiveFieldExtractor() {}
}
