// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.cache;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.EppResource;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import google.registry.util.Clock;
import google.registry.util.GoogleCredentialsBundle;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.RedisClusterClient;
import redis.clients.jedis.UnifiedJedis;

/** Dagger module to provide the {@link Jedis}-based cache for Valkey. */
@Module
public final class CacheModule {

  @Provides
  @Singleton
  public static Optional<UnifiedJedis> provideJedis(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("valkeyHostsAndPorts") Optional<ImmutableList<String>> valkeyHostsAndPorts,
      @Config("valkeySslSocketFactory") SSLSocketFactory valkeySslSocketFactory) {
    if (valkeyHostsAndPorts.map(ImmutableList::isEmpty).orElse(true)) {
      return Optional.empty();
    }
    ImmutableSet<HostAndPort> hostsAndPorts =
        valkeyHostsAndPorts.get().stream().map(HostAndPort::from).collect(toImmutableSet());
    JedisClientConfig clientConfig =
        DefaultJedisClientConfig.builder()
            .ssl(true)
            .sslSocketFactory(valkeySslSocketFactory)
            .credentialsProvider(new ValkeyCredentialsProvider(credentialsBundle))
            .build();
    if (hostsAndPorts.size() > 1) {
      return Optional.of(
          RedisClusterClient.builder().clientConfig(clientConfig).nodes(hostsAndPorts).build());
    }
    return Optional.of(
        RedisClient.builder()
            .clientConfig(clientConfig)
            .hostAndPort(Iterables.getOnlyElement(hostsAndPorts))
            .build());
  }

  @Provides
  @Singleton
  public static Optional<SimplifiedJedisClient> provideJedisClient(Optional<UnifiedJedis> jedis) {
    return jedis.map(SimplifiedJedisClient::new);
  }

  @Provides
  @Singleton
  public static DomainCache provideDomainCache(
      Optional<SimplifiedJedisClient> jedisClient, Clock clock, CacheMetrics cacheMetrics) {
    if (jedisClient.isEmpty()) {
      return domainName ->
          ForeignKeyUtils.loadResourceByCache(Domain.class, domainName, clock.now());
    }
    return new MultilayerDomainCache(jedisClient.get(), clock, cacheMetrics);
  }

  @Provides
  @Singleton
  public static HostCache provideHostCache(
      Optional<SimplifiedJedisClient> jedisClient, CacheMetrics cacheMetrics) {
    if (jedisClient.isEmpty()) {
      return repoId ->
          Optional.ofNullable(EppResource.loadByCache(VKey.create(Host.class, repoId)));
    }
    return new MultilayerHostCache(jedisClient.get(), cacheMetrics);
  }

  @Provides
  @Singleton
  @Config("valkeySslSocketFactory")
  static SSLSocketFactory provideValkeySslSocketFactory(
      @Config("valkeyCertificateAuthority") String valkeyCertificateAuthority) {
    try {
      ImmutableList<X509Certificate> trustedCerts =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(
                  new ByteArrayInputStream(
                      valkeyCertificateAuthority.getBytes(StandardCharsets.UTF_8)))
              .stream()
              .map(X509Certificate.class::cast)
              .collect(toImmutableList());

      // This is a roundabout way to trust the Cloud Memorystore-issued certificate authority even
      // though it's not a root cert (it's an intermediate cert).
      TrustManager x509TrustManager =
          new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return trustedCerts.toArray(new X509Certificate[0]);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] certs, String authType)
                throws CertificateException {
              Exception lastException = null;
              for (X509Certificate cert : certs) {
                for (X509Certificate trustedCert : trustedCerts) {
                  try {
                    cert.verify(trustedCert.getPublicKey());
                    return;
                  } catch (Exception e) {
                    // Verification failed, try the next one
                    lastException = e;
                  }
                }
              }
              throw new CertificateException(
                  "None of the server certificates were signed by the provided CA", lastException);
            }
          };

      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, new TrustManager[] {x509TrustManager}, null);
      return sslContext.getSocketFactory();
    } catch (GeneralSecurityException e) {
      throw new RuntimeException("Could not create X.509 certificate from provided PEM", e);
    }
  }
}
