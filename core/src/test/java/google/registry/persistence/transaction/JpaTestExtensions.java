// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.transaction;

import static com.google.common.base.Preconditions.checkState;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.cfg.Environment;
import org.joda.time.DateTime;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Holds specialized JUnit extensions that start a test database server and provide {@link
 * JpaTransactionManager} instances.
 */
public class JpaTestExtensions {

  private static final String GOLDEN_SCHEMA_SQL_PATH = "sql/schema/nomulus.golden.sql";
  private static final String HSTORE_EXTENSION_SQL_PATH =
      "sql/flyway/V14__load_extension_for_hstore.sql";

  /**
   * JUnit extension for integration tests with JPA framework, when the underlying database is
   * populated with the Nomulus Cloud SQL schema.
   */
  public static class JpaIntegrationTestExtension extends JpaTransactionManagerExtension {
    private JpaIntegrationTestExtension(
        Clock clock,
        ImmutableList<Class<?>> extraEntityClasses,
        ImmutableMap<String, String> userProperties) {
      super(clock, Optional.of(GOLDEN_SCHEMA_SQL_PATH), extraEntityClasses, userProperties);
    }
  }

  /**
   * JUnit extension for unit tests with JPA framework, when the underlying database is populated by
   * the optional init script (which must not be the Nomulus Cloud SQL schema).
   */
  public static class JpaUnitTestExtension extends JpaTransactionManagerExtension {
    private JpaUnitTestExtension(
        Clock clock,
        Optional<String> initScriptPath,
        ImmutableList<Class<?>> extraEntityClasses,
        ImmutableMap<String, String> userProperties) {
      super(clock, initScriptPath, false, extraEntityClasses, userProperties);
    }
  }

  /**
   * JUnit extension for member classes of {@link
   * google.registry.schema.integration.SqlIntegrationTestSuite}. In addition to providing a
   * database through {@link JpaIntegrationTestExtension}, it also keeps track of the test coverage
   * of the declared JPA entities (in persistence.xml). Per-class statistics are stored in static
   * variables. The SqlIntegrationTestSuite inspects the cumulative statistics after all test
   * classes have run.
   */
  public static final class JpaIntegrationWithCoverageExtension
      implements BeforeEachCallback, AfterEachCallback {

    private final JpaEntityCoverageExtension jpaEntityCoverage = new JpaEntityCoverageExtension();
    private final JpaIntegrationTestExtension integrationTestExtension;

    JpaIntegrationWithCoverageExtension(JpaIntegrationTestExtension integrationTestExtension) {
      this.integrationTestExtension = integrationTestExtension;
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
      integrationTestExtension.beforeEach(context);
      jpaEntityCoverage.beforeEach(context);
    }

    @Override
    public void afterEach(ExtensionContext context) {
      jpaEntityCoverage.afterEach(context);
      integrationTestExtension.afterEach(context);
    }
  }

  /** Builder of test extensions that provide {@link JpaTransactionManager}. */
  public static class Builder {

    private String initScript;
    private Clock clock;
    private List<Class<?>> extraEntityClasses = new ArrayList<>();
    private Map<String, String> userProperties = new HashMap<>();

    /**
     * Sets the SQL script to be used to initialize the database. If not set,
     * sql/schema/nomulus.golden.sql will be used.
     *
     * <p>The {@code initScript} is only accepted when building {@link JpaUnitTestExtension}.
     */
    public Builder withInitScript(String initScript) {
      this.initScript = initScript;
      return this;
    }

    public Builder withClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /** Adds annotated class(es) to the known entities for the database. */
    public Builder withEntityClass(Class<?>... classes) {
      this.extraEntityClasses.addAll(ImmutableSet.copyOf(classes));
      return this;
    }

    /** Adds the specified property to those used to initialize the transaction manager. */
    Builder withProperty(String name, String value) {
      this.userProperties.put(name, value);
      return this;
    }

    /**
     * Enables logging of SQL statements.
     *
     * <p>SQL logging is very noisy and disabled by default. This method maybe useful when
     * troubleshooting a specific test.
     */
    Builder withSqlLogging() {
      withProperty(Environment.SHOW_SQL, "true");
      return this;
    }

    /** Builds a {@link JpaIntegrationTestExtension} instance. */
    public JpaIntegrationTestExtension buildIntegrationTestExtension() {
      return new JpaIntegrationTestExtension(
          clock == null ? new FakeClock(DateTime.now(UTC)) : clock,
          ImmutableList.copyOf(extraEntityClasses),
          ImmutableMap.copyOf(userProperties));
    }

    /**
     * JUnit extension that adapts {@link JpaIntegrationTestExtension} for JUnit 5 and also checks
     * test coverage of JPA entity classes.
     */
    public JpaIntegrationWithCoverageExtension buildIntegrationWithCoverageExtension() {
      checkState(initScript == null, "Integration tests do not accept initScript");
      return new JpaIntegrationWithCoverageExtension(buildIntegrationTestExtension());
    }

    /**
     * Builds a {@link JpaUnitTestExtension} instance that can also be used as an extension for
     * JUnit5.
     */
    public JpaUnitTestExtension buildUnitTestExtension() {
      checkState(
          !Objects.equals(GOLDEN_SCHEMA_SQL_PATH, initScript),
          "Unit tests must not depend on the Nomulus schema.");
      return new JpaUnitTestExtension(
          clock == null ? new FakeClock(DateTime.now(UTC)) : clock,
          // Use the hstore extension by default so we can save the migration schedule
          Optional.of(initScript == null ? HSTORE_EXTENSION_SQL_PATH : initScript),
          ImmutableList.copyOf(extraEntityClasses),
          ImmutableMap.copyOf(userProperties));
    }
  }
}
