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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds specialized JUnit rules that start a test database server and provide {@link
 * JpaTransactionManager} instances.
 */
public class JpaTestRules {
  private static final String GOLDEN_SCHEMA_SQL_PATH = "sql/schema/nomulus.golden.sql";

  /**
   * Junit rule for integration tests with JPA framework, when the underlying database is populated
   * with the Nomulus Cloud SQL schema.
   *
   * <p>Test classes that instantiate this class should be included in {@link
   * google.registry.schema.integration.SqlIntegrationTestSuite}. This enforced by {@link
   * google.registry.schema.integration.SqlIntegrationMembershipTest}.
   */
  public static class JpaIntegrationTestRule extends JpaTransactionManagerRule {

    private JpaIntegrationTestRule(
        ImmutableList<Class> extraEntityClasses, ImmutableMap<String, String> userProperties) {
      super(Optional.of(GOLDEN_SCHEMA_SQL_PATH), extraEntityClasses, userProperties);
    }
  }

  /**
   * Junit rule for unit tests with JPA framework, when the underlying database is populated by the
   * optional init script.
   */
  public static class JpaUnitTestRule extends JpaTransactionManagerRule {

    private JpaUnitTestRule(
        Optional<String> initScriptPath,
        ImmutableList<Class> extraEntityClasses,
        ImmutableMap<String, String> userProperties) {
      super(initScriptPath, extraEntityClasses, userProperties);
    }
  }

  /** Builder of test rules that provide {@link JpaTransactionManager}. */
  public static class Builder {
    private String initScript;
    private List<Class> extraEntityClasses = new ArrayList<Class>();
    private Map<String, String> userProperties = new HashMap<String, String>();

    /**
     * Sets the SQL script to be used to initialize the database. If not set,
     * sql/schema/nomulus.golden.sql will be used.
     *
     * <p>The {@code initScript} is only accepted when building {@link JpaUnitTestRule}.
     */
    public Builder withInitScript(String initScript) {
      this.initScript = initScript;
      return this;
    }

    /** Adds annotated class(es) to the known entities for the database. */
    public Builder withEntityClass(Class... classes) {
      this.extraEntityClasses.addAll(ImmutableSet.copyOf(classes));
      return this;
    }

    /** Adds the specified property to those used to initialize the transaction manager. */
    public Builder withProperty(String name, String value) {
      this.userProperties.put(name, value);
      return this;
    }

    /** Builds a {@link JpaIntegrationTestRule} instance. */
    public JpaIntegrationTestRule buildIntegrationTestRule() {
      checkState(initScript == null, "JpaNomulusIntegrationTestRule does not accept initScript");
      return new JpaIntegrationTestRule(
          ImmutableList.copyOf(extraEntityClasses), ImmutableMap.copyOf(userProperties));
    }

    /** Builds a {@link JpaUnitTestRule} instance. */
    public JpaUnitTestRule buildUnitTestRule() {
      return new JpaUnitTestRule(
          Optional.ofNullable(initScript),
          ImmutableList.copyOf(extraEntityClasses),
          ImmutableMap.copyOf(userProperties));
    }
  }
}
