// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.request.auth;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserDao;
import google.registry.model.console.UserRoles;
import google.registry.testing.AppEngineExtension;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link NonGaeOAuthAuthenticationMechanism}. */
public class NonGaeOAuthAuthenticationMechanismTest {

  private static final ImmutableSet<String> REQUIRED_OAUTH_SCOPES =
      ImmutableSet.of("scope1", "scope2");
  private static final ImmutableSet<String> OAUTH_CLIENT_IDS =
      ImmutableSet.of("oauthClientId", "otherOauthClientId");

  @RegisterExtension
  final AppEngineExtension appEngine = AppEngineExtension.builder().withCloudSql().build();

  private NetHttpTransport httpTransport = mock(NetHttpTransport.class);
  private HttpServletRequest request = mock(HttpServletRequest.class);
  private HttpResponse httpResponse = mock(HttpResponse.class);

  private final NonGaeOAuthAuthenticationMechanism authenticationMechanism =
      new NonGaeOAuthAuthenticationMechanism(
          httpTransport, REQUIRED_OAUTH_SCOPES, OAUTH_CLIENT_IDS);

  @BeforeEach
  void beforeEach() throws IOException {
    when(request.getHeader(AUTHORIZATION)).thenReturn("Bearer token");
    HttpRequestFactory requestFactory = mock(HttpRequestFactory.class);
    when(httpTransport.createRequestFactory()).thenReturn(requestFactory);
    HttpRequest getRequest = mock(HttpRequest.class);
    when(requestFactory.buildGetRequest(any(GenericUrl.class))).thenReturn(getRequest);
    when(getRequest.execute()).thenReturn(httpResponse);
    when(httpResponse.getStatusCode()).thenReturn(HttpStatusCodes.STATUS_CODE_OK);
  }

  @Test
  void testSuccess_findsUser() throws Exception {
    User user =
        new User.Builder()
            .setEmailAddress("johndoe@theregistrar.com")
            .setGaiaId("gaiaId")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
                    .build())
            .build();
    UserDao.saveUser(user);
    // reload the user to pick up the SQL-created ID
    user = UserDao.loadUser("johndoe@theregistrar.com").get();
    setResponseJson(
        "{'aud': 'oauthClientId', 'scope': 'scope1 scope2', 'email': 'johndoe@theregistrar.com'}");
    assertThat(authenticationMechanism.authenticate(request))
        .isEqualTo(AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user)));
  }

  @Test
  void testFailure_missingHeader() {
    when(request.getHeader(AUTHORIZATION)).thenReturn(null);
    assertThat(authenticationMechanism.authenticate(request))
        .isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testFailure_invalidHeader() {
    when(request.getHeader(AUTHORIZATION)).thenReturn("invalidHeader");
    assertThat(authenticationMechanism.authenticate(request))
        .isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testFailure_invalidAuthToken() {
    when(httpResponse.getStatusCode()).thenReturn(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
    assertThat(authenticationMechanism.authenticate(request))
        .isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testFailure_unknownStatusCode() {
    when(httpResponse.getStatusCode()).thenReturn(HttpStatusCodes.STATUS_CODE_SERVICE_UNAVAILABLE);
    assertThat(authenticationMechanism.authenticate(request))
        .isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testFailure_ioException() throws Exception {
    when(httpResponse.getContent()).thenThrow(new IOException("exception"));
    assertThat(
            assertThrows(
                RuntimeException.class, () -> authenticationMechanism.authenticate(request)))
        .hasCauseThat()
        .isInstanceOf(IOException.class);
  }

  @Test
  void testFailure_invalidOauthClientId() throws Exception {
    setResponseJson("{'aud': 'badClientId'}");
    assertThat(authenticationMechanism.authenticate(request))
        .isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testFailure_missingScope() throws Exception {
    setResponseJson("{'aud': 'oauthClientId', 'scope': 'invalidScope'}");
    assertThat(authenticationMechanism.authenticate(request))
        .isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testFailure_partialScope() throws Exception {
    setResponseJson("{'aud': 'oauthClientId', 'scope': 'scope1'}");
    assertThat(authenticationMechanism.authenticate(request))
        .isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testFailure_noUser() throws Exception {
    setResponseJson(
        "{'aud': 'oauthClientId', 'scope': 'scope1 scope2', 'email': 'johndoe@theregistrar.com'}");
    assertThat(authenticationMechanism.authenticate(request))
        .isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  private void setResponseJson(String responseJson) throws IOException {
    when(httpResponse.getContent())
        .thenReturn(new ByteArrayInputStream(responseJson.getBytes(StandardCharsets.UTF_8)));
  }
}
