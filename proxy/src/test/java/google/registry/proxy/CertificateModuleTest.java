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

package google.registry.proxy;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.networking.handler.SslInitializerTestUtils.getKeyPair;
import static google.registry.networking.handler.SslInitializerTestUtils.signKeyPair;
import static google.registry.testing.JUnitBackports.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.proxy.CertificateModule.Prod;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.inject.Named;
import javax.inject.Singleton;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CertificateModule}. */
@RunWith(JUnit4.class)
public class CertificateModuleTest {

  private SelfSignedCertificate ssc;
  private PrivateKey key;
  private Certificate cert;
  private TestComponent component;

  private static byte[] getPemBytes(Object... objects) throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (JcaPEMWriter pemWriter =
        new JcaPEMWriter(new OutputStreamWriter(byteArrayOutputStream, UTF_8))) {
      for (Object object : objects) {
        pemWriter.writeObject(object);
      }
    }
    return byteArrayOutputStream.toByteArray();
  }

  /** Create a component with bindings to the given bytes[] as the contents from a PEM file. */
  private static TestComponent createComponent(byte[] pemBytes) {
    return DaggerCertificateModuleTest_TestComponent.builder()
        .pemBytesModule(new PemBytesModule(pemBytes))
        .build();
  }

  @Before
  public void setUp() throws Exception {
    ssc = new SelfSignedCertificate();
    KeyPair keyPair = getKeyPair();
    key = keyPair.getPrivate();
    cert = signKeyPair(ssc, keyPair, "example.tld");
  }

  @Test
  public void testSuccess() throws Exception {
    byte[] pemBytes = getPemBytes(cert, ssc.cert(), key);
    component = createComponent(pemBytes);
    assertThat(component.privateKey()).isEqualTo(key);
    assertThat(component.certificates()).asList().containsExactly(cert, ssc.cert()).inOrder();
  }

  @Test
  public void testSuccess_certificateChainNotContinuous() throws Exception {
    byte[] pemBytes = getPemBytes(cert, key, ssc.cert());
    component = createComponent(pemBytes);
    assertThat(component.privateKey()).isEqualTo(key);
    assertThat(component.certificates()).asList().containsExactly(cert, ssc.cert()).inOrder();
  }

  @Test
  public void testFailure_noPrivateKey() throws Exception {
    byte[] pemBytes = getPemBytes(cert, ssc.cert());
    component = createComponent(pemBytes);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.privateKey());
    assertThat(thrown).hasMessageThat().contains("0 keys are found");
  }

  @Test
  public void testFailure_twoPrivateKeys() throws Exception {
    byte[] pemBytes = getPemBytes(cert, ssc.cert(), key, ssc.key());
    component = createComponent(pemBytes);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.privateKey());
    assertThat(thrown).hasMessageThat().contains("2 keys are found");
  }

  @Test
  public void testFailure_certificatesOutOfOrder() throws Exception {
    byte[] pemBytes = getPemBytes(ssc.cert(), cert, key);
    component = createComponent(pemBytes);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.certificates());
    assertThat(thrown).hasMessageThat().contains("is not signed by");
  }

  @Test
  public void testFailure_noCertificates() throws Exception {
    byte[] pemBytes = getPemBytes(key);
    component = createComponent(pemBytes);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.certificates());
    assertThat(thrown).hasMessageThat().contains("No certificates");
  }

  @Module
  static class PemBytesModule {
    private final byte[] pemBytes;

    PemBytesModule(byte[] pemBytes) {
      this.pemBytes = pemBytes;
    }

    @Provides
    @Named("pemBytes")
    byte[] providePemBytes() {
      return pemBytes;
    }
  }

  /**
   * Test component that exposes prod certificate and key.
   *
   * <p>Local certificate and key are not tested because they are directly extracted from a
   * self-signed certificate. Here we want to test that we can correctly parse and create
   * certificate and keys from a .pem file.
   */
  @Singleton
  @Component(modules = {CertificateModule.class, PemBytesModule.class})
  interface TestComponent {

    @Prod
    PrivateKey privateKey();

    @Prod
    X509Certificate[] certificates();
  }
}
