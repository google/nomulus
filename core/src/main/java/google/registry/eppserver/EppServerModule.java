// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.eppserver;

import com.google.api.services.cloudkms.v1.CloudKMS;
import com.google.api.services.cloudkms.v1.model.DecryptRequest;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.google.common.base.Suppliers;
import com.google.monitoring.metrics.MetricReporter;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.cache.CacheModule;
import google.registry.config.CredentialModule;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.eppserver.EppProtocolModule.EppProtocol;
import google.registry.eppserver.HealthCheckProtocolModule.HealthCheckProtocol;
import google.registry.eppserver.Protocol.FrontendProtocol;
import google.registry.keyring.KeyringModule;
import google.registry.keyring.api.KeyModule;
import google.registry.networking.module.CertificateSupplierModule;
import google.registry.networking.module.CertificateSupplierModule.Mode;
import google.registry.privileges.secretmanager.SecretManagerModule;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.OidcTokenUtils;
import google.registry.util.RegistryEnvironment;
import google.registry.util.UtilsModule;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslProvider;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import redis.clients.jedis.UnifiedJedis;

/**
 * A module that provides the port-to-protocol map and other configs that are used to bootstrap the
 * server.
 */
@Module
public class EppServerModule {

  @Provides
  @EppProtocol
  static int provideEppPort(@Config("eppServerPort") int eppPort) {
    return eppPort;
  }

  @Provides
  @HealthCheckProtocol
  static int provideHealthCheckPort(@Config("eppServerHealthCheckPort") int healthCheckPort) {
    return healthCheckPort;
  }

  @Singleton
  @Provides
  LoggingHandler provideLoggingHandler() {
    return new LoggingHandler(LogLevel.DEBUG);
  }

  @Singleton
  @Provides
  static Supplier<GoogleCredentials> provideRefreshedCredentialsSupplier(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle) {
    return () -> {
      GoogleCredentials credentials = credentialsBundle.getGoogleCredentials();
      try {
        credentials.refreshIfExpired();
      } catch (IOException e) {
        throw new RuntimeException("Cannot refresh credentials.", e);
      }
      return credentials;
    };
  }

  @Singleton
  @Provides
  @Named("idToken")
  static Supplier<String> provideOidcToken(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("oauthClientId") String clientId) {
    return Suppliers.memoizeWithExpiration(
        () -> OidcTokenUtils.createOidcToken(credentialsBundle, clientId), 1, TimeUnit.HOURS);
  }

  @Singleton
  @Provides
  @Named("canary")
  static boolean provideIsCanary() {
    return RegistryEnvironment.get().name().endsWith("_CANARY");
  }

  @Singleton
  @Provides
  static CloudKMS provideCloudKms(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("projectId") String projectId) {
    return new CloudKMS.Builder(
            credentialsBundle.getHttpTransport(),
            credentialsBundle.getJsonFactory(),
            credentialsBundle.getHttpRequestInitializer())
        .setApplicationName(projectId)
        .build();
  }

  @Singleton
  @Provides
  static Storage provideStorage(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle) {
    return StorageOptions.newBuilder()
        .setTransportOptions(
            HttpTransportOptions.newBuilder()
                .setHttpTransportFactory(credentialsBundle::getHttpTransport)
                .build())
        .setCredentials(credentialsBundle.getGoogleCredentials())
        .build()
        .getService();
  }

  @Provides
  @Named("encryptedPemBytes")
  static byte[] provideEncryptedPemBytes(
      Storage storage,
      @Config("eppServerSslPemBucket") String bucket,
      @Config("eppServerSslPemFilename") String sslPemFilename) {
    try {
      return Base64.getMimeDecoder()
          .decode(storage.readAllBytes(BlobId.of(bucket, sslPemFilename)));
    } catch (StorageException e) {
      throw new RuntimeException(
          String.format(
              "Error reading encrypted PEM file %s from GCS bucket %s", sslPemFilename, bucket),
          e);
    }
  }

  @Provides
  @Named("pemBytes")
  static byte[] providePemBytes(
      CloudKMS cloudKms,
      @Named("encryptedPemBytes") byte[] encryptedPemBytes,
      @Config("projectId") String projectId,
      @Config("eppServerKmsLocation") String location,
      @Config("eppServerKmsKeyRing") String keyRing,
      @Config("eppServerKmsCryptoKey") String cryptoKey) {
    String cryptoKeyUrl =
        String.format(
            "projects/%s/locations/%s/keyRings/%s/cryptoKeys/%s",
            projectId, location, keyRing, cryptoKey);
    try {
      DecryptRequest decryptRequest = new DecryptRequest().encodeCiphertext(encryptedPemBytes);
      return cloudKms
          .projects()
          .locations()
          .keyRings()
          .cryptoKeys()
          .decrypt(cryptoKeyUrl, decryptRequest)
          .execute()
          .decodePlaintext();
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("PEM file decryption failed using CryptoKey: %s", cryptoKeyUrl), e);
    }
  }

  @Provides
  static SslProvider provideSslProvider() {
    return OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;
  }

  @Provides
  static ExecutorService provideExecutorService() {
    return Executors.newWorkStealingPool();
  }

  @Provides
  static ScheduledExecutorService provideScheduledExecutorService() {
    return Executors.newSingleThreadScheduledExecutor();
  }

  @Singleton
  @Provides
  static Mode provideMode() {
    if (RegistryEnvironment.get() == RegistryEnvironment.LOCAL) {
      return Mode.SELF_SIGNED;
    } else {
      return Mode.PEM_FILE;
    }
  }

  @Singleton
  @Provides
  @Named("remoteCertCachingDuration")
  static Duration provideCertCachingDuration(
      @Config("eppServerCertificateCacheSeconds") int cacheSeconds) {
    return Duration.ofSeconds(cacheSeconds);
  }

  @Singleton
  @Provides
  @Named("frontendMetricsRatio")
  static double provideFrontendMetricsRatio(@Config("eppServerFrontendMetricsRatio") double ratio) {
    return ratio;
  }

  /** Root level component that exposes the port-to-protocol map. */
  @Singleton
  @Component(
      modules = {
        EppServerModule.class,
        CacheModule.class,
        CertificateSupplierModule.class,
        ConfigModule.class,
        EppProtocolModule.class,
        HealthCheckProtocolModule.class,
        MetricsModule.class,
        CredentialModule.class,
        KeyModule.class,
        KeyringModule.class,
        SecretManagerModule.class,
        UtilsModule.class
      })
  public interface EppServerComponent {
    Set<FrontendProtocol> protocols();

    MetricReporter metricReporter();

    Optional<UnifiedJedis> jedis();
  }
}
