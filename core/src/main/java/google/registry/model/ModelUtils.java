// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model;

import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.base.Predicates.isNull;
import static com.google.common.base.Predicates.or;
import static com.google.common.collect.Maps.transformValues;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Streams;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** A collection of static methods that deal with reflection on model classes. */
public class ModelUtils {

  /** Caches all instance fields on an object, including non-public and inherited fields. */
  private static final LoadingCache<Class<?>, ImmutableMap<String, Field>> ALL_FIELDS_CACHE =
      CacheUtils.newCacheBuilder()
          .build(
              clazz -> {
                Deque<Class<?>> hierarchy = new ArrayDeque<>();
                // Walk the hierarchy up to but not including ImmutableObject (to ignore
                // hashCode).
                for (; clazz != ImmutableObject.class; clazz = clazz.getSuperclass()) {
                  // Add to the front, so that shadowed fields show up later in the list.
                  // This will mean that getFieldValues will show the most derived value.
                  hierarchy.addFirst(clazz);
                }
                Map<String, Field> fields = new LinkedHashMap<>();
                for (Class<?> hierarchyClass : hierarchy) {
                  // Don't use hierarchyClass.getFields() because it only picks up public fields.
                  for (Field field : hierarchyClass.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                      field.setAccessible(true);
                      fields.put(field.getName(), field);
                    }
                  }
                }
                return ImmutableMap.copyOf(fields);
              });

  /** Lists all instance fields on an object, including non-public and inherited fields. */
  public static Map<String, Field> getAllFields(Class<?> clazz) {
    return ALL_FIELDS_CACHE.get(clazz);
  }

  /**
   * Retrieves a field value via reflection, using the field's {@link GetterDelegate} if present.
   */
  static Object getFieldValue(Object instance, Field field) {
    try {
      if (field.isAnnotationPresent(GetterDelegate.class)) {
        return instance
            .getClass()
            .getMethod(field.getAnnotation(GetterDelegate.class).methodName())
            .invoke(instance);
      } else {
        return field.get(instance);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Sets a field value via reflection. */
  static void setFieldValue(Object instance, Field field, Object value) {
    try {
      field.set(instance, value);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Returns a map from Field objects (including non-public and inherited fields) to values.
   *
   * <p>This turns arrays into {@link List} objects so that ImmutableObject can more easily use the
   * returned map in its implementation of {@link ImmutableObject#toString} and {@link
   * ImmutableObject#equals}, which work by comparing and printing these maps.
   */
  public static Map<Field, Object> getFieldValues(Object instance) {
    // Don't make this ImmutableMap because field values can be null.
    Map<Field, Object> values = new LinkedHashMap<>();
    for (Field field : getAllFields(instance.getClass()).values()) {
      Object value = getFieldValue(instance, field);
      if (value != null && value.getClass().isArray()) {
        // It's surprisingly difficult to convert arrays into lists if the array might be primitive.
        final Object arrayValue = value;
        value =
            new AbstractList<>() {
              @Override
              public Object get(int index) {
                return Array.get(arrayValue, index);
              }

              @Override
              public int size() {
                return Array.getLength(arrayValue);
              }
            };
      }
      values.put(field, value);
    }
    return values;
  }

  /** Functional helper for {@link #cloneEmptyToNull}. */
  private static Object cloneEmptyToNullRecursive(Object obj) {
    if (obj instanceof ImmutableSortedMap) {
      // ImmutableSortedMapTranslatorFactory handles empty for us. If the object is null, then
      // its on-save hook can't run.
      return obj;
    }
    if (obj == null
        || obj.equals("")
        || (obj instanceof Collection && ((Collection<?>) obj).isEmpty())
        || (obj instanceof Map && ((Map<?, ?>) obj).isEmpty())
        || (obj.getClass().isArray() && Array.getLength(obj) == 0)) {
      return null;
    }
    Predicate<Object> immutableObjectOrNull = or(isNull(), instanceOf(ImmutableObject.class));
    if ((obj instanceof Set || obj instanceof List)
        && Streams.stream((Iterable<?>) obj).allMatch(immutableObjectOrNull)) {
      // Recurse into sets and lists, but only if they contain ImmutableObjects.
      Stream<?> stream =
          Streams.stream((Iterable<?>) obj).map(ModelUtils::cloneEmptyToNullRecursive);
      // We can't use toImmutable(List/Set) because the values can be null.
      // We can't use toSet because we have to preserve order in the Set.
      // So we use toList (accepts null) and LinkedHashSet (preserves order and accepts null)
      return (obj instanceof List)
          ? stream.collect(toList())
          : stream.collect(toCollection(LinkedHashSet::new));
    }
    if (obj instanceof Map && ((Map<?, ?>) obj).values().stream().allMatch(immutableObjectOrNull)) {
      // Recurse into maps with ImmutableObject values.
      return transformValues((Map<?, ?>) obj, ModelUtils::cloneEmptyToNullRecursive);
    }
    if (obj instanceof ImmutableObject) {
      // Recurse on the fields of an ImmutableObject.
      ImmutableObject copy = ImmutableObject.clone((ImmutableObject) obj);
      for (Field field : getAllFields(obj.getClass()).values()) {
        Object oldValue = getFieldValue(obj, field);
        Object newValue = cloneEmptyToNullRecursive(oldValue);
        if (!Objects.equals(oldValue, newValue)) {
          setFieldValue(copy, field, newValue);
        }
      }
      return copy;
    }
    return obj;
  }

  /** Returns a clone of the object and sets empty collections, arrays, maps and strings to null. */
  @SuppressWarnings("unchecked")
  protected static <T extends ImmutableObject> T cloneEmptyToNull(T obj) {
    return (T) cloneEmptyToNullRecursive(obj);
  }

  @VisibleForTesting
  static void resetCaches() {
    ALL_FIELDS_CACHE.invalidateAll();
  }
}
