// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.mosapi.module;

import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.privileges.secretmanager.SecretManagerClient;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

@Module
public final class MosApiModule {

  // Secret Manager constants
  private static final String LATEST_SECRET_VERSION = "latest";

  // @Named annotations for Dagger
  private static final String MOSAPI_TLS_CERT = "mosapiTlsCert";
  private static final String MOSAPI_TLS_KEY = "mosapiTlsKey";
  private static final String MOSAPI_SSL_CONTEXT = "mosapiSslContext";
  private static final String MOSAPI_HTTP_CLIENT = "mosapiHttpClient";

  // Cryptography-related constants
  private static final String CERTIFICATE_TYPE = "X.509";
  private static final String KEY_STORE_TYPE = "PKCS12";
  private static final String KEY_STORE_ALIAS = "client";
  private static final String SSL_CONTEXT_PROTOCOL = "TLS";

  /**
   * Provides a Provider for the MoSAPI TLS Cert.
   *
   * <p>This method returns a Dagger {@link Provider} that can be used to fetch the TLS Certs for a
   * MosAPI.
   *
   * @param secretManagerClient The injected Secret Manager client.
   * @param tlsCertSecretName The name of the secret in Secret Manager (from config).
   * @return A Provider for the MoSAPI TLS Certs.
   */
  @Provides
  @Named(MOSAPI_TLS_CERT)
  public static String provideMosapiTlsCert(
      SecretManagerClient secretManagerClient,
      @Config("mosapiTlsCertSecretName") String tlsCertSecretName) {
    return secretManagerClient.getSecretData(tlsCertSecretName, Optional.of(LATEST_SECRET_VERSION));
  }

  /**
   * Provides a Provider for the MoSAPI TLS Key.
   *
   * <p>This method returns a Dagger {@link Provider} that can be used to fetch the TLS Key for a
   * MosAPI.
   *
   * @param secretManagerClient The injected Secret Manager client.
   * @param tlsKeySecretName The name of the secret in Secret Manager (from config).
   * @return A Provider for the MoSAPI TLS Key.
   */
  @Provides
  @Named(MOSAPI_TLS_KEY)
  public static String provideMosapiTlsKey(
      SecretManagerClient secretManagerClient,
      @Config("mosapiTlsKeySecretName") String tlsKeySecretName) {
    return secretManagerClient.getSecretData(tlsKeySecretName, Optional.of(LATEST_SECRET_VERSION));
  }

  @Provides
  static Certificate provideCertificate(@Named(MOSAPI_TLS_CERT) String tlsCert) {
    try {
      CertificateFactory cf = CertificateFactory.getInstance(CERTIFICATE_TYPE);
      return cf.generateCertificate(
          new ByteArrayInputStream(tlsCert.getBytes(StandardCharsets.UTF_8)));
    } catch (CertificateException e) {
      throw new RuntimeException("Could not create X.509 certificate from provided PEM", e);
    }
  }

  @Provides
  static PrivateKey providePrivateKey(@Named(MOSAPI_TLS_KEY) String tlsKey) {
    try (PEMParser pemParser = new PEMParser(new StringReader(tlsKey))) {
      Object parsedObj = pemParser.readObject();
      JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
      if (parsedObj instanceof PEMKeyPair) {
        return converter.getPrivateKey(((PEMKeyPair) parsedObj).getPrivateKeyInfo());
      } else if (parsedObj instanceof PrivateKeyInfo) {
        return converter.getPrivateKey((PrivateKeyInfo) parsedObj);
      }
      throw new IllegalArgumentException(
          String.format(
              "Could not parse TLS private key; unexpected format %s",
              parsedObj != null ? parsedObj.getClass().getName() : "null"));
    } catch (IOException e) {
      throw new RuntimeException("Could not parse TLS private key from PEM string", e);
    }
  }

  @Provides
  static KeyStore provideKeyStore(PrivateKey privateKey, Certificate certificate) {
    try {
      KeyStore keyStore = KeyStore.getInstance(KEY_STORE_TYPE);
      keyStore.load(null, null);
      keyStore.setKeyEntry(
          KEY_STORE_ALIAS, privateKey, new char[0], new Certificate[] {certificate});
      return keyStore;
    } catch (GeneralSecurityException | IOException e) {
      throw new RuntimeException("Could not create KeyStore for mTLS", e);
    }
  }

  @Provides
  static KeyManagerFactory provideKeyManagerFactory(KeyStore keyStore) {
    try {
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, new char[0]);
      return kmf;
    } catch (GeneralSecurityException e) {
      throw new RuntimeException("Could not initialize KeyManagerFactory", e);
    }
  }

  @Provides
  @Named(MOSAPI_SSL_CONTEXT)
  static SSLContext provideSslContext(KeyManagerFactory keyManagerFactory) {
    try {
      SSLContext sslContext = SSLContext.getInstance(SSL_CONTEXT_PROTOCOL);
      sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
      return sslContext;
    } catch (GeneralSecurityException e) {
      throw new RuntimeException("Could not initialize SSLContext", e);
    }
  }

  @Provides
  static X509TrustManager provideTrustManager() {
    try {
      TrustManagerFactory trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init((KeyStore) null);
      return (X509TrustManager) trustManagerFactory.getTrustManagers()[0];
    } catch (GeneralSecurityException e) {
      throw new RuntimeException("Could not initialize TrustManager", e);
    }
  }

  @Provides
  @Singleton
  @Named(MOSAPI_HTTP_CLIENT)
  static OkHttpClient provideMosapiHttpClient(
      @Named(MOSAPI_SSL_CONTEXT) SSLContext sslContext, X509TrustManager trustManager) {
    return new OkHttpClient.Builder()
        .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
        .build();
  }

  /**
   * Provides a fixed thread pool for parallel TLD processing.
   *
   * <p>Strictly bound to 4 threads to comply with MoSAPI session limits (4 concurrent sessions per
   * certificate). This is used by MosApiStateService to fetch data in parallel.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 12.3</a>
   */
  @Provides
  @Singleton
  @Named("mosapiTldExecutor")
  static ExecutorService provideMosapiTldExecutor(
      @Config("mosapiTldThreadCnt") int threadPoolSize) {
    return Executors.newFixedThreadPool(threadPoolSize);
  }
}
