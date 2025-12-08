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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.reporting.mosapi.MosApiClient.InvalidCredentialsException;
import google.registry.reporting.mosapi.MosApiClient.IpAddressNotAllowedException;
import google.registry.reporting.mosapi.MosApiClient.MosApiException;
import google.registry.reporting.mosapi.MosApiClient.RateLimitExceededException;
import google.registry.util.HttpUtils;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MosApiClientTest {
  private static final String TEST_URL = "https://example.com";
  private static final String ENTITY_TYPE = "ry";
  private static final String ENTITY_ID = "test-id";
  private static final String USERNAME = "testuser";
  private static final String PASSWORD = "testpassword";
  private static final String LOGIN_URL = "https://example.com/ry/test-id/login";
  private static final String LOGOUT_URL = "https://example.com/ry/test-id/logout";
  private static final String GET_URL = "https://example.com/ry/test-id/get-path";
  private static final String POST_URL = "https://example.com/ry/test-id/post-path";
  private static final String POST_BODY = "{\"key\":\"value\"}";
  private static final String COOKIE_HEADER = "id=test-cookie-123";
  private static final String SET_COOKIE_HEADER = "id=test-cookie-123; expires=...; path=/";
  private static final String EXPIRED_COOKIE_HEADER = "id=expired-cookie-456";

  @Mock private HttpClient.Builder mockHttpClientBuilder;
  @Mock private HttpClient mockHttpClient;
  @Mock private HttpResponse<String> mockHttpResponse;
  @Mock private Function<String, String> mockUsernameProvider;
  @Mock private Function<String, String> mockPasswordProvider;
  @Mock private MosApiSessionCache mockMosApiSessionCache;

  @Captor private ArgumentCaptor<Map<String, String>> headersCaptor;

  private MosApiClient mosApiClient;
  private MockedStatic<HttpUtils> httpUtilsMock;

  @BeforeEach
  void setUp() {
    // Mock the static HttpUtils class
    httpUtilsMock = Mockito.mockStatic(HttpUtils.class);
    // Mock the HttpClient builder chain
    when(mockHttpClientBuilder.build()).thenReturn(mockHttpClient);
    // Mock provider functions. Use lenient() to avoid UnnecessaryStubbingException
    // since not all tests will trigger a login and use these mocks.

    mosApiClient =
        new MosApiClient(
            mockHttpClientBuilder,
            TEST_URL,
            ENTITY_TYPE,
            mockUsernameProvider,
            mockPasswordProvider,
            mockMosApiSessionCache);
  }

  @AfterEach
  void tearDown() {
    // Close the static mock
    httpUtilsMock.close();
  }

  @Test
  void login_success() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(200);
    // Mock the headers() call
    HttpHeaders mockHeaders =
        HttpHeaders.of(Map.of("Set-Cookie", List.of(SET_COOKIE_HEADER)), (a, b) -> true);
    when(mockHttpResponse.headers()).thenReturn(mockHeaders);
    // Mock the underlying HttpUtils.sendPostRequest call
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenReturn(mockHttpResponse);

    assertDoesNotThrow(() -> mosApiClient.login(ENTITY_ID));

    // Verify sendPostRequest was called with the correct URL and Auth
    httpUtilsMock.verify(
        () ->
            HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), headersCaptor.capture()));
    assertThat(headersCaptor.getValue()).containsKey("Authorization");

    // Verify the cookie was stored in the cache
    verify(mockMosApiSessionCache).store(ENTITY_ID, COOKIE_HEADER);
  }

  @Test
  void login_success_noCookieHeader_throwsException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(200);
    // Mock empty headers
    HttpHeaders mockHeaders = HttpHeaders.of(Collections.emptyMap(), (a, b) -> true);
    when(mockHttpResponse.headers()).thenReturn(mockHeaders);
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenReturn(mockHttpResponse);

    MosApiException e = assertThrows(MosApiException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("Login succeeded but server did not return a Set-Cookie header.");
    verifyNoInteractions(mockMosApiSessionCache);
  }

  @Test
  void login_failure_throwsInvalidCredentialsException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(401);
    when(mockHttpResponse.body()).thenReturn("Auth Failed");
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenReturn(mockHttpResponse);

    InvalidCredentialsException e =
        assertThrows(InvalidCredentialsException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(e).hasMessageThat().isEqualTo("Auth Failed");
  }

  @Test
  void login_failure_throwsIpAddressNotAllowedException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(403);
    when(mockHttpResponse.body()).thenReturn("Forbidden");
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenReturn(mockHttpResponse);

    IpAddressNotAllowedException e =
        assertThrows(IpAddressNotAllowedException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(e).hasMessageThat().isEqualTo("Forbidden");
  }

  @Test
  void login_failure_throwsRateLimitExceededException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(429);
    when(mockHttpResponse.body()).thenReturn("Too Many Requests");
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenReturn(mockHttpResponse);

    RateLimitExceededException e =
        assertThrows(RateLimitExceededException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(e).hasMessageThat().isEqualTo("Too Many Requests");
  }

  @Test
  void login_failure_throwsMosApiExceptionForUnexpectedCode() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(500);
    when(mockHttpResponse.body()).thenReturn("Internal Server Error");
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenReturn(mockHttpResponse);

    MosApiException exception =
        assertThrows(MosApiException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(exception)
        .hasMessageThat()
        .contains("Login failed with unexpected status code: 500 - Internal Server Error");
  }

  @Test
  void login_failure_throwsMosApiExceptionForNetworkError() throws Exception {
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenThrow(new IOException("Network failed"));

    MosApiException exception =
        assertThrows(MosApiException.class, () -> mosApiClient.login(ENTITY_ID));
    assertThat(exception).hasMessageThat().isEqualTo("An error occurred during login.");
    assertThat(exception).hasCauseThat().isInstanceOf(IOException.class);
  }

  @Test
  void logout_success_withCookie() throws Exception {
    // Mock that we have a cached cookie
    when(mockMosApiSessionCache.get(ENTITY_ID)).thenReturn(Optional.of(COOKIE_HEADER));
    when(mockHttpResponse.statusCode()).thenReturn(200);
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenReturn(mockHttpResponse);

    assertDoesNotThrow(() -> mosApiClient.logout(ENTITY_ID));

    // Verify the POST request was sent with the cookie
    httpUtilsMock.verify(
        () ->
            HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGOUT_URL), headersCaptor.capture()));
    assertThat(headersCaptor.getValue()).containsEntry("Cookie", COOKIE_HEADER);

    // Verify the cache was cleared in the 'finally' block
    verify(mockMosApiSessionCache).clear(ENTITY_ID);
  }

  @Test
  void logout_success_noCookie() throws Exception {
    // Mock that we have no cached cookie
    when(mockMosApiSessionCache.get(ENTITY_ID)).thenReturn(Optional.empty());
    when(mockHttpResponse.statusCode()).thenReturn(200);
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenReturn(mockHttpResponse);

    assertDoesNotThrow(() -> mosApiClient.logout(ENTITY_ID));

    // Verify the POST request was sent *without* a cookie
    httpUtilsMock.verify(
        () ->
            HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGOUT_URL), headersCaptor.capture()));
    assertThat(headersCaptor.getValue()).isEmpty();

    // Verify the cache was cleared
    verify(mockMosApiSessionCache).clear(ENTITY_ID);
  }

  @Test
  void logout_failure_logsWarningOn401() throws Exception {
    when(mockMosApiSessionCache.get(ENTITY_ID)).thenReturn(Optional.of(COOKIE_HEADER));
    when(mockHttpResponse.statusCode()).thenReturn(401);
    when(mockHttpResponse.body()).thenReturn("Session may have already expired");
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenReturn(mockHttpResponse);

    assertDoesNotThrow(() -> mosApiClient.logout(ENTITY_ID));
    // Verify the cache was *still* cleared in the 'finally' block
    verify(mockMosApiSessionCache).clear(ENTITY_ID);
  }

  @Test
  void executeGetRequest_success_withCachedCookie() throws Exception {
    when(mockMosApiSessionCache.get(ENTITY_ID)).thenReturn(Optional.of(COOKIE_HEADER));
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("Success Data");
    httpUtilsMock
        .when(() -> HttpUtils.sendGetRequest(any(HttpClient.class), anyString(), anyMap()))
        .thenReturn(mockHttpResponse);

    String result =
        mosApiClient.executeGetRequest(
            ENTITY_ID, "/get-path", Collections.emptyMap(), Collections.emptyMap());

    assertThat(result).isEqualTo("Success Data");
    // Verify HttpUtils was called with the correct URL and headers
    httpUtilsMock.verify(
        () -> HttpUtils.sendGetRequest(eq(mockHttpClient), eq(GET_URL), headersCaptor.capture()));
    assertThat(headersCaptor.getValue()).containsEntry("Cookie", COOKIE_HEADER);
    // Verify login was NOT called (no HttpUtils.sendPostRequest)
    httpUtilsMock.verify(
        () -> HttpUtils.sendPostRequest(any(HttpClient.class), anyString(), anyMap()), times(0));
  }

  @Test
  void executeGetRequest_noCookie_logsInAndSucceeds() throws Exception {
    // 1. Mock cache returning empty
    when(mockMosApiSessionCache.get(ENTITY_ID))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(COOKIE_HEADER)); // 2. Mock cache returning new cookie after login

    // 3. Mock login response
    HttpResponse<String> mockLoginResponse = mock(HttpResponse.class);
    when(mockLoginResponse.statusCode()).thenReturn(200);
    HttpHeaders mockHeaders =
        HttpHeaders.of(Map.of("Set-Cookie", List.of(SET_COOKIE_HEADER)), (a, b) -> true);
    when(mockLoginResponse.headers()).thenReturn(mockHeaders);
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()))
        .thenReturn(mockLoginResponse);

    // 4. Mock GET response
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("Success Data");
    httpUtilsMock
        .when(() -> HttpUtils.sendGetRequest(eq(mockHttpClient), eq(GET_URL), anyMap()))
        .thenReturn(mockHttpResponse);

    String result =
        mosApiClient.executeGetRequest(
            ENTITY_ID, "/get-path", Collections.emptyMap(), Collections.emptyMap());

    assertThat(result).isEqualTo("Success Data");
    // Verify login was called
    httpUtilsMock.verify(
        () -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()));
    // Verify cookie was stored
    verify(mockMosApiSessionCache).store(ENTITY_ID, COOKIE_HEADER);
    // Verify GET was called
    httpUtilsMock.verify(
        () ->
            HttpUtils.sendGetRequest(
                eq(mockHttpClient), eq(GET_URL), eq(ImmutableMap.of("Cookie", COOKIE_HEADER))));
  }

  @Test
  void executeGetRequest_sessionExpired_relogsInAndSucceeds() throws Exception {
    // 1. Mock cache returning expired cookie
    when(mockMosApiSessionCache.get(ENTITY_ID))
        .thenReturn(Optional.of(EXPIRED_COOKIE_HEADER)) // 5. Mock cache returning new cookie
        .thenReturn(Optional.of(COOKIE_HEADER));

    // 2. Mock 401 response for GET
    HttpResponse<String> mock401Response = mock(HttpResponse.class);
    when(mock401Response.statusCode()).thenReturn(401);
    // This is the specific "session expired" message

    // 3. Mock 200 response for GET (the retry)
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("Success Data");
    httpUtilsMock
        .when(() -> HttpUtils.sendGetRequest(eq(mockHttpClient), eq(GET_URL), anyMap()))
        .thenReturn(mock401Response) // First call fails
        .thenReturn(mockHttpResponse); // Second call succeeds

    // 4. Mock login response
    HttpResponse<String> mockLoginResponse = mock(HttpResponse.class);
    when(mockLoginResponse.statusCode()).thenReturn(200);
    HttpHeaders mockHeaders =
        HttpHeaders.of(Map.of("Set-Cookie", List.of(SET_COOKIE_HEADER)), (a, b) -> true);
    when(mockLoginResponse.headers()).thenReturn(mockHeaders);
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()))
        .thenReturn(mockLoginResponse);

    String result =
        mosApiClient.executeGetRequest(
            ENTITY_ID, "/get-path", Collections.emptyMap(), Collections.emptyMap());

    assertThat(result).isEqualTo("Success Data");
    // Verify GET was called twice
    httpUtilsMock.verify(
        () ->
            HttpUtils.sendGetRequest(
                eq(mockHttpClient),
                eq(GET_URL),
                eq(ImmutableMap.of("Cookie", EXPIRED_COOKIE_HEADER))));
    httpUtilsMock.verify(
        () ->
            HttpUtils.sendGetRequest(
                eq(mockHttpClient), eq(GET_URL), eq(ImmutableMap.of("Cookie", COOKIE_HEADER))));
    // Verify login was called
    httpUtilsMock.verify(
        () -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()));
    // Verify cookie was stored
    verify(mockMosApiSessionCache).store(ENTITY_ID, COOKIE_HEADER);
  }

  @Test
  void executeGetRequest_reloginFails429_throwsTryAgainLater() throws Exception {
    // 1. Mock cache returning no cookie
    when(mockMosApiSessionCache.get(ENTITY_ID)).thenReturn(Optional.empty());

    // 2. Mock 429 response for login
    HttpResponse<String> mock429Response = mock(HttpResponse.class);
    when(mock429Response.statusCode()).thenReturn(429);
    when(mock429Response.body()).thenReturn("Too Many Requests");
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()))
        .thenReturn(mock429Response);

    MosApiException e =
        assertThrows(
            MosApiException.class,
            () ->
                mosApiClient.executeGetRequest(
                    ENTITY_ID, "/get-path", Collections.emptyMap(), Collections.emptyMap()));

    assertThat(e).hasMessageThat().isEqualTo("Try running after some time");
    // Verify GET was *not* called
    httpUtilsMock.verify(
        () -> HttpUtils.sendGetRequest(any(HttpClient.class), anyString(), anyMap()), times(0));
    // Verify login was attempted
    httpUtilsMock.verify(
        () -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()));
  }

  @Test
  void executeGetRequest_reloginFails401_throwsAuthFailed() throws Exception {
    // 1. Mock cache returning no cookie
    when(mockMosApiSessionCache.get(ENTITY_ID)).thenReturn(Optional.empty());

    // 2. Mock 401 response for login (Invalid Credentials)
    HttpResponse<String> mock401Response = mock(HttpResponse.class);
    when(mock401Response.statusCode()).thenReturn(401);
    when(mock401Response.body()).thenReturn("Invalid Credentials");
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()))
        .thenReturn(mock401Response);

    MosApiException e =
        assertThrows(
            MosApiException.class,
            () ->
                mosApiClient.executeGetRequest(
                    ENTITY_ID, "/get-path", Collections.emptyMap(), Collections.emptyMap()));

    assertThat(e).hasMessageThat().isEqualTo("Automatic re-login failed.");
    assertThat(e).hasCauseThat().isInstanceOf(InvalidCredentialsException.class);
    assertThat(e.getCause()).hasMessageThat().isEqualTo("Invalid Credentials");
  }

  @Test
  void executeGetRequest_any401Error_relogsInAndSucceeds() throws Exception {
    // This test verifies that *any* 401 error message triggers a re-login,
    // per the new simplified logic in the active MosApiClient.java file.

    // 1. Mock cache returning expired cookie
    when(mockMosApiSessionCache.get(ENTITY_ID))
        .thenReturn(Optional.of(EXPIRED_COOKIE_HEADER))
        .thenReturn(Optional.of(COOKIE_HEADER)); // After login

    // 2. Mock 401 response for GET
    HttpResponse<String> mock401Response = mock(HttpResponse.class);
    when(mock401Response.statusCode()).thenReturn(401);

    // 3. Mock 200 response for GET (the retry)
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("Success Data");
    httpUtilsMock
        .when(() -> HttpUtils.sendGetRequest(eq(mockHttpClient), eq(GET_URL), anyMap()))
        .thenReturn(mock401Response) // First call fails
        .thenReturn(mockHttpResponse); // Second call succeeds

    // 4. Mock login response
    HttpResponse<String> mockLoginResponse = mock(HttpResponse.class);
    when(mockLoginResponse.statusCode()).thenReturn(200);
    HttpHeaders mockHeaders =
        HttpHeaders.of(Map.of("Set-Cookie", List.of(SET_COOKIE_HEADER)), (a, b) -> true);
    when(mockLoginResponse.headers()).thenReturn(mockHeaders);
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()))
        .thenReturn(mockLoginResponse);

    // The call should NOT throw an exception
    String result =
        assertDoesNotThrow(
            () ->
                mosApiClient.executeGetRequest(
                    ENTITY_ID, "/get-path", Collections.emptyMap(), Collections.emptyMap()));

    assertThat(result).isEqualTo("Success Data");

    // Verify GET was called twice (initial attempt + retry)
    httpUtilsMock.verify(
        () ->
            HttpUtils.sendGetRequest(
                eq(mockHttpClient),
                eq(GET_URL),
                eq(ImmutableMap.of("Cookie", EXPIRED_COOKIE_HEADER))));
    httpUtilsMock.verify(
        () ->
            HttpUtils.sendGetRequest(
                eq(mockHttpClient), eq(GET_URL), eq(ImmutableMap.of("Cookie", COOKIE_HEADER))));

    // Verify login WAS called
    httpUtilsMock.verify(
        () -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()), times(1));
    // Verify cookie was stored
    verify(mockMosApiSessionCache).store(ENTITY_ID, COOKIE_HEADER);
  }

  @Test
  void executePostRequest_success_withCachedCookie() throws Exception {
    when(mockMosApiSessionCache.get(ENTITY_ID)).thenReturn(Optional.of(COOKIE_HEADER));
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("POST Success");
    httpUtilsMock
        .when(
            () ->
                HttpUtils.sendPostRequest(
                    any(HttpClient.class), anyString(), anyMap(), anyString()))
        .thenReturn(mockHttpResponse);

    String result =
        mosApiClient.executePostRequest(ENTITY_ID, "/post-path", POST_BODY, Collections.emptyMap());

    assertThat(result).isEqualTo("POST Success");
    // Verify sendPostRequest (4-arg) was called
    httpUtilsMock.verify(
        () ->
            HttpUtils.sendPostRequest(
                eq(mockHttpClient), eq(POST_URL), headersCaptor.capture(), eq(POST_BODY)));

    // Verify headers
    Map<String, String> capturedHeaders = headersCaptor.getValue();
    assertThat(capturedHeaders).containsEntry("Cookie", COOKIE_HEADER);
    assertThat(capturedHeaders).containsEntry("Content-Type", "application/json");

    // Verify login was NOT called
    verify(mockMosApiSessionCache, times(1)).get(ENTITY_ID);
    verifyNoMoreInteractions(mockMosApiSessionCache);
  }

  @Test
  void executePostRequest_noCookie_logsInAndSucceeds() throws Exception {
    // 1. Mock cache
    when(mockMosApiSessionCache.get(ENTITY_ID))
        .thenReturn(Optional.empty()) // first call
        .thenReturn(Optional.of(COOKIE_HEADER)); // second call

    // 2. Mock login response
    HttpResponse<String> mockLoginResponse = mock(HttpResponse.class);
    when(mockLoginResponse.statusCode()).thenReturn(200);
    HttpHeaders mockHeaders =
        HttpHeaders.of(Map.of("Set-Cookie", List.of(SET_COOKIE_HEADER)), (a, b) -> true);
    when(mockLoginResponse.headers()).thenReturn(mockHeaders);
    httpUtilsMock
        .when(() -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()))
        .thenReturn(mockLoginResponse);

    // 3. Mock POST response
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("POST Success");
    httpUtilsMock
        .when(
            () ->
                HttpUtils.sendPostRequest(eq(mockHttpClient), eq(POST_URL), anyMap(), anyString()))
        .thenReturn(mockHttpResponse);

    String result =
        mosApiClient.executePostRequest(ENTITY_ID, "/post-path", POST_BODY, Collections.emptyMap());

    assertThat(result).isEqualTo("POST Success");
    // Verify login was called
    httpUtilsMock.verify(
        () -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(LOGIN_URL), anyMap()));
    // Verify POST was called
    httpUtilsMock.verify(
        () -> HttpUtils.sendPostRequest(eq(mockHttpClient), eq(POST_URL), anyMap(), eq(POST_BODY)));
  }

  @Test
  void executePostRequest_noBody() throws Exception {
    when(mockMosApiSessionCache.get(ENTITY_ID)).thenReturn(Optional.of(COOKIE_HEADER));
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("POST Success");
    httpUtilsMock
        .when(
            () ->
                HttpUtils.sendPostRequest(
                    any(HttpClient.class), anyString(), anyMap(), anyString()))
        .thenReturn(mockHttpResponse);

    String result =
        mosApiClient.executePostRequest(ENTITY_ID, "/post-path", null, Collections.emptyMap());

    assertThat(result).isEqualTo("POST Success");
    // Verify sendPostRequest (4-arg) was called with an empty body
    httpUtilsMock.verify(
        () ->
            HttpUtils.sendPostRequest(
                eq(mockHttpClient), eq(POST_URL), headersCaptor.capture(), eq("")));

    // Verify headers (no Content-Type)
    Map<String, String> capturedHeaders = headersCaptor.getValue();
    assertThat(capturedHeaders).containsEntry("Cookie", COOKIE_HEADER);
    assertThat(capturedHeaders).doesNotContainKey("Content-Type");
  }
}
