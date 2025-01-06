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

package google.registry.bsa.api;

import static com.google.common.truth.Truth.assertThat;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.keyring.api.Keyring;
import google.registry.request.UrlConnectionService;
import google.registry.testing.FakeClock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.security.GeneralSecurityException;
import javax.net.ssl.HttpsURLConnection;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link BsaCredential}. */
@ExtendWith(MockitoExtension.class)
class BsaCredentialTest {

  private static final Duration AUTH_TOKEN_EXPIRY = Duration.standardMinutes(30);

  @Mock OutputStream connectionOutputStream;
  @Mock HttpsURLConnection connection;
  @Mock UrlConnectionService connectionService;
  @Mock Keyring keyring;
  FakeClock clock = new FakeClock();
  BsaCredential credential;

  @BeforeEach
  void setup() throws Exception {
    credential =
        new BsaCredential(connectionService, "https://authUrl", AUTH_TOKEN_EXPIRY, keyring, clock);
  }

  void setupHttp() throws Exception {
    when(connectionService.createConnection(any(URL.class))).thenReturn(connection);
    when(connection.getOutputStream()).thenReturn(connectionOutputStream);
    when(keyring.getBsaApiKey()).thenReturn("bsaApiKey");
  }

  @Test
  void getAuthToken_fetchesNew() throws Exception {
    credential = spy(credential);
    doReturn("a", "b", "c").when(credential).fetchNewAuthToken();
    assertThat(credential.getAuthToken()).isEqualTo("a");
    verify(credential, times(1)).fetchNewAuthToken();
  }

  @Test
  void getAuthToken_useCached() throws Exception {
    credential = spy(credential);
    doReturn("a", "b", "c").when(credential).fetchNewAuthToken();
    assertThat(credential.getAuthToken()).isEqualTo("a");
    clock.advanceBy(AUTH_TOKEN_EXPIRY.minus(Duration.millis(1)));
    assertThat(credential.getAuthToken()).isEqualTo("a");
    verify(credential, times(1)).fetchNewAuthToken();
  }

  @Test
  void getAuthToken_cacheExpires() throws Exception {
    credential = spy(credential);
    doReturn("a", "b", "c").when(credential).fetchNewAuthToken();
    assertThat(credential.getAuthToken()).isEqualTo("a");
    clock.advanceBy(AUTH_TOKEN_EXPIRY);
    assertThat(credential.getAuthToken()).isEqualTo("b");
    verify(credential, times(2)).fetchNewAuthToken();
  }

  @Test
  void fetchNewAuthToken_success() throws Exception {
    setupHttp();
    when(connection.getResponseCode()).thenReturn(SC_OK);
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("{\"id_token\": \"abc\"}".getBytes(UTF_8)));
    assertThat(credential.getAuthToken()).isEqualTo("abc");
    verify(connection, times(1)).setRequestMethod("POST");
    verify(connection, times(1))
        .setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    verify(connectionOutputStream, times(1))
        .write(eq("apiKey=bsaApiKey&space=BSA".getBytes(UTF_8)), anyInt(), anyInt());
    verify(connection, times(1)).disconnect();
  }

  @Test
  void fetchNewAuthToken_whenStatusIsNotOK_throwsRetriableException() throws Exception {
    setupHttp();
    when(connection.getResponseCode()).thenReturn(202);
    assertThat(assertThrows(BsaException.class, () -> credential.getAuthToken()).isRetriable())
        .isTrue();
  }

  @Test
  void fetchNewAuthToken_IOException_isRetriable() throws Exception {
    setupHttp();
    doThrow(new IOException()).when(connection).getResponseCode();
    assertThat(assertThrows(BsaException.class, () -> credential.getAuthToken()).isRetriable())
        .isTrue();
  }

  @Test
  void fetchNewAuthToken_securityException_NotRetriable() throws Exception {
    doThrow(new GeneralSecurityException())
        .when(connectionService)
        .createConnection(any(URL.class));
    assertThat(assertThrows(BsaException.class, () -> credential.getAuthToken()).isRetriable())
        .isFalse();
  }
}
