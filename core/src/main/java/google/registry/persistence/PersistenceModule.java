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

package google.registry.persistence;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.config.RegistryConfig.getHibernateConnectionIsolation;
import static google.registry.config.RegistryConfig.getHibernateHikariConnectionTimeout;
import static google.registry.config.RegistryConfig.getHibernateHikariIdleTimeout;
import static google.registry.config.RegistryConfig.getHibernateHikariMaximumPoolSize;
import static google.registry.config.RegistryConfig.getHibernateHikariMinimumIdle;
import static google.registry.config.RegistryConfig.getHibernateLogSqlQueries;

import com.google.api.client.auth.oauth2.Credential;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.kms.KmsKeyring;
import google.registry.persistence.transaction.CloudSqlCredentialSupplier;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.JpaTransactionManagerImpl;
import google.registry.privileges.secretmanager.SqlCredential;
import google.registry.privileges.secretmanager.SqlCredentialStore;
import google.registry.privileges.secretmanager.SqlUser;
import google.registry.privileges.secretmanager.SqlUser.RobotId;
import google.registry.privileges.secretmanager.SqlUser.RobotUser;
import google.registry.tools.AuthModule.CloudSqlClientCredential;
import google.registry.util.Clock;
import java.lang.annotation.Documented;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import org.hibernate.cfg.Environment;

/** Dagger module class for the persistence layer. */
@Module
public abstract class PersistenceModule {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // This name must be the same as the one defined in persistence.xml.
  public static final String PERSISTENCE_UNIT_NAME = "nomulus";
  public static final String HIKARI_CONNECTION_TIMEOUT = "hibernate.hikari.connectionTimeout";
  public static final String HIKARI_MINIMUM_IDLE = "hibernate.hikari.minimumIdle";
  public static final String HIKARI_MAXIMUM_POOL_SIZE = "hibernate.hikari.maximumPoolSize";
  public static final String HIKARI_IDLE_TIMEOUT = "hibernate.hikari.idleTimeout";

  public static final String HIKARI_DS_SOCKET_FACTORY = "hibernate.hikari.dataSource.socketFactory";
  public static final String HIKARI_DS_CLOUD_SQL_INSTANCE =
      "hibernate.hikari.dataSource.cloudSqlInstance";

  @VisibleForTesting
  @Provides
  @DefaultHibernateConfigs
  public static ImmutableMap<String, String> provideDefaultDatabaseConfigs() {
    ImmutableMap.Builder<String, String> properties = ImmutableMap.builder();

    properties.put(Environment.DRIVER, "org.postgresql.Driver");
    properties.put(
        Environment.CONNECTION_PROVIDER,
        "org.hibernate.hikaricp.internal.HikariCPConnectionProvider");
    // Whether to automatically validate and export schema DDL to the database when the
    // SessionFactory is created. Setting it to 'none' to turn off the feature.
    properties.put(Environment.HBM2DDL_AUTO, "none");

    // Hibernate converts any date to this timezone when writing to the database.
    properties.put(Environment.JDBC_TIME_ZONE, "UTC");
    properties.put(
        Environment.PHYSICAL_NAMING_STRATEGY, NomulusNamingStrategy.class.getCanonicalName());

    properties.put(Environment.ISOLATION, getHibernateConnectionIsolation());
    properties.put(Environment.SHOW_SQL, getHibernateLogSqlQueries());
    properties.put(HIKARI_CONNECTION_TIMEOUT, getHibernateHikariConnectionTimeout());
    properties.put(HIKARI_MINIMUM_IDLE, getHibernateHikariMinimumIdle());
    properties.put(HIKARI_MAXIMUM_POOL_SIZE, getHibernateHikariMaximumPoolSize());
    properties.put(HIKARI_IDLE_TIMEOUT, getHibernateHikariIdleTimeout());
    properties.put(Environment.DIALECT, NomulusPostgreSQLDialect.class.getName());
    return properties.build();
  }

  @Provides
  @Singleton
  @PartialCloudSqlConfigs
  static ImmutableMap<String, String> providePartialCloudSqlConfigs(
      @Config("cloudSqlJdbcUrl") String jdbcUrl,
      @Config("cloudSqlInstanceConnectionName") String instanceConnectionName,
      @DefaultHibernateConfigs ImmutableMap<String, String> defaultConfigs) {
    return createPartialSqlConfigs(
        jdbcUrl, instanceConnectionName, defaultConfigs, Optional.empty());
  }

  /**
   * Optionally overrides the isolation level in the config file.
   *
   * <p>The binding for {@link TransactionIsolationLevel} may be {@link Nullable}. As a result, it
   * is a compile-time error to inject {@code Optional<TransactionIsolation>} (See {@link
   * BindsOptionalOf} for more information). User should inject {@code
   * Optional<Provider<TransactionIsolation>>} instead.
   */
  @BindsOptionalOf
  @Config("beamIsolationOverride")
  abstract TransactionIsolationLevel bindBeamIsolationOverride();

  /**
   * Optionally overrides the Cloud SQL database instance's connection name.
   *
   * <p>This allows connections to alternative database instances, e.g., the read-only replica or a
   * test database.
   */
  @BindsOptionalOf
  @Config("instanceConnectionNameOverride")
  abstract String instanceConnectionNameOverride();

  @Provides
  @Singleton
  @BeamPipelineCloudSqlConfigs
  static ImmutableMap<String, String> provideBeamPipelineCloudSqlConfigs(
      @Config("beamCloudSqlJdbcUrl") String jdbcUrl,
      @Config("beamCloudSqlInstanceConnectionName") String instanceConnectionName,
      @DefaultHibernateConfigs ImmutableMap<String, String> defaultConfigs,
      @Config("beamIsolationOverride")
          Optional<Provider<TransactionIsolationLevel>> isolationOverride) {
    return createPartialSqlConfigs(
        jdbcUrl, instanceConnectionName, defaultConfigs, isolationOverride);
  }

  @VisibleForTesting
  static ImmutableMap<String, String> createPartialSqlConfigs(
      String jdbcUrl,
      String instanceConnectionName,
      ImmutableMap<String, String> defaultConfigs,
      Optional<Provider<TransactionIsolationLevel>> isolationOverride) {
    HashMap<String, String> overrides = Maps.newHashMap(defaultConfigs);
    overrides.put(Environment.URL, jdbcUrl);
    overrides.put(HIKARI_DS_SOCKET_FACTORY, "com.google.cloud.sql.postgres.SocketFactory");
    overrides.put(HIKARI_DS_CLOUD_SQL_INSTANCE, instanceConnectionName);
    isolationOverride
        .map(Provider::get)
        .ifPresent(override -> overrides.put(Environment.ISOLATION, override.name()));
    return ImmutableMap.copyOf(overrides);
  }

  /**
   * Provides a {@link Supplier} of single-use JDBC {@link Connection connections} that can manage
   * the database DDL schema.
   */
  @Provides
  @Singleton
  @SchemaManagerConnection
  static Supplier<Connection> provideSchemaManagerConnectionSupplier(
      SqlCredentialStore credentialStore,
      @PartialCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs) {
    SqlCredential credential =
        credentialStore.getCredential(new RobotUser(RobotId.SCHEMA_DEPLOYER));
    String user = credential.login();
    String password = credential.password();
    return () -> createJdbcConnection(user, password, cloudSqlConfigs);
  }

  private static Connection createJdbcConnection(
      String user, String password, ImmutableMap<String, String> cloudSqlConfigs) {
    Properties properties = new Properties();
    properties.put("user", user);
    properties.put("password", password);
    properties.put("cloudSqlInstance", cloudSqlConfigs.get(HIKARI_DS_CLOUD_SQL_INSTANCE));
    properties.put("socketFactory", cloudSqlConfigs.get(HIKARI_DS_SOCKET_FACTORY));
    try {
      return DriverManager.getConnection(cloudSqlConfigs.get(Environment.URL), properties);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  @Singleton
  @AppEngineJpaTm
  static JpaTransactionManager provideAppEngineJpaTm(
      @Config("cloudSqlUsername") String username,
      KmsKeyring kmsKeyring,
      SqlCredentialStore credentialStore,
      @PartialCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs,
      Clock clock) {
    HashMap<String, String> overrides = Maps.newHashMap(cloudSqlConfigs);
    validateAndSetCredential(
        credentialStore,
        new RobotUser(RobotId.NOMULUS),
        overrides,
        username,
        kmsKeyring.getCloudSqlPassword());
    return new JpaTransactionManagerImpl(create(overrides), clock);
  }

  @Provides
  @Singleton
  @BeamJpaTm
  static JpaTransactionManager provideBeamJpaTm(
      SqlCredentialStore credentialStore,
      @Config("instanceConnectionNameOverride")
          Optional<Provider<String>> instanceConnectionNameOverride,
      @Config("beamIsolationOverride")
          Optional<Provider<TransactionIsolationLevel>> isolationOverride,
      @PartialCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs,
      Clock clock) {
    HashMap<String, String> overrides = Maps.newHashMap(cloudSqlConfigs);
    // TODO(b/175700623): make sql username configurable from config file.
    SqlCredential credential = credentialStore.getCredential(new RobotUser(RobotId.NOMULUS));
    overrides.put(Environment.USER, credential.login());
    overrides.put(Environment.PASS, credential.password());
    // Override the default minimum which is tuned for the Registry server. A worker VM should
    // release all connections if it no longer interacts with the database.
    overrides.put(HIKARI_MINIMUM_IDLE, "0");
    /**
     * Disable Hikari's maxPoolSize limit check by setting it to an absurdly large number. The
     * effective (and desirable) limit is the number of pipeline threads on the pipeline worker,
     * which can be configured using pipeline options. See {@link RegistryPipelineOptions} for more
     * information.
     */
    overrides.put(HIKARI_MAXIMUM_POOL_SIZE, String.valueOf(Integer.MAX_VALUE));
    instanceConnectionNameOverride
        .map(Provider::get)
        .ifPresent(
            instanceConnectionName ->
                overrides.put(HIKARI_DS_CLOUD_SQL_INSTANCE, instanceConnectionName));
    isolationOverride
        .map(Provider::get)
        .ifPresent(isolation -> overrides.put(Environment.ISOLATION, isolation.name()));
    return new JpaTransactionManagerImpl(create(overrides), clock);
  }

  @Provides
  @Singleton
  @NomulusToolJpaTm
  static JpaTransactionManager provideNomulusToolJpaTm(
      @Config("toolsCloudSqlUsername") String username,
      KmsKeyring kmsKeyring,
      SqlCredentialStore credentialStore,
      @PartialCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs,
      @CloudSqlClientCredential Credential credential,
      Clock clock) {
    CloudSqlCredentialSupplier.setupCredentialSupplier(credential);
    HashMap<String, String> overrides = Maps.newHashMap(cloudSqlConfigs);
    validateAndSetCredential(
        credentialStore,
        new RobotUser(RobotId.TOOL),
        overrides,
        username,
        kmsKeyring.getToolsCloudSqlPassword());
    return new JpaTransactionManagerImpl(create(overrides), clock);
  }

  @Provides
  @Singleton
  @SocketFactoryJpaTm
  static JpaTransactionManager provideSocketFactoryJpaTm(
      SqlCredentialStore credentialStore,
      @Config("beamCloudSqlUsername") String username,
      @Config("beamCloudSqlPassword") String password,
      @Config("beamHibernateHikariMaximumPoolSize") int hikariMaximumPoolSize,
      @BeamPipelineCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs,
      Clock clock) {
    HashMap<String, String> overrides = Maps.newHashMap(cloudSqlConfigs);
    overrides.put(HIKARI_MAXIMUM_POOL_SIZE, String.valueOf(hikariMaximumPoolSize));
    overrides.put(Environment.USER, username);
    overrides.put(Environment.PASS, password);
    // TODO(b/175700623): consider assigning different logins to pipelines
    // TODO(b/179839014): Make SqlCredentialStore injectable in BEAM
    // Note: the logs below appear in the pipeline's Worker logs, not the Job log.
    try {
      SqlCredential credential = credentialStore.getCredential(new RobotUser(RobotId.NOMULUS));
      if (!Objects.equals(username, credential.login())) {
        logger.atWarning().log(
            "Wrong username for nomulus. Expecting %s, found %s.", username, credential.login());
      } else if (!Objects.equals(password, credential.password())) {
        logger.atWarning().log("Wrong password for nomulus.");
      } else {
        logger.atWarning().log("Credentials in the kerying and the secret manager match.");
      }
    } catch (Exception e) {
      logger.atWarning().withCause(e).log("Failed to get SQL credential from Secret Manager.");
    }
    return new JpaTransactionManagerImpl(create(overrides), clock);
  }

  @Provides
  @Singleton
  @JdbcJpaTm
  static JpaTransactionManager provideLocalJpaTm(
      @Config("beamCloudSqlJdbcUrl") String jdbcUrl,
      @Config("beamCloudSqlUsername") String username,
      @Config("beamCloudSqlPassword") String password,
      @DefaultHibernateConfigs ImmutableMap<String, String> defaultConfigs,
      Clock clock) {
    HashMap<String, String> overrides = Maps.newHashMap(defaultConfigs);
    overrides.put(Environment.URL, jdbcUrl);
    overrides.put(Environment.USER, username);
    overrides.put(Environment.PASS, password);
    return new JpaTransactionManagerImpl(create(overrides), clock);
  }

  /** Constructs the {@link EntityManagerFactory} instance. */
  @VisibleForTesting
  static EntityManagerFactory create(
      String jdbcUrl, String username, String password, ImmutableMap<String, String> configs) {
    HashMap<String, String> properties = Maps.newHashMap(configs);
    properties.put(Environment.URL, jdbcUrl);
    properties.put(Environment.USER, username);
    properties.put(Environment.PASS, password);

    return create(ImmutableMap.copyOf(properties));
  }

  private static EntityManagerFactory create(Map<String, String> properties) {
    // If there are no annotated classes, we can create the EntityManagerFactory from the generic
    // method.  Otherwise we have to use a more tailored approach.  Note that this adds to the set
    // of annotated classes defined in the configuration, it does not override them.
    EntityManagerFactory emf =
        Persistence.createEntityManagerFactory(
            PERSISTENCE_UNIT_NAME, ImmutableMap.copyOf(properties));
    checkState(
        emf != null,
        "Persistence.createEntityManagerFactory() returns a null EntityManagerFactory");
    return emf;
  }

  /**
   * Verifies that the credential from the Secret Manager matches the one currently in use, and
   * configures JPA with the credential from the Secret Manager.
   *
   * <p>This is a helper for the transition to the Secret Manager, and will be removed once data and
   * permissions are properly set up for all projects.
   */
  private static void validateAndSetCredential(
      SqlCredentialStore credentialStore,
      SqlUser sqlUser,
      Map<String, String> overrides,
      String expectedLogin,
      String expectedPassword) {
    try {
      SqlCredential credential = credentialStore.getCredential(sqlUser);
      checkState(
          credential.login().equals(expectedLogin),
          "Wrong login for %s. Expecting %s, found %s.",
          sqlUser.geUserName(),
          expectedLogin,
          credential.login());
      checkState(
          credential.password().equals(expectedPassword),
          "Wrong password for %s.",
          sqlUser.geUserName());
      overrides.put(Environment.USER, credential.login());
      overrides.put(Environment.PASS, credential.password());
      logger.atWarning().log("Credentials in the kerying and the secret manager match.");
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log("Failed to get SQL credential from Secret Manager");
    }
  }

  /**
   * Transaction isolation levels supported by Cloud SQL (mysql and postgresql).
   *
   * <p>Enum names may be used for property-based configuration, and must match the corresponding
   * variable names in {@link Connection}.
   */
  public enum TransactionIsolationLevel {
    TRANSACTION_READ_UNCOMMITTED,
    TRANSACTION_READ_COMMITTED,
    TRANSACTION_REPEATABLE_READ,
    TRANSACTION_SERIALIZABLE;

    private final int value;

    TransactionIsolationLevel() {
      try {
        // name() is final in parent class (Enum.java), therefore safe to call in constructor.
        value = Connection.class.getField(name()).getInt(null);
      } catch (Exception e) {
        throw new IllegalStateException(
            String.format(
                "%s Enum name %s has no matching public field in java.sql.Connection.",
                getClass().getSimpleName(), name()));
      }
    }

    public final int getValue() {
      return value;
    }
  }

  /** Dagger qualifier for JDBC {@link Connection} with schema management privilege. */
  @Qualifier
  @Documented
  public @interface SchemaManagerConnection {}

  /** Dagger qualifier for {@link JpaTransactionManager} used for App Engine application. */
  @Qualifier
  @Documented
  @interface AppEngineJpaTm {}

  /** Dagger qualifier for {@link JpaTransactionManager} used inside BEAM pipelines. */
  // Note: @SocketFactoryJpaTm will be phased out in favor of this qualifier.
  @Qualifier
  @Documented
  public @interface BeamJpaTm {}

  /** Dagger qualifier for {@link JpaTransactionManager} used for Nomulus tool. */
  @Qualifier
  @Documented
  public @interface NomulusToolJpaTm {}

  /**
   * Dagger qualifier for {@link JpaTransactionManager} that accesses Cloud SQL using socket
   * factory. This is meant for applications not running on AppEngine, therefore without access to a
   * {@link google.registry.keyring.api.Keyring}.
   */
  @Qualifier
  @Documented
  public @interface SocketFactoryJpaTm {}

  /**
   * Dagger qualifier for {@link JpaTransactionManager} backed by plain JDBC connections. This is
   * mainly used by tests.
   */
  @Qualifier
  @Documented
  public @interface JdbcJpaTm {}

  /** Dagger qualifier for the partial Cloud SQL configs. */
  @Qualifier
  @Documented
  @interface PartialCloudSqlConfigs {}

  /** Dagger qualifier for the Cloud SQL configs used by Beam pipelines. */
  @Qualifier
  @Documented
  @interface BeamPipelineCloudSqlConfigs {}

  /** Dagger qualifier for the default Hibernate configurations. */
  // TODO(b/177568911): Change annotations in this class to none public or put them in a top level
  // package.
  @Qualifier
  @Documented
  public @interface DefaultHibernateConfigs {}
}
