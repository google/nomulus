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

package google.registry.testing;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.persistence.transaction.TransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

/**
 * Implementation of {@link TestTemplateInvocationContextProvider} to execute tests against
 * different database. The test annotated with {@link TestTemplate} will be executed twice against
 * Datastore and PostgresQL respectively.
 */
class DualDatabaseTestInvocationContextProvider implements TestTemplateInvocationContextProvider {

  private static final Namespace NAMESPACE =
      Namespace.create(DualDatabaseTestInvocationContextProvider.class);
  private static final String INJECTED_TM_SUPPLIER_KEY = "injected_tm_supplier_key";
  private static final String ORIGINAL_TM_KEY = "original_tm_key";

  @Override
  public boolean supportsTestTemplate(ExtensionContext context) {
    return true;
  }

  /**
   * Returns true if "context" is an objectify unit test.
   *
   * <p>Provided to allow ReplayExtension to make this determination.
   */
  static boolean inOfyContext(ExtensionContext context) {
    return (DatabaseType) context.getStore(NAMESPACE).get(INJECTED_TM_SUPPLIER_KEY)
        == DatabaseType.OFY;
  }

  @Override
  public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
      ExtensionContext context) {
    TestTemplateInvocationContext ofyContext =
        createInvocationContext(context.getDisplayName() + " with Datastore", DatabaseType.OFY);
    TestTemplateInvocationContext sqlContext =
        createInvocationContext(context.getDisplayName() + " with PostgreSQL", DatabaseType.JPA);
    Method testMethod = context.getTestMethod().orElseThrow(IllegalStateException::new);
    if (testMethod.isAnnotationPresent(TestOfyAndSql.class)) {
      return Stream.of(ofyContext, sqlContext);
    } else if (testMethod.isAnnotationPresent(TestOfyOnly.class)) {
      return Stream.of(ofyContext);
    } else if (testMethod.isAnnotationPresent(TestSqlOnly.class)) {
      return Stream.of(sqlContext);
    } else {
      throw new IllegalStateException(
          "Test method must be annotated with @TestOfyAndSql, @TestOfyOnly or @TestSqlOnly");
    }
  }

  private TestTemplateInvocationContext createInvocationContext(
      String name, DatabaseType databaseType) {
    return new TestTemplateInvocationContext() {
      @Override
      public String getDisplayName(int invocationIndex) {
        return name;
      }

      @Override
      public List<Extension> getAdditionalExtensions() {
        return ImmutableList.of(new DatabaseSwitchInvocationContext(databaseType));
      }
    };
  }

  private static class DatabaseSwitchInvocationContext implements TestInstancePostProcessor {

    private DatabaseType databaseType;

    private DatabaseSwitchInvocationContext(DatabaseType databaseType) {
      this.databaseType = databaseType;
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context)
        throws Exception {
      List<Field> appEngineExtensionFields = getAppEngineExtensionFields(testInstance.getClass());
      if (appEngineExtensionFields.size() != 1) {
        throw new IllegalStateException(
            String.format(
                "@DualDatabaseTest test must have 1 AppEngineExtension field but found %d field(s)",
                appEngineExtensionFields.size()));
      }
      appEngineExtensionFields.get(0).setAccessible(true);
      AppEngineExtension appEngineExtension =
          (AppEngineExtension) appEngineExtensionFields.get(0).get(testInstance);
      if (!appEngineExtension.isWithDatastoreAndCloudSql()) {
        throw new IllegalStateException(
            "AppEngineExtension in @DualDatabaseTest test must set withDatastoreAndCloudSql()");
      }
      context.getStore(NAMESPACE).put(INJECTED_TM_SUPPLIER_KEY, databaseType);
    }

    private static ImmutableList<Field> getAppEngineExtensionFields(Class<?> clazz) {
      ImmutableList.Builder<Field> fieldBuilder = new ImmutableList.Builder<>();
      if (clazz.getSuperclass() != null) {
        fieldBuilder.addAll(getAppEngineExtensionFields(clazz.getSuperclass()));
      }
      fieldBuilder.addAll(
          Stream.of(clazz.getDeclaredFields())
              .filter(field -> AppEngineExtension.class.isAssignableFrom(field.getType()))
              .collect(toImmutableList()));
      return fieldBuilder.build();
    }
  }

  static void injectTmForDualDatabaseTest(ExtensionContext context) {
    if (isDualDatabaseTest(context)) {
      context
          .getTestMethod()
          .ifPresent(
              testMethod -> {
                if (!testMethod.isAnnotationPresent(TestOfyAndSql.class)
                    && !testMethod.isAnnotationPresent(TestOfyOnly.class)
                    && !testMethod.isAnnotationPresent(TestSqlOnly.class)) {
                  throw new IllegalStateException(
                      "Test method must be annotated with @TestOfyAndSql, @TestOfyOnly or"
                          + " @TestSqlOnly");
                }
              });
      context.getStore(NAMESPACE).put(ORIGINAL_TM_KEY, tm());
      DatabaseType databaseType =
          (DatabaseType) context.getStore(NAMESPACE).get(INJECTED_TM_SUPPLIER_KEY);
      TransactionManagerFactory.setTmForTest(databaseType.getTm());
    }
  }

  static void restoreTmAfterDualDatabaseTest(ExtensionContext context) {
    if (isDualDatabaseTest(context)) {
      TransactionManagerFactory.removeTmOverrideForTest();
    }
  }

  private static boolean isDualDatabaseTest(ExtensionContext context) {
    Object testInstance = context.getTestInstance().orElseThrow(RuntimeException::new);
    // If the test method is declared in its parent class,
    // e.g. google.registry.flows.ResourceFlowTestCase.testRequiresLogin,
    // we don't consider it is a DualDatabaseTest. This is because there may exist some subclasses
    // that have not been migrated to DualDatabaseTest.
    boolean isDeclaredTestMethod =
        ImmutableSet.copyOf(testInstance.getClass().getDeclaredMethods())
            .contains(context.getTestMethod().orElseThrow(RuntimeException::new));
    return testInstance.getClass().isAnnotationPresent(DualDatabaseTest.class)
        && isDeclaredTestMethod;
  }

  private enum DatabaseType {
    JPA(TransactionManagerFactory::jpaTm),
    OFY(TransactionManagerFactory::ofyTm);

    @SuppressWarnings("Immutable") // Supplier is immutable, but not annotated as such.
    private final Supplier<? extends TransactionManager> supplier;

    DatabaseType(Supplier<? extends TransactionManager> supplier) {
      this.supplier = supplier;
    }

    TransactionManager getTm() {
      return supplier.get();
    }
  }
}
