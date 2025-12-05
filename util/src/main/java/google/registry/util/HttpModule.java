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

package google.registry.util;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

@Module
public class HttpModule {
  @Provides
  static HttpClient.Builder provideHttpClientBuilder() {
    return HttpClient.newBuilder();
  }

  @Provides
  @Singleton
  static HttpClient provideHttpClient() {
    return HttpClient.newHttpClient();
  }

  @Provides
  @Singleton
  @Named("mosapiHttpClient")
  static HttpClient provideMosapiHttpClient(
      @Named("mosapiTlsCert") String tlsCert, @Named("mosapiTlsKey") String tlsKey) {
    try {
      // 1. Parse the Certificate first
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      Certificate certificate =
          cf.generateCertificate(
              new ByteArrayInputStream(tlsCert.getBytes(StandardCharsets.UTF_8)));

      // The certificate explicitly knows if it is RSA or EC.
      // We ask the cert for the algorithm, then use that to create the KeyFactory.
      String detectedAlgo = certificate.getPublicKey().getAlgorithm();
      // -------------------------------------------

      // 2. Parse the Private Key
      // This regex cleans up PKCS#1, PKCS#8, and all newlines/spaces safely.
      String privateKeyPem =
          tlsKey
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replace("-----BEGIN RSA PRIVATE KEY-----", "")
              .replace("-----END RSA PRIVATE KEY-----", "")
              .replace("-----BEGIN EC PRIVATE KEY-----", "")
              .replace("-----END EC PRIVATE KEY-----", "")
              .replaceAll("\\s+", "");

      byte[] encoded = Base64.getDecoder().decode(privateKeyPem);

      // 3. Use the DETECTED algorithm
      KeyFactory keyFactory = KeyFactory.getInstance(detectedAlgo);
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
      PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

      // 4. Create KeyStore
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(null, null);
      keyStore.setKeyEntry("client", privateKey, new char[0], new Certificate[] {certificate});

      // 5. Create SSLContext
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, new char[0]);

      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(kmf.getKeyManagers(), null, null);

      return HttpClient.newBuilder().sslContext(sslContext).build();

    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize MoSAPI mTLS HttpClient", e);
    }
  }
}
