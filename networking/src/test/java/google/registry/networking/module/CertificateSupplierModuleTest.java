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

package google.registry.networking.module;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.networking.handler.SslInitializerTestUtils.getKeyPair;
import static google.registry.networking.handler.SslInitializerTestUtils.signKeyPair;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.networking.module.CertificateSupplierModule.Mode;
import google.registry.networking.module.CertificateSupplierModule.TlsCredentials;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import google.registry.util.SelfSignedCaCertificate;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CertificateSupplierModule}. */
class CertificateSupplierModuleTest {

  private SelfSignedCaCertificate ssc;
  private PrivateKey key;
  private Certificate cert;
  private TestComponent component;

  /** Create a component with bindings to construct certificates and keys from a PEM file. */
  private static TestComponent createComponentForPem(Object... objects) throws Exception {
    return DaggerCertificateSupplierModuleTest_TestComponent.builder()
        .certificateModule(CertificateModule.createCertificateModuleForPem(objects))
        .useMode(Mode.PEM_FILE)
        .build();
  }

  private static byte[] encodePem(Object... objects) throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (JcaPEMWriter pemWriter =
        new JcaPEMWriter(new OutputStreamWriter(byteArrayOutputStream, UTF_8))) {
      for (Object object : objects) {
        pemWriter.writeObject(object);
      }
    }
    return byteArrayOutputStream.toByteArray();
  }

  @BeforeEach
  void beforeEach() throws Exception {
    ssc = SelfSignedCaCertificate.create(new FakeClock());
    KeyPair keyPair = getKeyPair();
    key = keyPair.getPrivate();
    cert = signKeyPair(ssc, keyPair, "example.tld");
  }

  @Test
  void testSuccess() throws Exception {
    component = createComponentForPem(cert, ssc.cert(), key);
    TlsCredentials credentials = component.tlsCredentialsSupplier().get();
    assertThat(credentials.privateKey()).isEqualTo(key);
    assertThat(credentials.certificates()).containsExactly(cert, ssc.cert()).inOrder();
  }

  @Test
  void testSuccess_certificateChainNotContinuous() throws Exception {
    component = createComponentForPem(cert, key, ssc.cert());
    TlsCredentials credentials = component.tlsCredentialsSupplier().get();
    assertThat(credentials.privateKey()).isEqualTo(key);
    assertThat(credentials.certificates()).containsExactly(cert, ssc.cert()).inOrder();
  }

  @Test
  void testSuccess_selfSignedMode() throws Exception {
    component =
        DaggerCertificateSupplierModuleTest_TestComponent.builder()
            .certificateModule(CertificateModule.createCertificateModuleForPem())
            .useMode(Mode.SELF_SIGNED)
            .build();
    TlsCredentials credentials = component.tlsCredentialsSupplier().get();
    assertThat(credentials.privateKey()).isNotNull();
    assertThat(credentials.certificates()).hasSize(1);
  }

  @Test
  void testFailure_p12ModeUnsupported() throws Exception {
    component =
        DaggerCertificateSupplierModuleTest_TestComponent.builder()
            .certificateModule(
                CertificateModule.createCertificateModuleForPem(cert, ssc.cert(), key))
            .useMode(Mode.P12_FILE)
            .build();
    UnsupportedOperationException thrown =
        assertThrows(UnsupportedOperationException.class, () -> component.tlsCredentialsSupplier());
    assertThat(thrown).hasMessageThat().contains("P12_FILE");
  }

  @Test
  void testSuccess_singleFetchCachesAtomically() throws Exception {
    KeyPair rotatedKeyPair = getKeyPair();
    PrivateKey rotatedKey = rotatedKeyPair.getPrivate();
    X509Certificate rotatedCert = signKeyPair(ssc, rotatedKeyPair, "rotated.tld");

    byte[] firstPem = encodePem(cert, ssc.cert(), key);
    byte[] secondPem = encodePem(rotatedCert, ssc.cert(), rotatedKey);
    AtomicInteger fetchCount = new AtomicInteger();

    component =
        DaggerCertificateSupplierModuleTest_TestComponent.builder()
            .certificateModule(
                new CertificateModule(
                    () -> fetchCount.getAndIncrement() == 0 ? firstPem : secondPem,
                    Duration.ofDays(1)))
            .useMode(Mode.PEM_FILE)
            .build();

    TlsCredentials first = component.tlsCredentialsSupplier().get();
    TlsCredentials second = component.tlsCredentialsSupplier().get();
    assertThat(fetchCount.get()).isEqualTo(1);
    assertThat(first).isSameInstanceAs(second);
    assertThat(first.privateKey()).isEqualTo(key);
    assertThat(first.certificates()).containsExactly(cert, ssc.cert()).inOrder();
  }

  @Test
  void testSuccess_atomicRotationAfterExpiration() throws Exception {
    KeyPair rotatedKeyPair = getKeyPair();
    PrivateKey rotatedKey = rotatedKeyPair.getPrivate();
    X509Certificate rotatedCert = signKeyPair(ssc, rotatedKeyPair, "rotated.tld");

    byte[] firstPem = encodePem(cert, ssc.cert(), key);
    byte[] secondPem = encodePem(rotatedCert, ssc.cert(), rotatedKey);
    AtomicInteger fetchCount = new AtomicInteger();

    component =
        DaggerCertificateSupplierModuleTest_TestComponent.builder()
            .certificateModule(
                new CertificateModule(
                    () -> fetchCount.getAndIncrement() == 0 ? firstPem : secondPem,
                    Duration.ofNanos(1)))
            .useMode(Mode.PEM_FILE)
            .build();

    Supplier<TlsCredentials> supplier = component.tlsCredentialsSupplier();
    TlsCredentials first = supplier.get();
    assertThat(first.privateKey()).isEqualTo(key);
    assertThat(first.certificates()).containsExactly(cert, ssc.cert()).inOrder();

    long before = System.nanoTime();
    while (System.nanoTime() - before <= 1L) {
      // Spin briefly until the 1ns expiration window elapses without sleeping.
    }

    TlsCredentials rotated = supplier.get();
    assertThat(fetchCount.get()).isEqualTo(2);
    assertThat(rotated.privateKey()).isEqualTo(rotatedKey);
    assertThat(rotated.certificates()).containsExactly(rotatedCert, ssc.cert()).inOrder();
  }

  @Test
  @SuppressWarnings("ReturnValueIgnored")
  void testFailure_noPrivateKey() throws Exception {
    component = createComponentForPem(cert, ssc.cert());
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.tlsCredentialsSupplier().get());
    assertThat(thrown).hasMessageThat().contains("0 keys are found");
  }

  @Test
  @SuppressWarnings("ReturnValueIgnored")
  void testFailure_twoPrivateKeys() throws Exception {
    component = createComponentForPem(cert, ssc.cert(), key, ssc.key());
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.tlsCredentialsSupplier().get());
    assertThat(thrown).hasMessageThat().contains("2 keys are found");
  }

  @Test
  @SuppressWarnings("ReturnValueIgnored")
  void testFailure_certificatesOutOfOrder() throws Exception {
    component = createComponentForPem(ssc.cert(), cert, key);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.tlsCredentialsSupplier().get());
    assertThat(thrown).hasMessageThat().contains("is not signed by");
  }

  @Test
  @SuppressWarnings("ReturnValueIgnored")
  void testFailure_noCertificates() throws Exception {
    component = createComponentForPem(key);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.tlsCredentialsSupplier().get());
    assertThat(thrown).hasMessageThat().contains("No certificates");
  }

  @Module
  static class CertificateModule {

    private final Supplier<byte[]> pemBytesSupplier;
    private final Duration cachingDuration;

    private CertificateModule(Supplier<byte[]> pemBytesSupplier, Duration cachingDuration) {
      this.pemBytesSupplier = pemBytesSupplier;
      this.cachingDuration = cachingDuration;
    }

    static CertificateModule createCertificateModuleForPem(Object... objects) throws Exception {
      byte[] encoded = encodePem(objects);
      return new CertificateModule(encoded::clone, Duration.ofDays(1));
    }

    @Provides
    @Named("pemBytes")
    byte[] providePemBytes() {
      return pemBytesSupplier.get().clone();
    }

    @Provides
    @Named("remoteCertCachingDuration")
    Duration provideCachingDuration() {
      return cachingDuration;
    }

    @Provides
    Clock provideClock() {
      return new FakeClock();
    }
  }

  /**
   * Test component that exposes the atomic TLS credentials supplier.
   *
   * <p>Depending on what {@link Mode} is provided to the component, it will return certificates and
   * private key extracted from the corresponding format.
   */
  @Singleton
  @Component(modules = {CertificateSupplierModule.class, CertificateModule.class})
  interface TestComponent {

    Supplier<TlsCredentials> tlsCredentialsSupplier();

    @Component.Builder
    interface Builder {

      @BindsInstance
      Builder useMode(Mode mode);

      Builder certificateModule(CertificateModule certificateModule);

      TestComponent build();
    }
  }
}
