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
import static google.registry.testing.JUnitBackports.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.networking.module.CertificateSupplierModule.Mode;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.function.Supplier;
import javax.inject.Named;
import javax.inject.Singleton;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CertificateSupplierModule}. */
@RunWith(JUnit4.class)
public class CertificateSupplierModuleTest {

  private SelfSignedCertificate ssc;
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

  @Before
  public void setUp() throws Exception {
    ssc = new SelfSignedCertificate();
    KeyPair keyPair = getKeyPair();
    key = keyPair.getPrivate();
    cert = signKeyPair(ssc, keyPair, "example.tld");
  }

  @Test
  public void testSuccess() throws Exception {
    component = createComponentForPem(cert, ssc.cert(), key);
    assertThat(component.privateKeySupplier().get()).isEqualTo(key);
    assertThat(component.certificatesSupplier().get()).containsExactly(cert, ssc.cert()).inOrder();
  }

  @Test
  public void testSuccess_certificateChainNotContinuous() throws Exception {
    component = createComponentForPem(cert, key, ssc.cert());
    assertThat(component.privateKeySupplier().get()).isEqualTo(key);
    assertThat(component.certificatesSupplier().get()).containsExactly(cert, ssc.cert()).inOrder();
  }

  @Test
  @SuppressWarnings("ReturnValueIgnored")
  public void testFailure_noPrivateKey() throws Exception {
    component = createComponentForPem(cert, ssc.cert());
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.privateKeySupplier().get());
    assertThat(thrown).hasMessageThat().contains("0 keys are found");
  }

  @Test
  @SuppressWarnings("ReturnValueIgnored")
  public void testFailure_twoPrivateKeys() throws Exception {
    component = createComponentForPem(cert, ssc.cert(), key, ssc.key());
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.privateKeySupplier().get());
    assertThat(thrown).hasMessageThat().contains("2 keys are found");
  }

  @Test
  @SuppressWarnings("ReturnValueIgnored")
  public void testFailure_certificatesOutOfOrder() throws Exception {
    component = createComponentForPem(ssc.cert(), cert, key);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.certificatesSupplier().get());
    assertThat(thrown).hasMessageThat().contains("is not signed by");
  }

  @Test
  @SuppressWarnings("ReturnValueIgnored")
  public void testFailure_noCertificates() throws Exception {
    component = createComponentForPem(key);
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> component.certificatesSupplier().get());
    assertThat(thrown).hasMessageThat().contains("No certificates");
  }

  @Module
  static class CertificateModule {

    private final byte[] pemBytes;

    private CertificateModule(byte[] pemBytes) {
      this.pemBytes = pemBytes.clone();
    }

    static CertificateModule createCertificateModuleForPem(Object... objects) throws Exception {
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      try (JcaPEMWriter pemWriter =
          new JcaPEMWriter(new OutputStreamWriter(byteArrayOutputStream, UTF_8))) {
        for (Object object : objects) {
          pemWriter.writeObject(object);
        }
      }
      return new CertificateModule(byteArrayOutputStream.toByteArray());
    }

    @Provides
    @Named("pemBytes")
    byte[] providePemBytes() {
      return pemBytes.clone();
    }

    @Provides
    @Named("remoteCertCachingDuration")
    Duration provideCachingDuration() {
      // Make the supplier always return the save value for test to save time.
      return Duration.ofDays(1);
    }
  }

  /**
   * Test component that exposes the certificates and private key.
   *
   * <p>Depending on what {@link Mode} is provided to the component, it will return certificates and
   * private key extracted from the correspondonig format. Self-signed mode is not tested as they
   * are only used in tests.
   */
  @Singleton
  @Component(modules = {CertificateSupplierModule.class, CertificateModule.class})
  interface TestComponent {

    Supplier<PrivateKey> privateKeySupplier();

    Supplier<ImmutableList<X509Certificate>> certificatesSupplier();

    @Component.Builder
    interface Builder {

      @BindsInstance
      Builder useMode(Mode mode);

      Builder certificateModule(CertificateModule certificateModule);

      TestComponent build();
    }
  }
}
