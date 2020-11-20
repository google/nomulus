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

package google.registry.schema.replay;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.common.GaeUserIdConverter;
import google.registry.persistence.VKey;
import google.registry.testing.DatastoreEntityExtension;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Test to verify classes implement {@link SqlEntity} and {@link DatastoreEntity} when they should.
 */
public class EntityTest {

  @RegisterExtension
  final DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  private static final ImmutableSet<Class<?>> NON_CONVERTED_CLASSES =
      ImmutableSet.of(GaeUserIdConverter.class);

  @Test
  void testSqlEntityPersistence() {
    try (ScanResult scanResult =
        new ClassGraph().enableAnnotationInfo().whitelistPackages("google.registry").scan()) {
      // All javax.persistence entities must implement SqlEntity and vice versa
      ImmutableSet<String> javaxPersistenceClasses =
          getAllClassesWithAnnotation(scanResult, javax.persistence.Entity.class.getName());
      ImmutableSet<String> sqlEntityClasses =
          getClassNames(scanResult.getClassesImplementing(SqlEntity.class.getName()));
      assertThat(sqlEntityClasses).containsExactlyElementsIn(javaxPersistenceClasses);

      // All com.googlecode.objectify entities must implement DatastoreEntity and vice versa
      ImmutableSet<String> objectifyClasses =
          getAllClassesWithAnnotation(
              scanResult, com.googlecode.objectify.annotation.Entity.class.getName());
      ImmutableSet<String> datastoreEntityClasses =
          getClassNames(scanResult.getClassesImplementing(DatastoreEntity.class.getName()));
      assertThat(datastoreEntityClasses).containsExactlyElementsIn(objectifyClasses);
    }
  }

  @Test
  void testDatastoreEntityVKeyCreation() {
    // For replication, we need to be able to convert from Key -> VKey for the relevant classes.
    // This means that the relevant classes must not have a parent or must have a createVKey method
    try (ScanResult scanResult =
        new ClassGraph().enableAnnotationInfo().whitelistPackages("google.registry").scan()) {
      ImmutableSet<Class<?>> datastoreEntityClasses =
          getClasses(scanResult.getClassesImplementing(DatastoreEntity.class.getName()));
      // some classes aren't converted so they aren't relevant
      ImmutableSet<Class<?>> vkeyConversionNecessaryClasses =
          datastoreEntityClasses.stream()
              .filter(clazz -> !DatastoreOnlyEntity.class.isAssignableFrom(clazz))
              .filter(clazz -> !NonReplicatedEntity.class.isAssignableFrom(clazz))
              .collect(toImmutableSet());

      ImmutableSet.Builder<Class<?>> failedClasses = new ImmutableSet.Builder<>();
      for (Class<?> clazz : vkeyConversionNecessaryClasses) {
        if (hasParent(clazz)) {
          try {
            Method createVKeyMethod = clazz.getMethod("createVKey", Key.class);
            if (!createVKeyMethod.getReturnType().equals(VKey.class)) {
              failedClasses.add(clazz);
            }
          } catch (NoSuchMethodException e) {
            failedClasses.add(clazz);
          }
        }
      }
      assertWithMessage(
              "Some DatastoreEntity classes with parents were missing createVKey methods: ")
          .that(failedClasses.build())
          .isEmpty();
    }
  }

  private <T> boolean hasParent(Class<T> clazz) {
    return getAllFields(clazz).stream()
        .anyMatch(field -> field.getAnnotation(Parent.class) != null);
  }

  private ImmutableSet<Field> getAllFields(Class<?> clazz) {
    ImmutableSet.Builder<Field> builder = new ImmutableSet.Builder<>();
    for (Class<?> currentClass = clazz;
        currentClass != null;
        currentClass = currentClass.getSuperclass()) {
      builder.addAll(ImmutableSet.copyOf(currentClass.getDeclaredFields()));
    }
    return builder.build();
  }

  private ImmutableSet<String> getAllClassesWithAnnotation(
      ScanResult scanResult, String annotation) {
    ImmutableSet.Builder<String> result = new ImmutableSet.Builder<>();
    ClassInfoList classesWithAnnotation = scanResult.getClassesWithAnnotation(annotation);
    result.addAll(getClassNames(classesWithAnnotation));
    classesWithAnnotation.stream()
        .map(ClassInfo::getSubclasses)
        .forEach(classInfoList -> result.addAll(getClassNames(classInfoList)));
    return result.build();
  }

  private ImmutableSet<Class<?>> getClasses(ClassInfoList classInfoList) {
    return classInfoList.stream()
        .filter(ClassInfo::isStandardClass)
        .map(ClassInfo::loadClass)
        .filter(clazz -> !clazz.isAnnotationPresent(EntityForTesting.class))
        .filter(clazz -> !clazz.isAnnotationPresent(Embed.class))
        .filter(clazz -> !NON_CONVERTED_CLASSES.contains(clazz))
        .collect(toImmutableSet());
  }

  private ImmutableSet<String> getClassNames(ClassInfoList classInfoList) {
    return getClasses(classInfoList).stream().map(Class::getName).collect(toImmutableSet());
  }

  /** Entities that are solely used for testing, to avoid scanning them in {@link EntityTest}. */
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface EntityForTesting {}
}
