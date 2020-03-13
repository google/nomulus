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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.kms.KmsKeyring;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.JpaTransactionManagerImpl;
import google.registry.util.Clock;
import java.lang.annotation.Documented;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import org.hibernate.cfg.Environment;

/** Dagger module class for the persistence layer. */
@Module
public class PersistenceModule {
  // This name must be the same as the one defined in persistence.xml.
  public static final String PERSISTENCE_UNIT_NAME = "nomulus";
  public static final String HIKARI_CONNECTION_TIMEOUT = "hibernate.hikari.connectionTimeout";
  public static final String HIKARI_MINIMUM_IDLE = "hibernate.hikari.minimumIdle";
  public static final String HIKARI_MAXIMUM_POOL_SIZE = "hibernate.hikari.maximumPoolSize";
  public static final String HIKARI_IDLE_TIMEOUT = "hibernate.hikari.idleTimeout";

  public static final String HIKARI_DS_SOCKET_FACTORY = "hibernate.hikari.dataSource.socketFactory";
  public static final String HIKARI_DS_CLOUD_SQL_INSTANCE =
      "hibernate.hikari.dataSource.cloudSqlInstance";

  @Provides
  @DefaultHibernateConfigs
  public static ImmutableMap<String, String> providesDefaultDatabaseConfigs() {
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
  public static ImmutableMap<String, String> providesPartialCloudSqlConfigs(
      @Config("cloudSqlJdbcUrl") String jdbcUrl,
      @Config("cloudSqlInstanceConnectionName") String instanceConnectionName,
      @DefaultHibernateConfigs ImmutableMap<String, String> defaultConfigs) {
    HashMap<String, String> overrides = Maps.newHashMap(defaultConfigs);
    overrides.put(Environment.URL, jdbcUrl);
    overrides.put(HIKARI_DS_SOCKET_FACTORY, "com.google.cloud.sql.postgres.SocketFactory");
    overrides.put(HIKARI_DS_CLOUD_SQL_INSTANCE, instanceConnectionName);
    return ImmutableMap.copyOf(overrides);
  }

  @Provides
  @Singleton
  @AppEngineJpaTm
  public static JpaTransactionManager providesAppEngineJpaTm(
      @Config("cloudSqlUsername") String username,
      KmsKeyring kmsKeyring,
      @PartialCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs,
      Clock clock) {
    HashMap<String, String> overrides = Maps.newHashMap(cloudSqlConfigs);
    overrides.put(Environment.USER, username);
    overrides.put(Environment.PASS, kmsKeyring.getCloudSqlPassword());
    return new JpaTransactionManagerImpl(create(overrides), clock);
  }

  @Provides
  @Singleton
  @NomulusToolJpaTm
  public static JpaTransactionManager providesNomulusToolJpaTm(
      @Config("toolsCloudSqlUsername") String username,
      KmsKeyring kmsKeyring,
      @PartialCloudSqlConfigs ImmutableMap<String, String> cloudSqlConfigs,
      Clock clock) {
    HashMap<String, String> overrides = Maps.newHashMap(cloudSqlConfigs);
    overrides.put(Environment.USER, username);
    overrides.put(Environment.PASS, kmsKeyring.getToolsCloudSqlPassword());
    return new JpaTransactionManagerImpl(create(overrides), clock);
  }

  /** Constructs the {@link EntityManagerFactory} instance. */
  @VisibleForTesting
  public static EntityManagerFactory create(
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

  /** Dagger qualifier for {@link JpaTransactionManager} used for App Engine application. */
  @Qualifier
  @Documented
  @interface AppEngineJpaTm {}

  /** Dagger qualifier for {@link JpaTransactionManager} used for Nomulus tool. */
  @Qualifier
  @Documented
  public @interface NomulusToolJpaTm {}

  /** Dagger qualifier for the partial Cloud SQL configs. */
  @Qualifier
  @Documented
  @interface PartialCloudSqlConfigs {}

  /** Dagger qualifier for the default Hibernate configurations. */
  // TODO(shicong): Change annotations in this class to none public or put them in a top level
  //  package
  @Qualifier
  @Documented
  public @interface DefaultHibernateConfigs {}
}
