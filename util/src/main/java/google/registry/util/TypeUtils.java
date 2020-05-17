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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.CollectionUtils.difference;
import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isStatic;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;

/** Utilities methods related to reflection. */
public class TypeUtils {

  /** A {@code TypeToken} that removes an ugly cast in the common cases of getting a known type. */
  public static class TypeInstantiator<T> extends TypeToken<T> {
    protected TypeInstantiator(Class<?> declaringClass) {
      super(declaringClass);
    }

    @SuppressWarnings("unchecked")
    public Class<T> getExactType() {
      return (Class<T>) getRawType();
    }

    public T instantiate() {
      return TypeUtils.instantiate(getExactType());
    }
  }

  public static <T> T instantiate(Class<? extends T> clazz) {
    checkArgument(Modifier.isPublic(clazz.getModifiers()),
        "AppEngine's custom security manager won't let us reflectively access non-public types");
    try {
      return clazz.getConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Instantiate a class with the specified constructor argument.
   *
   * <p>Because we use arg1's type to lookup the constructor, this only works if arg1's class is
   * exactly the same type as the constructor argument. Subtypes are not allowed.
   */
  public static <T, U> T instantiate(Class<? extends T> clazz, U arg1) {
    checkArgument(Modifier.isPublic(clazz.getModifiers()),
        "AppEngine's custom security manager won't let us reflectively access non-public types");
    try {
      return clazz.getConstructor(arg1.getClass()).newInstance(arg1);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the class referred to by a fully qualified class name string.
   *
   * <p>Throws an error if the loaded class is not assignable from the expected super type class.
   */
  public static <T> Class<T> getClassFromString(String className, Class<T> expectedSuperType) {
    Class<?> clazz;
    try {
      clazz = Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(String.format("Failed to load class %s", className), e);
    }
    checkArgument(
        expectedSuperType.isAssignableFrom(clazz),
        "%s does not implement/extend %s",
        clazz.getSimpleName(),
        expectedSuperType.getSimpleName());
    @SuppressWarnings("unchecked")
    Class<T> castedClass = (Class<T>) clazz;
    return castedClass;
  }

  /** Aggregates enum "values" in a typesafe enum pattern into a string-&gt;field map. */
  @SuppressWarnings("unchecked")
  public static <T> ImmutableMap<String, T> getTypesafeEnumMapping(Class<T> clazz) {
    ImmutableMap.Builder<String, T> builder = new ImmutableMap.Builder<>();
    for (Field field : clazz.getFields()) {
      if (isFinal(field.getModifiers())
          && isStatic(field.getModifiers())
          && clazz.isAssignableFrom(field.getType())) {
        try {
          T enumField = (T) field.get(null);
          builder.put(field.getName(), enumField);
        } catch (IllegalArgumentException | IllegalAccessException e) {
          throw new RuntimeException(String.format(
              "Could not retrieve static final field mapping for %s", clazz.getName()), e);
        }
      }
    }
    return builder.build();
  }

  /** Returns a predicate that tests whether classes are annotated with the given annotation. */
  public static Predicate<Class<?>> hasAnnotation(
      final Class<? extends Annotation> annotation) {
    return clazz -> clazz.isAnnotationPresent(annotation);
  }

  public static void checkNoInheritanceRelationships(ImmutableSet<Class<?>> resourceClasses) {
    for (Class<?> resourceClass : resourceClasses) {
      for (Class<?> potentialSuperclass : difference(resourceClasses, resourceClass)) {
        checkArgument(
            !potentialSuperclass.isAssignableFrom(resourceClass),
            "Cannot specify resource classes with inheritance relationship: %s extends %s",
            resourceClass,
            potentialSuperclass);
      }
    }
  }
}
