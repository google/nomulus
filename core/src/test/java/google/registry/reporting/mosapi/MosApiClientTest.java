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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.reporting.mosapi.MosApiClient.InvalidCredentialsException;
import google.registry.reporting.mosapi.MosApiClient.IpAddressNotAllowedException;
import google.registry.reporting.mosapi.MosApiClient.MosApiException;
import google.registry.reporting.mosapi.MosApiClient.RateLimitExceededException;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MosApiClientTest {
  private static final String TEST_URL = "https://example.com";
  private static final String ENTITY_TYPE = "ry";
  private static final String ENTITY_ID = "test-id";
  private static final String USERNAME = "testuser";
  private static final String PASSWORD = "testpassword";

  @Mock private HttpClient.Builder mockHttpClientBuilder;
  @Mock private HttpClient mockHttpClient;
  @Mock private HttpResponse<String> mockHttpResponse;
  @Mock private Function<String, String> mockUsernameProvider;
  @Mock private Function<String, String> mockPasswordProvider;

  @Captor private ArgumentCaptor<HttpRequest> httpRequestCaptor;

  private MosApiClient mosApiClient;

  @BeforeEach
  void setUp() {
    when(mockHttpClientBuilder.cookieHandler(any(CookieManager.class)))
        .thenReturn(mockHttpClientBuilder);
    when(mockHttpClientBuilder.build()).thenReturn(mockHttpClient);
    when(mockUsernameProvider.apply(ENTITY_ID)).thenReturn(USERNAME);
    when(mockPasswordProvider.apply(ENTITY_ID)).thenReturn(PASSWORD);

    mosApiClient =
        new MosApiClient(
            mockHttpClientBuilder,
            TEST_URL,
            ENTITY_TYPE,
            mockUsernameProvider,
            mockPasswordProvider);
  }

  @Test
  void login_success_isLoggedIn() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    assertDoesNotThrow(() -> mosApiClient.login(ENTITY_ID));

    verify(mockHttpClient).send(httpRequestCaptor.capture(), any());
    HttpRequest capturedRequest = httpRequestCaptor.getValue();
    assertThat(capturedRequest.uri()).isEqualTo(URI.create("https://example.com/ry/test-id/login"));
    assertThat(capturedRequest.headers().firstValue("Authorization")).isPresent();
  }

  @Test
  void login_failure_throwsInvalidCredentialsException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(401);
    when(mockHttpResponse.body()).thenReturn("Auth Failed");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    InvalidCredentialsException e =
        assertThrows(InvalidCredentialsException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(e).hasMessageThat().isEqualTo("Auth Failed");
  }

  @Test
  void login_failure_throwsIpAddressNotAllowedException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(403);
    when(mockHttpResponse.body()).thenReturn("Forbidden");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    IpAddressNotAllowedException e =
        assertThrows(IpAddressNotAllowedException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(e).hasMessageThat().isEqualTo("Forbidden");
  }

  @Test
  void login_failure_throwsRateLimitExceededException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(429);
    when(mockHttpResponse.body()).thenReturn("Too Many Requests");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    RateLimitExceededException e =
        assertThrows(RateLimitExceededException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(e).hasMessageThat().isEqualTo("Too Many Requests");
  }

  @Test
  void login_failure_throwsMosApiExceptionForUnexpectedCode() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(500);
    when(mockHttpResponse.body()).thenReturn("Internal Server Error");
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);

    MosApiException exception =
        assertThrows(MosApiException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(exception)
        .hasMessageThat()
        .contains("Login failed with unexpected status code: 500 - Internal Server Error");
  }

  @Test
  void login_failure_throwsMosApiExceptionForNetworkError() throws Exception {
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("Network failed"));

    MosApiException exception =
        assertThrows(MosApiException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(exception).hasMessageThat().isEqualTo("An error occurred during login.");
    assertThat(exception).hasCauseThat().isInstanceOf(IOException.class);
  }

  @Test
  void logout_success() throws Exception {
    // First, login successfully
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse);
    mosApiClient.login(ENTITY_ID);

    // Then, test logout
    assertDoesNotThrow(() -> mosApiClient.logout(ENTITY_ID));
    verify(mockHttpClient, times(2)).send(httpRequestCaptor.capture(), any());
    HttpRequest logoutRequest = httpRequestCaptor.getValue();
    assertThat(logoutRequest.uri()).isEqualTo(URI.create("https://example.com/ry/test-id/logout"));
  }

  @Test
  void logout_failure_logsWarningOn401() throws Exception {
    // First, login
    HttpResponse<String> loginResponse = mock(HttpResponse.class);
    when(loginResponse.statusCode()).thenReturn(200);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(loginResponse)
        .thenReturn(mockHttpResponse); // For the logout call
    mosApiClient.login(ENTITY_ID);

    // Then, mock 401 for logout
    when(mockHttpResponse.statusCode()).thenReturn(401);
    when(mockHttpResponse.body()).thenReturn("Session may have already expired");

    assertDoesNotThrow(() -> mosApiClient.logout(ENTITY_ID));
  }

  @Test
  void logout_failure_throwsMosApiExceptionForNetworkError() throws Exception {
    // First, login
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockHttpResponse)
        .thenThrow(new IOException("Network failed on logout"));
    mosApiClient.login(ENTITY_ID);

    // Then, test logout failure
    MosApiException exception =
        assertThrows(MosApiException.class, () -> mosApiClient.logout(ENTITY_ID));
    assertThat(exception).hasMessageThat().isEqualTo("An error occurred during logout.");
    assertThat(exception).hasCauseThat().isInstanceOf(IOException.class);
  }
}
