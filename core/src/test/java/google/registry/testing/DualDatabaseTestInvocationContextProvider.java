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
import google.registry.persistence.transaction.TransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import java.lang.reflect.Field;
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

  @Override
  public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
      ExtensionContext context) {
    return Stream.of(
        createInvocationContext("Test Datastore", TransactionManagerFactory::ofyTm),
        createInvocationContext("Test PostgreSQL", TransactionManagerFactory::jpaTm));
  }

  private TestTemplateInvocationContext createInvocationContext(
      String name, Supplier<? extends TransactionManager> tmSupplier) {
    return new TestTemplateInvocationContext() {
      @Override
      public String getDisplayName(int invocationIndex) {
        return name;
      }

      @Override
      public List<Extension> getAdditionalExtensions() {
        return ImmutableList.of(new DatabaseSwitchInvocationContext(tmSupplier));
      }
    };
  }

  private static class DatabaseSwitchInvocationContext implements TestInstancePostProcessor {

    private Supplier<? extends TransactionManager> tmSupplier;

    private DatabaseSwitchInvocationContext(Supplier<? extends TransactionManager> tmSupplier) {
      this.tmSupplier = tmSupplier;
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context)
        throws Exception {
      List<Field> appEngineRuleFields =
          Stream.of(testInstance.getClass().getFields())
              .filter(field -> field.getType().isAssignableFrom(AppEngineExtension.class))
              .collect(toImmutableList());
      if (appEngineRuleFields.size() != 1) {
        throw new IllegalStateException(
            "@DualDatabaseTest test must have only 1 AppEngineRule field");
      }
      appEngineRuleFields.get(0).setAccessible(true);
      AppEngineExtension appEngineRule =
          (AppEngineExtension) appEngineRuleFields.get(0).get(testInstance);
      if (!appEngineRule.isWithDatastoreAndCloudSql()) {
        throw new IllegalStateException(
            "AppEngineRule in @DualDatabaseTest test must set withDatastoreAndCloudSql()");
      }
      context.getStore(NAMESPACE).put(INJECTED_TM_SUPPLIER_KEY, tmSupplier);
    }
  }

  static void injectTmForDualDatabaseTest(ExtensionContext context) {
    if (isDualDatabaseTest(context)) {
      context.getStore(NAMESPACE).put(ORIGINAL_TM_KEY, tm());
      Supplier<? extends TransactionManager> tmSupplier =
          (Supplier<? extends TransactionManager>)
              context.getStore(NAMESPACE).get(INJECTED_TM_SUPPLIER_KEY);
      TransactionManagerFactory.setTm(tmSupplier.get());
    }
  }

  static void restoreTmAfterDualDatabaseTest(ExtensionContext context) {
    if (isDualDatabaseTest(context)) {
      TransactionManager original =
          (TransactionManager) context.getStore(NAMESPACE).get(ORIGINAL_TM_KEY);
      TransactionManagerFactory.setTm(original);
    }
  }

  private static boolean isDualDatabaseTest(ExtensionContext context) {
    Object testInstance = context.getTestInstance().orElseThrow(RuntimeException::new);
    return testInstance.getClass().isAnnotationPresent(DualDatabaseTest.class);
  }
}
