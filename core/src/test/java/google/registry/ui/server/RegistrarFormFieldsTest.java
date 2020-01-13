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

package google.registry.ui.server;

import static com.google.common.truth.Truth8.assertThat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThrows;

import google.registry.testing.CertificateSamples;
import google.registry.ui.forms.FormFieldException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RegistrarFormFields}. */
@RunWith(JUnit4.class)
public class RegistrarFormFieldsTest {
  @Test
  public void testValidCertificate_doesntThrowError() {
    assertThat(RegistrarFormFields.CLIENT_CERTIFICATE_FIELD.convert(CertificateSamples.SAMPLE_CERT))
        .hasValue(CertificateSamples.SAMPLE_CERT);
  }

  @Test
  public void testBadCertificate_throwsFfe() {
    FormFieldException thrown =
        assertThrows(
            FormFieldException.class,
            () -> RegistrarFormFields.CLIENT_CERTIFICATE_FIELD.convert("palfun"));
    assertThat(
        thrown,
        equalTo(
            new FormFieldException("Invalid X.509 PEM certificate")
                .propagate("clientCertificate")));
  }

  @Test
  public void testValidCertificateHash_doesntThrowError() {
    assertThat(
            RegistrarFormFields.CLIENT_CERTIFICATE_HASH_FIELD.convert(
                CertificateSamples.SAMPLE_CERT_HASH))
        .hasValue(CertificateSamples.SAMPLE_CERT_HASH);
  }

  @Test
  public void testBadCertificateHash_throwsFfe() {
    FormFieldException thrown =
        assertThrows(
            FormFieldException.class,
            () -> RegistrarFormFields.CLIENT_CERTIFICATE_HASH_FIELD.convert("~~~"));
    assertThat(
        thrown,
        equalTo(
            new FormFieldException("Field must contain a base64 value.")
                .propagate("clientCertificateHash")));
  }
}
