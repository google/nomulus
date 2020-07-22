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
import dagger.Component;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.keyring.kms.KmsModule;
import google.registry.persistence.PersistenceModule;
import google.registry.persistence.PersistenceModule.JdbcJpaTm;
import google.registry.persistence.PersistenceModule.SocketFactoryJpaTm;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.util.UtilsModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.options.PipelineOptionsFactory;

/**
 * Provides bindings for {@link JpaTransactionManager} to Cloud SQL.
 *
 * <p>This module is intended for use in BEAM pipelines, and uses a BEAM utility to access GCS like
 * a regular file system.
 */
@Module
public class BeamJpaModule {

  private static final String GCS_SCHEME = "gs://";

  @Nullable private final String credentialFilePath;

  /**
   * Constructs a new instance of {@link BeamJpaModule}.
   *
   * <p>Note: it is an unfortunately necessary antipattern to check for the validity of
   * credentialFilePath in {@link #provideCloudSqlAccessInfo} rather than in the constructor.
   * Unfortunately, this is a restriction imposed upon us by Dagger. Specifically, because we use
   * this in at least one 1 {@link google.registry.tools.RegistryTool} command(s), it must be
   * instantiated in {@code google.registry.tools.RegistryToolComponent} for all possible commands;
   * Dagger doesn't permit it to ever be null. For the vast majority of commands, it will never be
   * used (so a null credential file path is fine in those cases).
   *
   * @param credentialFilePath the path to a Cloud SQL credential file. This must refer to either a
   *     real encrypted file on GCS as returned by {@link
   *     BackupPaths#getCloudSQLCredentialFilePatterns} or an unencrypted file on local filesystem
   *     with credentials to a test database.
   */
  public BeamJpaModule(@Nullable String credentialFilePath) {
    this.credentialFilePath = credentialFilePath;
  }

  /** Returns true if the credential file is on GCS (and therefore expected to be encrypted). */
  private boolean isCloudSqlCredential() {
    return credentialFilePath.startsWith(GCS_SCHEME);
  }

  @Provides
  @Singleton
  SqlAccessInfo provideCloudSqlAccessInfo(Lazy<CloudSqlCredentialDecryptor> lazyDecryptor) {
    checkArgument(!isNullOrEmpty(credentialFilePath), "Null or empty credentialFilePath");
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
    FileSystems.setDefaultPipelineOptions(PipelineOptionsFactory.create());
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
  @Config("beamCloudSqlJdbcUrl")
  String provideJdbcUrl(SqlAccessInfo sqlAccessInfo) {
    return sqlAccessInfo.jdbcUrl();
  }

  @Provides
  @Config("beamCloudSqlInstanceConnectionName")
  String provideSqlInstanceName(SqlAccessInfo sqlAccessInfo) {
    return sqlAccessInfo
        .cloudSqlInstanceName()
        .orElseThrow(() -> new IllegalStateException("Cloud SQL not provisioned."));
  }

  @Provides
  @Config("beamCloudSqlUsername")
  String provideSqlUsername(SqlAccessInfo sqlAccessInfo) {
    return sqlAccessInfo.user();
  }

  @Provides
  @Config("beamCloudSqlPassword")
  String provideSqlPassword(SqlAccessInfo sqlAccessInfo) {
    return sqlAccessInfo.password();
  }

  @Provides
  @Config("beamCloudKmsProjectId")
  static String kmsProjectId() {
    return "domain-registry-dev";
  }

  @Provides
  @Config("beamCloudKmsKeyRing")
  static String keyRingName() {
    return "nomulus-tool-keyring";
  }

  @Provides
  @Config("beamHibernateHikariMaximumPoolSize")
  static int getBeamHibernateHikariMaximumPoolSize() {
    // TODO(weiminyu): make this configurable. Should be equal to number of cores.
    return 4;
  }

  @Singleton
  @Component(
      modules = {
        ConfigModule.class,
        CredentialModule.class,
        BeamJpaModule.class,
        KmsModule.class,
        PersistenceModule.class,
        UtilsModule.class
      })
  public interface JpaTransactionManagerComponent {
    @SocketFactoryJpaTm
    JpaTransactionManager cloudSqlJpaTransactionManager();

    @JdbcJpaTm
    JpaTransactionManager localDbJpaTransactionManager();
  }
}
