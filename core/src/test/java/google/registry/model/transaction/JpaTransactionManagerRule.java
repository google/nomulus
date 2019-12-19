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

package google.registry.model.transaction;

import static com.google.common.truth.Truth.assertThat;
import static org.joda.time.DateTimeZone.UTC;
import static org.testcontainers.containers.PostgreSQLContainer.POSTGRESQL_PORT;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import google.registry.model.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.persistence.HibernateSchemaExporter;
import google.registry.persistence.NomulusPostgreSql;
import google.registry.persistence.PersistenceModule;
import google.registry.persistence.PersistenceXmlUtility;
import google.registry.testing.FakeClock;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Optional;
import java.util.Properties;
import javax.persistence.EntityManagerFactory;
import org.hibernate.cfg.Environment;
import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.hibernate.jpa.boot.spi.Bootstrap;
import org.joda.time.DateTime;
import org.junit.rules.ExternalResource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class of JUnit Rules to provision {@link JpaTransactionManagerImpl} backed by {@link
 * PostgreSQLContainer}. This class is not for direct use. Use specialized subclasses, {@link
 * JpaIntegrationTestRule} or {@link JpaTestRules.JpaUnitTestRule} as befits the use case.
 *
 * <p>This rule also replaces the {@link JpaTransactionManagerImpl} provided by {@link
 * TransactionManagerFactory} with the {@link JpaTransactionManagerImpl} generated by the rule
 * itself, so that all SQL queries will be sent to the database instance created by {@link
 * PostgreSQLContainer} to achieve test purpose.
 */
abstract class JpaTransactionManagerRule extends ExternalResource {
  private static final String DB_CLEANUP_SQL_PATH =
      "google/registry/model/transaction/cleanup_database.sql";
  private static final String MANAGEMENT_DB_NAME = "management";
  private static final String POSTGRES_DB_NAME = "postgres";

  private final DateTime now = DateTime.now(UTC);
  private final FakeClock clock = new FakeClock(now);
  private final Optional<String> initScriptPath;
  private final ImmutableList<Class> extraEntityClasses;
  private final ImmutableMap userProperties;

  private static final JdbcDatabaseContainer database = create();
  private static final long ACTIVE_CONNECTIONS_BASELINE =
      getActiveConnectionCountByUser(database.getUsername());
  ;
  private static final HibernateSchemaExporter exporter =
      HibernateSchemaExporter.create(
          database.getJdbcUrl(), database.getUsername(), database.getPassword());
  private EntityManagerFactory emf;
  private JpaTransactionManager cachedTm;

  protected JpaTransactionManagerRule(
      Optional<String> initScriptPath,
      ImmutableList<Class> extraEntityClasses,
      ImmutableMap<String, String> userProperties) {
    this.initScriptPath = initScriptPath;
    this.extraEntityClasses = extraEntityClasses;
    this.userProperties = userProperties;
  }

  private static JdbcDatabaseContainer create() {
    PostgreSQLContainer container =
        new PostgreSQLContainer(NomulusPostgreSql.getDockerTag())
            .withDatabaseName(MANAGEMENT_DB_NAME);
    container.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> container.close()));
    return container;
  }

  @Override
  public void before() throws Exception {
    executeSql(MANAGEMENT_DB_NAME, readSqlInClassPath(DB_CLEANUP_SQL_PATH));
    initScriptPath.ifPresent(path -> executeSql(POSTGRES_DB_NAME, readSqlInClassPath(path)));
    if (!extraEntityClasses.isEmpty()) {
      File tempSqlFile = File.createTempFile("tempSqlFile", ".sql");
      tempSqlFile.deleteOnExit();
      exporter.export(extraEntityClasses, tempSqlFile);
      executeSql(
          POSTGRES_DB_NAME,
          new String(Files.readAllBytes(tempSqlFile.toPath()), StandardCharsets.UTF_8));
    }

    ImmutableMap properties = PersistenceModule.providesDefaultDatabaseConfigs();
    if (!userProperties.isEmpty()) {
      // If there are user properties, create a new properties object with these added.
      ImmutableMap.Builder builder = properties.builder();
      builder.putAll(userProperties);
      // Forbid Hibernate push to stay consistent with flyway-based schema management.
      builder.put(Environment.HBM2DDL_AUTO, "none");
      builder.put(Environment.SHOW_SQL, "true");
      properties = builder.build();
    }
    assertNormalActiveConnection();
    emf =
        createEntityManagerFactory(
            getJdbcUrlFor(POSTGRES_DB_NAME),
            database.getUsername(),
            database.getPassword(),
            properties,
            extraEntityClasses);
    JpaTransactionManagerImpl txnManager = new JpaTransactionManagerImpl(emf, clock);
    cachedTm = TransactionManagerFactory.jpaTm;
    TransactionManagerFactory.jpaTm = txnManager;
  }

  @Override
  public void after() {
    TransactionManagerFactory.jpaTm = cachedTm;
    if (emf != null) {
      emf.close();
      emf = null;
    }
    cachedTm = null;
    assertNormalActiveConnection();
  }

  private static long getActiveConnectionCountByUser(String userName) {
    try (Connection conn = createConnection(POSTGRES_DB_NAME);
        Statement statement = conn.createStatement()) {
      ResultSet rs =
          statement.executeQuery(
              "SELECT COUNT(1) FROM pg_stat_activity WHERE usename = '" + userName + "'");
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * This function throws exception if it detects connection leak by checking the metadata table
   * pg_stat_activity.
   */
  private void assertNormalActiveConnection() {
    assertThat(getActiveConnectionCountByUser(database.getUsername()))
        .isEqualTo(ACTIVE_CONNECTIONS_BASELINE);
  }

  private static String readSqlInClassPath(String sqlScriptPath) {
    try {
      return Resources.toString(Resources.getResource(sqlScriptPath), Charsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void executeSql(String dbName, String sqlScript) {
    try (Connection conn = createConnection(dbName);
        Statement statement = conn.createStatement()) {
      statement.execute(sqlScript);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String getJdbcUrlFor(String dbName) {
    // Disable Postgres driver use of java.util.logging to reduce noise at startup time
    return "jdbc:postgresql://"
        + database.getContainerIpAddress()
        + ":"
        + database.getMappedPort(POSTGRESQL_PORT)
        + "/"
        + dbName
        + "?loggerLevel=OFF";
  }

  private static Connection createConnection(String dbName) {
    final Properties info = new Properties();
    info.put("user", database.getUsername());
    info.put("password", database.getPassword());
    final Driver jdbcDriverInstance = database.getJdbcDriverInstance();
    try {
      return jdbcDriverInstance.connect(getJdbcUrlFor(dbName), info);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** Constructs the {@link EntityManagerFactory} instance. */
  private static EntityManagerFactory createEntityManagerFactory(
      String jdbcUrl,
      String username,
      String password,
      ImmutableMap<String, String> configs,
      ImmutableList<Class> extraEntityClasses) {
    HashMap<String, String> properties = Maps.newHashMap(configs);
    properties.put(Environment.URL, jdbcUrl);
    properties.put(Environment.USER, username);
    properties.put(Environment.PASS, password);

    ParsedPersistenceXmlDescriptor descriptor =
        PersistenceXmlUtility.getParsedPersistenceXmlDescriptor();

    extraEntityClasses.stream().map(Class::getName).forEach(descriptor::addClasses);
    return Bootstrap.getEntityManagerFactoryBuilder(descriptor, properties).build();
  }

  /** Returns the {@link FakeClock} used by the underlying {@link JpaTransactionManagerImpl}. */
  public FakeClock getTxnClock() {
    return clock;
  }

}
