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

package google.registry.beam.initsql;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import dagger.Binds;
import dagger.Component;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import google.registry.beam.initsql.BeamJpaModule.BindModule;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.kms.KmsModule;
import google.registry.persistence.PersistenceModule;
import google.registry.persistence.PersistenceModule.JdbcJpaTm;
import google.registry.persistence.PersistenceModule.SocketFactoryJpaTm;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.util.Clock;
import google.registry.util.Sleeper;
import google.registry.util.SystemClock;
import google.registry.util.SystemSleeper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.ResourceId;

/**
 * Provides bindings for {@link JpaTransactionManager} to Cloud SQL.
 *
 * <p>This module is intended for use in BEAM pipelines, and uses a BEAM utility to access GCS like
 * a regular file system.
 *
 * <p>Note that {@link google.registry.config.RegistryConfig.ConfigModule} cannot be used here,
 * since many bindings, especially KMS-related ones, are different.
 */
@Module(includes = {BindModule.class})
class BeamJpaModule {

  private static final String GCS_SCHEME = "gs://";

  private final String credentialFilePath;

  BeamJpaModule(String credentialFilePath) {
    checkArgument(!isNullOrEmpty(credentialFilePath), "Null or empty credentialFilePath");
    this.credentialFilePath = credentialFilePath;
  }

  private boolean isCloudSqlCredential() {
    return credentialFilePath.startsWith(GCS_SCHEME);
  }

  @Provides
  @Singleton
  SqlAccessInfo provideCloudSqlAccessInfo(Lazy<CloudSqlCredentialDecryptor> lazyDecryptor) {
    String line = readOnlyLineFromCredentialFile();
    if (isCloudSqlCredential()) {
      line = lazyDecryptor.get().decrypt(line);
    }
    // See ./BackupPaths.java for explanation of the line format.
    List<String> parts = Splitter.on(' ').splitToList(line.trim());
    checkState(parts.size() == 3, "Expecting three phrases in %s", line);
    if (isCloudSqlCredential()) {
      return SqlAccessInfo.createCloudSqlAccessInfo(parts.get(0), parts.get(1), parts.get(2));
    } else {
      return SqlAccessInfo.createLocalSqlAccessInfo(parts.get(0), parts.get(1), parts.get(2));
    }
  }

  String readOnlyLineFromCredentialFile() {
    try {
      ResourceId resourceId = FileSystems.matchSingleFileSpec(credentialFilePath).resourceId();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(
                  Channels.newInputStream(FileSystems.open(resourceId)), StandardCharsets.UTF_8))) {
        return reader.readLine();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  @Config("cloudSqlJdbcUrl")
  String provideJdbcUrl(SqlAccessInfo sqlAccessInfo) {
    return sqlAccessInfo.jdbcUrl();
  }

  @Provides
  @Config("cloudSqlInstanceConnectionName")
  String provideSqlInstanceName(SqlAccessInfo sqlAccessInfo) {
    return sqlAccessInfo
        .cloudSqlInstanceName()
        .orElseThrow(() -> new IllegalStateException("Cloud SQL not provisioned."));
  }

  @Provides
  @Config("cloudSqlUsername")
  String provideSqlUsername(SqlAccessInfo sqlAccessInfo) {
    return sqlAccessInfo.user();
  }

  @Provides
  @Config("cloudSqlPassword")
  String provideSqlPassword(SqlAccessInfo sqlAccessInfo) {
    return sqlAccessInfo.password();
  }

  @Provides
  @Config("cloudKmsProjectId")
  static String kmsProjectId() {
    return "domain-registry-dev";
  }

  @Provides
  @Config("cloudKmsKeyRing")
  static String keyRingName() {
    return "nomulus-tool-keyring";
  }

  @Provides
  @Config("defaultCredentialOauthScopes")
  static ImmutableList<String> defaultCredentialOauthScopes() {
    return ImmutableList.of("https://www.googleapis.com/auth/cloud-platform");
  }

  @Provides
  @Named("transientFailureRetries")
  static int transientFailureRetries() {
    return 12;
  }

  @Module
  interface BindModule {

    @Binds
    Sleeper sleeper(SystemSleeper sleeper);

    @Binds
    Clock clock(SystemClock clock);
  }

  @Singleton
  @Component(
      modules = {
        CredentialModule.class,
        BeamJpaModule.class,
        KmsModule.class,
        PersistenceModule.class
      })
  public interface JpaTransactionManagerComponent {
    @SocketFactoryJpaTm
    JpaTransactionManager cloudSqlJpaTransactionManager();

    @JdbcJpaTm
    JpaTransactionManager localDbJpaTransactionManager();
  }
}
