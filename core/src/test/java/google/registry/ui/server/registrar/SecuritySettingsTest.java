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

package google.registry.ui.server.registrar;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2_HASH;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT_HASH;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.registrar.Registrar;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for security_settings.js use of {@link RegistrarSettingsAction}.
 *
 * <p>The default read and session validation tests are handled by the superclass.
 */
class SecuritySettingsTest extends RegistrarSettingsActionTestCase {

  @Test
  void testPost_updateCert_success() throws Exception {
    Registrar modified =
        loadRegistrar(CLIENT_ID)
            .asBuilder()
            .setClientCertificate(SAMPLE_CERT, clock.nowUtc())
            .build();
    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update",
        "id", CLIENT_ID,
        "args", modified.toJsonMap()));
    assertThat(response).containsEntry("status", "SUCCESS");
    assertThat(response).containsEntry("results", ImmutableList.of(modified.toJsonMap()));
    assertThat(loadRegistrar(CLIENT_ID)).isEqualTo(modified);
    assertMetric(CLIENT_ID, "update", "[OWNER]", "SUCCESS");
    verifyNotificationEmailsSent();
  }

  @Test
  void testPost_updateCert_failure() {
    Map<String, Object> reqJson = loadRegistrar(CLIENT_ID).toJsonMap();
    reqJson.put("clientCertificate", "BLAH");
    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update",
        "id", CLIENT_ID,
        "args", reqJson));
    assertThat(response).containsEntry("status", "ERROR");
    assertThat(response).containsEntry("message", "Invalid X.509 PEM certificate");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormFieldException");
  }

  @Test
  void testChangeCertificates() throws Exception {
    Map<String, Object> jsonMap = loadRegistrar(CLIENT_ID).toJsonMap();
    jsonMap.put("clientCertificate", SAMPLE_CERT);
    jsonMap.put("failoverClientCertificate", null);
    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update", "id", CLIENT_ID, "args", jsonMap));
    assertThat(response).containsEntry("status", "SUCCESS");
    Registrar registrar = loadRegistrar(CLIENT_ID);
    assertThat(registrar.getClientCertificate()).isEqualTo(SAMPLE_CERT);
    assertThat(registrar.getClientCertificateHash()).isEqualTo(SAMPLE_CERT_HASH);
    assertThat(registrar.getFailoverClientCertificate()).isNull();
    assertThat(registrar.getFailoverClientCertificateHash()).isNull();
    assertMetric(CLIENT_ID, "update", "[OWNER]", "SUCCESS");
    verifyNotificationEmailsSent();
  }

  @Test
  void testChangeFailoverCertificate() throws Exception {
    Map<String, Object> jsonMap = loadRegistrar(CLIENT_ID).toJsonMap();
    jsonMap.put("failoverClientCertificate", SAMPLE_CERT2);
    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update", "id", CLIENT_ID, "args", jsonMap));
    assertThat(response).containsEntry("status", "SUCCESS");
    Registrar registrar = loadRegistrar(CLIENT_ID);
    assertThat(registrar.getFailoverClientCertificate()).isEqualTo(SAMPLE_CERT2);
    assertThat(registrar.getFailoverClientCertificateHash()).isEqualTo(SAMPLE_CERT2_HASH);
    assertMetric(CLIENT_ID, "update", "[OWNER]", "SUCCESS");
    verifyNotificationEmailsSent();
  }

  @Test
  void testEmptyOrNullCertificate_doesNotClearOutCurrentOne() throws Exception {
    Registrar initialRegistrar =
        persistResource(
            loadRegistrar(CLIENT_ID)
                .asBuilder()
                .setClientCertificate(SAMPLE_CERT, START_OF_TIME)
                .setFailoverClientCertificate(SAMPLE_CERT2, START_OF_TIME)
                .build());
    Map<String, Object> jsonMap = initialRegistrar.toJsonMap();
    jsonMap.put("clientCertificate", null);
    jsonMap.put("failoverClientCertificate", "");
    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update", "id", CLIENT_ID, "args", jsonMap));
    assertThat(response).containsEntry("status", "SUCCESS");
    Registrar registrar = loadRegistrar(CLIENT_ID);
    assertThat(registrar.getClientCertificate()).isEqualTo(SAMPLE_CERT);
    assertThat(registrar.getClientCertificateHash()).isEqualTo(SAMPLE_CERT_HASH);
    assertThat(registrar.getFailoverClientCertificate()).isEqualTo(SAMPLE_CERT2);
    assertThat(registrar.getFailoverClientCertificateHash()).isEqualTo(SAMPLE_CERT2_HASH);
    assertMetric(CLIENT_ID, "update", "[OWNER]", "SUCCESS");
  }

  @Test
  void testToJsonMap_containsCertificate() {
    Map<String, Object> jsonMap =
        loadRegistrar(CLIENT_ID)
            .asBuilder()
            .setClientCertificate(SAMPLE_CERT2, START_OF_TIME)
            .build()
            .toJsonMap();
    assertThat(jsonMap).containsEntry("clientCertificate", SAMPLE_CERT2);
    assertThat(jsonMap).containsEntry("clientCertificateHash", SAMPLE_CERT2_HASH);
  }

  @Test
  void testToJsonMap_containsFailoverCertificate() {
    Map<String, Object> jsonMap =
        loadRegistrar(CLIENT_ID)
            .asBuilder()
            .setFailoverClientCertificate(SAMPLE_CERT2, START_OF_TIME)
            .build()
            .toJsonMap();
    assertThat(jsonMap).containsEntry("failoverClientCertificate", SAMPLE_CERT2);
    assertThat(jsonMap).containsEntry("failoverClientCertificateHash", SAMPLE_CERT2_HASH);
  }
}
