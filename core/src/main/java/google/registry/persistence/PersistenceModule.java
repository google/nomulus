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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.api.client.auth.oauth2.Credential;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.persistence.transaction.CloudSqlCredentialSupplier;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.JpaTransactionManagerImpl;
import google.registry.persistence.transaction.TransactionManager;
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

  /**
   * Postgresql-specific: driver default fetch size is 0, which disables streaming result sets. Here
   * we set a small default geared toward Nomulus server transactions. Large queries can override
   * the defaults using {@link JpaTransactionManager#setQueryFetchSize}.
   */
  public static final String JDBC_FETCH_SIZE = "hibernate.jdbc.fetch_size";

  private static final int DEFAULT_SERVER_FETCH_SIZE = 20;

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
    properties.put(JDBC_FETCH_SIZE, Integer.toString(DEFAULT_SERVER_FETCH_SIZE));
    return properties.build();
  }

  @Provides
  @Singleton
  @PartialCloudSqlConfigs
  static ImmutableMap<String, String> providePartialCloudSqlConfigs(
      @Config("cloudSqlJdbcUrl") String jdbcUrl,
      @Config("cloudSqlInstanceConnectionName") String instanceConnectionName,
      @DefaultHibernateConfigs ImmutableMap<String, String> defaultConfigs) {
    HashMap<String, String> overrides = Maps.newHashMap(defaultConfigs);
    overrides.put(Environment.URL, jdbcUrl);
    overrides.put(HIKARI_DS_SOCKET_FACTORY, "com.google.cloud.sql.postgres.SocketFactory");
    overrides.put(HIKARI_DS_CLOUD_SQL_INSTANCE, instanceConnectionName);
    return ImmutableMap.copyOf(overrides);
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
      SqlCredentialStore credentialStore,
      @Config("instanceConnectionNameOverride")
          Optional<Provider<String>> instanceConnectionNameOverride,
      @Config("beamIsolationOverride")
          Optional<Provider<TransactionIsolationLevel>> isolationOverride,
      @PartialCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs) {
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
  static TransactionManager provideTransactionManager() {
    return tm();
  }

  @Provides
  @Singleton
  @AppEngineJpaTm
  static JpaTransactionManager provideAppEngineJpaTm(
      SqlCredentialStore credentialStore,
      @PartialCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs,
      Clock clock) {
    HashMap<String, String> overrides = Maps.newHashMap(cloudSqlConfigs);
    setSqlCredential(credentialStore, new RobotUser(RobotId.NOMULUS), overrides);
    return new JpaTransactionManagerImpl(create(overrides), clock);
  }

  @Provides
  @Singleton
  @BeamJpaTm
  static JpaTransactionManager provideBeamJpaTm(
      @BeamPipelineCloudSqlConfigs ImmutableMap<String, String> beamCloudSqlConfigs, Clock clock) {
    return new JpaTransactionManagerImpl(create(beamCloudSqlConfigs), clock);
  }

  @Provides
  @Singleton
  @BeamBulkQueryJpaTm
  static JpaTransactionManager provideBeamBulkQueryJpaTm(
      @BeamPipelineCloudSqlConfigs ImmutableMap<String, String> beamCloudSqlConfigs, Clock clock) {
    return new JpaTransactionManagerImpl(
        BulkQueryJpaFactory.createBulkQueryEntityManagerFactory(beamCloudSqlConfigs), clock);
  }

  @Provides
  @Singleton
  @NomulusToolJpaTm
  static JpaTransactionManager provideNomulusToolJpaTm(
      SqlCredentialStore credentialStore,
      @PartialCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs,
      @CloudSqlClientCredential Credential credential,
      Clock clock) {
    CloudSqlCredentialSupplier.setupCredentialSupplier(credential);
    HashMap<String, String> overrides = Maps.newHashMap(cloudSqlConfigs);
    setSqlCredential(credentialStore, new RobotUser(RobotId.TOOL), overrides);
    return new JpaTransactionManagerImpl(create(overrides), clock);
  }

  @Provides
  @Singleton
  @ReadOnlyReplicaJpaTm
  static JpaTransactionManager provideReadOnlyReplicaJpaTm(
      SqlCredentialStore credentialStore,
      @PartialCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs,
      @Config("cloudSqlReplicaInstanceConnectionName")
          Optional<String> replicaInstanceConnectionName,
      Clock clock) {
    HashMap<String, String> overrides = Maps.newHashMap(cloudSqlConfigs);
    setSqlCredential(credentialStore, new RobotUser(RobotId.NOMULUS), overrides);
    replicaInstanceConnectionName.ifPresent(
        name -> overrides.put(HIKARI_DS_CLOUD_SQL_INSTANCE, name));
    return new JpaTransactionManagerImpl(create(overrides), clock);
  }

  @Provides
  @Singleton
  @BeamReadOnlyReplicaJpaTm
  static JpaTransactionManager provideBeamReadOnlyReplicaJpaTm(
      @BeamPipelineCloudSqlConfigs ImmutableMap<String, String> beamCloudSqlConfigs,
      @Config("cloudSqlReplicaInstanceConnectionName")
          Optional<String> replicaInstanceConnectionName,
      Clock clock) {
    HashMap<String, String> overrides = Maps.newHashMap(beamCloudSqlConfigs);
    replicaInstanceConnectionName.ifPresent(
        name -> overrides.put(HIKARI_DS_CLOUD_SQL_INSTANCE, name));
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

  /** Configures JPA with the credential from the Secret Manager. */
  private static void setSqlCredential(
      SqlCredentialStore credentialStore, SqlUser sqlUser, Map<String, String> overrides) {
    try {
      SqlCredential credential = credentialStore.getCredential(sqlUser);
      overrides.put(Environment.USER, credential.login());
      overrides.put(Environment.PASS, credential.password());
    } catch (Throwable e) {
      // TODO(b/184631990): after SQL becomes primary, throw an exception to fail fast
      logger.atSevere().withCause(e).log("Failed to get SQL credential from Secret Manager.");
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

  /** Types of {@link JpaTransactionManager JpaTransactionManagers}. */
  public enum JpaTransactionManagerType {
    /** The regular {@link JpaTransactionManager} for general use. */
    REGULAR,
    /**
     * The {@link JpaTransactionManager} optimized for bulk loading multi-level JPA entities. Please
     * see {@link google.registry.model.bulkquery.BulkQueryEntities} for more information.
     */
    BULK_QUERY,
    /**
     * The {@link JpaTransactionManager} that uses the read-only Postgres replica if configured, or
     * the standard DB if not.
     */
    READ_ONLY_REPLICA
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
  @Qualifier
  @Documented
  public @interface BeamJpaTm {}

  /**
   * Dagger qualifier for {@link JpaTransactionManager} that uses an alternative entity model for
   * faster bulk queries.
   */
  @Qualifier
  @Documented
  public @interface BeamBulkQueryJpaTm {}

  /**
   * Dagger qualifier for {@link JpaTransactionManager} used inside BEAM pipelines that uses the
   * read-only Postgres replica if one is configured (otherwise it uses the standard DB).
   */
  @Qualifier
  @Documented
  public @interface BeamReadOnlyReplicaJpaTm {}

  /**
   * Dagger qualifier for {@link JpaTransactionManager} that uses the read-only Postgres replica if
   * one is configured (otherwise it uses the standard DB).
   */
  @Qualifier
  @Documented
  public @interface ReadOnlyReplicaJpaTm {}

  /** Dagger qualifier for {@link JpaTransactionManager} used for Nomulus tool. */
  @Qualifier
  @Documented
  public @interface NomulusToolJpaTm {}

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
