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

package google.registry.reporting.mosapi;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.privileges.secretmanager.SecretManagerClient;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MosApiSessionCache}. */
@ExtendWith(MockitoExtension.class)
public class MosApiSessionCacheTest {
  private static final String ENTITY_ID = "test-tld";
  private static final String COOKIE_VALUE = "id=test-cookie-123";
  private static final String SECRET_NAME = "mosapi_session_cookie_test-tld";

  @Mock private SecretManagerClient mockSecretManagerClient;

  private MosApiSessionCache mosApiSessionCache;

  @BeforeEach
  void setUp() {
    mosApiSessionCache = new MosApiSessionCache(mockSecretManagerClient);
  }

  @Test
  void get_success_cookieExists() {
    when(mockSecretManagerClient.getSecretData(SECRET_NAME, Optional.of("latest")))
        .thenReturn(COOKIE_VALUE);
    Optional<String> cookie = mosApiSessionCache.get(ENTITY_ID);
    assertThat(cookie).isPresent();
    assertThat(cookie.get()).isEqualTo(COOKIE_VALUE);
    verify(mockSecretManagerClient).getSecretData(SECRET_NAME, Optional.of("latest"));
  }

  @Test
  void get_cookieIsEmpty_returnsEmpty() {
    when(mockSecretManagerClient.getSecretData(SECRET_NAME, Optional.of("latest"))).thenReturn("");
    Optional<String> cookie = mosApiSessionCache.get(ENTITY_ID);
    assertThat(cookie).isEmpty();
  }

  @Test
  void get_cookieIsNull_returnsEmpty() {
    when(mockSecretManagerClient.getSecretData(SECRET_NAME, Optional.of("latest")))
        .thenReturn(null);
    Optional<String> cookie = mosApiSessionCache.get(ENTITY_ID);
    assertThat(cookie).isEmpty();
  }

  @Test
  void get_secretNotFound_exceptionHandled() {
    when(mockSecretManagerClient.getSecretData(SECRET_NAME, Optional.of("latest")))
        .thenThrow(new RuntimeException("Secret not found"));
    // The exception should be caught and logged, returning empty.
    Optional<String> cookie = mosApiSessionCache.get(ENTITY_ID);
    assertThat(cookie).isEmpty();
  }

  @Test
  void store_success() {
    // A successful call to the (void) mock method will do nothing.
    assertDoesNotThrow(() -> mosApiSessionCache.store(ENTITY_ID, COOKIE_VALUE));
    // Verify the client was called with the correct secret name and value.
    verify(mockSecretManagerClient).addSecretVersion(SECRET_NAME, COOKIE_VALUE);
  }

  @Test
  void store_failure_throwsRuntimeException() {
    doThrow(new RuntimeException("Permission denied"))
        .when(mockSecretManagerClient)
        .addSecretVersion(SECRET_NAME, COOKIE_VALUE);

    RuntimeException e =
        assertThrows(
            RuntimeException.class, () -> mosApiSessionCache.store(ENTITY_ID, COOKIE_VALUE));

    assertThat(e).hasMessageThat().isEqualTo("Failed to store session cookie in Secret Manager.");
    assertThat(e).hasCauseThat().isInstanceOf(RuntimeException.class);
    assertThat(e.getCause()).hasMessageThat().isEqualTo("Permission denied");
  }

  @Test
  void clear_callsStoreWithEmptyString() {
    assertDoesNotThrow(() -> mosApiSessionCache.clear(ENTITY_ID));
    // Verify store() was called with the correct secret name and an empty string
    verify(mockSecretManagerClient).addSecretVersion(SECRET_NAME, "");
  }
}
