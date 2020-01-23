// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.integration;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.truth.Expect;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.lang.reflect.Field;
import java.util.stream.Stream;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Suite.SuiteClasses;

/**
 * Verifies that {@link SqlIntegrationTestSuite} includes all integration tests for JPA entities and
 * nothing else. The test suite is used in server/schema compatibility tests between releases.
 *
 * <p>All JPA entity test classes are expected to have a field with type {@link
 * JpaIntegrationWithCoverageRule}.
 */
@RunWith(JUnit4.class)
public class SqlIntegrationMembershipTest {

  @ClassRule public static final Expect expect = Expect.create();

  @Test
  public void sqlIntegrationMembershipComplete() {
    ImmutableSet<String> sqlDependentTests;
    try (ScanResult scanResult =
        new ClassGraph().enableAnnotationInfo().whitelistPackages("google.registry").scan()) {
      sqlDependentTests =
          scanResult.getClassesWithAnnotation(RunWith.class.getName()).stream()
              .filter(clazz -> clazz.getSimpleName().endsWith("Test"))
              .map(clazz -> clazz.loadClass())
              .filter(SqlIntegrationMembershipTest::isSqlDependent)
              .map(Class::getName)
              .collect(ImmutableSet.toImmutableSet());
    }
    ImmutableSet<String> declaredTests =
        Stream.of(SqlIntegrationTestSuite.class.getAnnotation(SuiteClasses.class).value())
            .map(Class::getName)
            .collect(ImmutableSet.toImmutableSet());
    SetView<String> undeclaredTests = Sets.difference(sqlDependentTests, declaredTests);
    expect
        .withMessage(
            "Undeclared sql-dependent tests found. "
                + "Please add them to SqlIntegrationTestSuite.java.")
        .that(undeclaredTests)
        .isEmpty();
    SetView<String> unnecessaryDeclarations = Sets.difference(declaredTests, sqlDependentTests);
    expect
        .withMessage("Found tests that should not be included in SqlIntegrationTestSuite.java.")
        .that(unnecessaryDeclarations)
        .isEmpty();
  }

  private static boolean isSqlDependent(Class<?> testClass) {
    for (Class<?> clazz = testClass; clazz != null; clazz = clazz.getSuperclass()) {
      if (Stream.of(clazz.getDeclaredFields())
          .map(Field::getType)
          .anyMatch(JpaIntegrationWithCoverageRule.class::equals)) {
        return true;
      }
    }
    return false;
  }
}
