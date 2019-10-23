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

import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import google.registry.model.transaction.JpaTransactionManagerRule;
import java.lang.reflect.Field;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.reflections.Reflections;

/**
 * Verifies that all tests that depends on the Cloud SQL schema are included in the project's
 * sqlIntegrationTest suite. Names of the test classes is set to the 'test.sqlIntergrationTests'
 * system property as a comma-separated string.
 *
 * <p>A test is deemed dependent on the SQL schema iff it has a field with type {@link
 * JpaTransactionManagerRule}.
 */
// TODO(weiminyu): consider generating a TestSuite class instead.
@RunWith(JUnit4.class)
public class SqlIntegrationMembershipTest {

  @Test
  public void sqlIntegrationMembershipComplete() {
    ImmutableSet<String> sqlDependentTests =
        new Reflections("google.registry")
            .getTypesAnnotatedWith(RunWith.class, true).stream()
                .filter(clazz -> clazz.getSimpleName().endsWith("Test"))
                .filter(SqlIntegrationMembershipTest::isSqlDependent)
                .map(Class::getName)
                .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<String> declaredTests =
        ImmutableSet.copyOf(System.getProperty("test.sqlIntergrationTests", "").split(","));

    SetView<String> undeclaredTests = Sets.difference(sqlDependentTests, declaredTests);
    assertWithMessage(
            "Undeclared sql-dependent tests found. "
                + "Make sure they are included in sqlIntegrationTestPatterns in build script.")
        .that(undeclaredTests)
        .isEmpty();
    SetView<String> unnecessaryDeclarations = Sets.difference(declaredTests, sqlDependentTests);
    assertWithMessage("Found tests that should not be included in sqlIntegrationTestPatterns.")
        .that(unnecessaryDeclarations)
        .isEmpty();
  }

  private static boolean isSqlDependent(Class<?> testClass) {
    return Stream.of(testClass.getDeclaredFields())
        .map(Field::getType)
        .anyMatch(JpaTransactionManagerRule.class::equals);
  }
}
