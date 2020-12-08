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

package google.registry.persistence;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.beam.initsql.BeamJpaModule;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.keyring.kms.KmsModule;
import google.registry.persistence.PersistenceModule.DefaultHibernateConfigs;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.JpaTransactionManagerImpl;
import google.registry.privileges.secretmanager.SecretManagerModule;
import google.registry.util.Clock;
import google.registry.util.UtilsModule;
import java.lang.annotation.Documented;
import java.util.HashMap;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import org.hibernate.cfg.Environment;

/** Dagger module class for injecting local persistence units for testing e.g. Beam pipelines. */
@Module
public class PersistenceTestModule {

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
    return new JpaTransactionManagerImpl(PersistenceModule.create(overrides), clock);
  }

  /**
   * Dagger qualifier for {@link JpaTransactionManager} backed by plain JDBC connections. This is
   * mainly used by tests.
   */
  @Qualifier
  @Documented
  public @interface JdbcJpaTm {}

  @Singleton
  @Component(
      modules = {
        ConfigModule.class,
        CredentialModule.class,
        BeamJpaModule.class,
        KmsModule.class,
        PersistenceModule.class,
        PersistenceTestModule.class,
        SecretManagerModule.class,
        UtilsModule.class
      })
  public interface JpaTransactionManagerTestComponent {
    @JdbcJpaTm
    JpaTransactionManager jpaTransactionManager();
  }

  public static JpaTransactionManager testJpaTransactionManager(BeamJpaModule beamJpaModule) {
    return DaggerPersistenceTestModule_JpaTransactionManagerTestComponent.builder()
        .beamJpaModule(beamJpaModule)
        .build()
        .jpaTransactionManager();
  }
}
