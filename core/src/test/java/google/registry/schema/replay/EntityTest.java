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

import com.google.common.collect.ImmutableSet;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Test to verify classes implement {@link SqlEntity} and {@link DatastoreEntity} when they should.
 */
public class EntityTest {

  @Test
  @Disabled("This won't be done until b/152410794 is done, since it requires many entity changes")
  void testSqlEntityPersistence() {
    try (ScanResult scanResult =
        new ClassGraph().enableAnnotationInfo().whitelistPackages("google.registry").scan()) {
      // All javax.persistence entities must implement SqlEntity and vice versa
      ImmutableSet<String> javaxPersistenceClasses =
          getClassNames(
              scanResult.getClassesWithAnnotation(javax.persistence.Entity.class.getName()));
      ImmutableSet<String> sqlEntityClasses =
          getClassNames(scanResult.getClassesImplementing(SqlEntity.class.getName()));
      assertThat(javaxPersistenceClasses).isEqualTo(sqlEntityClasses);

      // All com.googlecode.objectify.annotation.Entity classes must implement DatastoreEntity and
      // vice versa
      ImmutableSet<String> objectifyClasses =
          getClassNames(
              scanResult.getClassesWithAnnotation(
                  com.googlecode.objectify.annotation.Entity.class.getName()));
      ImmutableSet<String> datastoreEntityClasses =
          getClassNames(scanResult.getClassesImplementing(DatastoreEntity.class.getName()));
      assertThat(objectifyClasses).isEqualTo(datastoreEntityClasses);
    }
  }

  private ImmutableSet<String> getClassNames(ClassInfoList classInfoList) {
    return classInfoList.stream()
        .filter(ClassInfo::isStandardClass)
        .map(ClassInfo::loadClass)
        .filter(clazz -> !clazz.isAnnotationPresent(EntityForTesting.class))
        .map(Class::getName)
        .collect(toImmutableSet());
  }

  /** Entities that are solely used for testing, to avoid scanning them in {@link EntityTest}. */
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface EntityForTesting {}
}
