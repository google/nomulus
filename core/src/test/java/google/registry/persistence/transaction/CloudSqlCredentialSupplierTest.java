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

package google.registry.persistence.transaction;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.sql.CredentialFactory;
import java.io.IOException;
import javax.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link CloudSqlCredentialSupplier}. */
final class CloudSqlCredentialSupplierTest {

  private static final GenericUrl CONNECT_SETTINGS_URL =
      new GenericUrl(
          "https://sqladmin.googleapis.com/sql/v1beta4/projects/test/instances/db/connectSettings");
  private static final GoogleCredentials TEST_CREDENTIALS =
      GoogleCredentials.create(new AccessToken("test-access-token", null));
  private static final GoogleCredentials OTHER_CREDENTIALS =
      GoogleCredentials.create(new AccessToken("other-access-token", null));

  @Nullable private String originalCredentialFactoryProperty;

  @BeforeEach
  void beforeEach() {
    originalCredentialFactoryProperty =
        System.getProperty(CredentialFactory.CREDENTIAL_FACTORY_PROPERTY);
    System.clearProperty(CredentialFactory.CREDENTIAL_FACTORY_PROPERTY);
    CloudSqlCredentialSupplier.resetForTesting();
  }

  @AfterEach
  void afterEach() {
    CloudSqlCredentialSupplier.resetForTesting();
    if (originalCredentialFactoryProperty == null) {
      System.clearProperty(CredentialFactory.CREDENTIAL_FACTORY_PROPERTY);
    } else {
      System.setProperty(
          CredentialFactory.CREDENTIAL_FACTORY_PROPERTY, originalCredentialFactoryProperty);
    }
  }

  @Test
  void testCreate_uninitialized_throwsIllegalStateException() {
    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> new CloudSqlCredentialSupplier().create());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("CloudSqlCredentialSupplier has not been initialized");
  }

  @Test
  void testGetCredentials_uninitialized_throwsIllegalStateException() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> new CloudSqlCredentialSupplier().getCredentials());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("CloudSqlCredentialSupplier has not been initialized");
  }

  @Test
  void testSetup_setsCredentialFactoryPropertyAndReturnsCredentials() {
    CloudSqlCredentialSupplier.setupCredentialSupplier(TEST_CREDENTIALS, null);
    CloudSqlCredentialSupplier supplier = new CloudSqlCredentialSupplier();

    assertThat(System.getProperty(CredentialFactory.CREDENTIAL_FACTORY_PROPERTY))
        .isEqualTo(CloudSqlCredentialSupplier.class.getName());
    assertThat(supplier.getCredentials()).isSameInstanceAs(TEST_CREDENTIALS);
  }

  @Test
  void testSetup_supportsReflectiveInstantiationViaProperty()
      throws ReflectiveOperationException, IOException {
    CloudSqlCredentialSupplier.setupCredentialSupplier(TEST_CREDENTIALS, "ticket/12345");

    String className = System.getProperty(CredentialFactory.CREDENTIAL_FACTORY_PROPERTY);
    CredentialFactory factory =
        Class.forName(className)
            .asSubclass(CredentialFactory.class)
            .getDeclaredConstructor()
            .newInstance();

    assertThat(factory).isInstanceOf(CloudSqlCredentialSupplier.class);
    assertThat(factory.getCredentials()).isSameInstanceAs(TEST_CREDENTIALS);
    HttpRequest request = buildGetRequest(factory.create());
    assertThat(request.getHeaders().getFirstHeaderStringValue("X-Goog-Request-Reason"))
        .isEqualTo("ticket/12345");
  }

  @Test
  void testCreate_withoutRequestReason_setsAuthorizationOnly() throws IOException {
    CloudSqlCredentialSupplier.setupCredentialSupplier(TEST_CREDENTIALS, null);
    HttpRequestInitializer initializer = new CloudSqlCredentialSupplier().create();

    HttpRequest request = buildGetRequest(initializer);

    assertThat(initializer.getClass()).isEqualTo(HttpCredentialsAdapter.class);
    assertThat(request.getHeaders().getAuthorization()).isEqualTo("Bearer test-access-token");
    assertThat(request.getHeaders().getFirstHeaderStringValue("X-Goog-Request-Reason")).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t", "\u00a0", "\u3000"})
  void testCreate_withBlankOrUnicodeWhitespaceRequestReason_omitsHeader(String requestReason)
      throws IOException {
    CloudSqlCredentialSupplier.setupCredentialSupplier(TEST_CREDENTIALS, requestReason);
    HttpRequestInitializer initializer = new CloudSqlCredentialSupplier().create();

    HttpRequest request = buildGetRequest(initializer);

    assertThat(initializer.getClass()).isEqualTo(HttpCredentialsAdapter.class);
    assertThat(request.getHeaders().getAuthorization()).isEqualTo("Bearer test-access-token");
    assertThat(request.getHeaders().getFirstHeaderStringValue("X-Goog-Request-Reason")).isNull();
  }

  @Test
  void testCreate_withRequestReason_attachesTrimmedHeaderAndAuthorization() throws IOException {
    CloudSqlCredentialSupplier.setupCredentialSupplier(
        TEST_CREDENTIALS, "  \u00a0ticket/12345 investigate latency\u00a0  ");
    CloudSqlCredentialSupplier supplier = new CloudSqlCredentialSupplier();
    HttpRequestInitializer initializer = supplier.create();

    HttpRequest request = buildGetRequest(initializer);

    assertThat(initializer).isInstanceOf(HttpCredentialsAdapter.class);
    assertThat(supplier.getCredentials()).isSameInstanceAs(TEST_CREDENTIALS);
    assertThat(request.getHeaders().getAuthorization()).isEqualTo("Bearer test-access-token");
    assertThat(request.getHeaders().getFirstHeaderStringValue("X-Goog-Request-Reason"))
        .isEqualTo("ticket/12345 investigate latency");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\u00a0"})
  void testSetup_resetRequestReason_clearsHeader(@Nullable String resetReason) throws IOException {
    CloudSqlCredentialSupplier.setupCredentialSupplier(TEST_CREDENTIALS, "ticket/12345");
    HttpRequest initialRequest = buildGetRequest(new CloudSqlCredentialSupplier().create());
    assertThat(initialRequest.getHeaders().getFirstHeaderStringValue("X-Goog-Request-Reason"))
        .isEqualTo("ticket/12345");

    CloudSqlCredentialSupplier.setupCredentialSupplier(TEST_CREDENTIALS, resetReason);
    HttpRequestInitializer resetInitializer = new CloudSqlCredentialSupplier().create();
    HttpRequest resetRequest = buildGetRequest(resetInitializer);

    assertThat(resetInitializer.getClass()).isEqualTo(HttpCredentialsAdapter.class);
    assertThat(resetRequest.getHeaders().getAuthorization()).isEqualTo("Bearer test-access-token");
    assertThat(resetRequest.getHeaders().getFirstHeaderStringValue("X-Goog-Request-Reason"))
        .isNull();
  }

  @Test
  void testCreate_initializeTwice_isIdempotentAndPreservesHeadersAndHandler() throws IOException {
    CloudSqlCredentialSupplier.setupCredentialSupplier(TEST_CREDENTIALS, "ticket/12345");
    HttpRequestInitializer initializer = new CloudSqlCredentialSupplier().create();
    HttpRequest request =
        new MockHttpTransport().createRequestFactory().buildGetRequest(CONNECT_SETTINGS_URL);
    request.getHeaders().set("X-Existing-Header", "existing-value");

    initializer.initialize(request);
    initializer.initialize(request);

    assertThat(request.getHeaders().getAuthorization()).isEqualTo("Bearer test-access-token");
    assertThat(request.getHeaders().getHeaderStringValues("X-Goog-Request-Reason"))
        .containsExactly("ticket/12345");
    assertThat(request.getHeaders().getFirstHeaderStringValue("X-Existing-Header"))
        .isEqualTo("existing-value");
    assertThat(request.getUnsuccessfulResponseHandler()).isSameInstanceAs(initializer);
  }

  @Test
  void testSetup_nullCredential_throwsNullPointerExceptionWithoutMutatingState() {
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () ->
                CloudSqlCredentialSupplier.setupCredentialSupplier(
                    null, "ticket/12345 investigate latency"));

    assertThat(thrown).hasMessageThat().contains("credential");
    assertThat(System.getProperty(CredentialFactory.CREDENTIAL_FACTORY_PROPERTY)).isNull();
    assertThrows(
        IllegalStateException.class, () -> new CloudSqlCredentialSupplier().getCredentials());
  }

  @Test
  void testSetup_nullCredential_preservesExistingConfigAndProperty() throws IOException {
    CloudSqlCredentialSupplier.setupCredentialSupplier(TEST_CREDENTIALS, "ticket/12345");

    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> CloudSqlCredentialSupplier.setupCredentialSupplier(null, "ticket/99999"));

    assertThat(thrown).hasMessageThat().contains("credential");
    assertThat(System.getProperty(CredentialFactory.CREDENTIAL_FACTORY_PROPERTY))
        .isEqualTo(CloudSqlCredentialSupplier.class.getName());
    CloudSqlCredentialSupplier supplier = new CloudSqlCredentialSupplier();
    assertThat(supplier.getCredentials()).isSameInstanceAs(TEST_CREDENTIALS);
    HttpRequest request = buildGetRequest(supplier.create());
    assertThat(request.getHeaders().getFirstHeaderStringValue("X-Goog-Request-Reason"))
        .isEqualTo("ticket/12345");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "reason\rinjected",
        "reason\ninjected",
        "reason\r\nX-Injected: bad",
        "reason\r\n folded",
        "reason\0null",
        "\0",
        "reason\tvalue",
        "reason\u007fvalue",
        "ticket/caf\u00e9",
        "ticket\u00a012345"
      })
  void testSetup_invalidRequestReason_throwsIllegalArgumentExceptionWithoutMutatingState(
      String invalidReason) {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CloudSqlCredentialSupplier.setupCredentialSupplier(
                    TEST_CREDENTIALS, invalidReason));

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("CLOUDSDK_CORE_REQUEST_REASON must contain only printable ASCII characters");
    assertThat(System.getProperty(CredentialFactory.CREDENTIAL_FACTORY_PROPERTY)).isNull();
    assertThrows(
        IllegalStateException.class, () -> new CloudSqlCredentialSupplier().getCredentials());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "reason\rinjected",
        "reason\ninjected",
        "reason\r\nX-Injected: bad",
        "reason\0null",
        "ticket/caf\u00e9"
      })
  void testSetup_invalidRequestReason_preservesExistingConfigAndProperty(String invalidReason)
      throws IOException {
    CloudSqlCredentialSupplier.setupCredentialSupplier(TEST_CREDENTIALS, "ticket/12345");

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CloudSqlCredentialSupplier.setupCredentialSupplier(
                    OTHER_CREDENTIALS, invalidReason));

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("CLOUDSDK_CORE_REQUEST_REASON must contain only printable ASCII characters");
    assertThat(System.getProperty(CredentialFactory.CREDENTIAL_FACTORY_PROPERTY))
        .isEqualTo(CloudSqlCredentialSupplier.class.getName());
    CloudSqlCredentialSupplier supplier = new CloudSqlCredentialSupplier();
    assertThat(supplier.getCredentials()).isSameInstanceAs(TEST_CREDENTIALS);
    HttpRequest request = buildGetRequest(supplier.create());
    assertThat(request.getHeaders().getFirstHeaderStringValue("X-Goog-Request-Reason"))
        .isEqualTo("ticket/12345");
  }

  private static HttpRequest buildGetRequest(HttpRequestInitializer initializer)
      throws IOException {
    return new MockHttpTransport()
        .createRequestFactory(initializer)
        .buildGetRequest(CONNECT_SETTINGS_URL);
  }
}
