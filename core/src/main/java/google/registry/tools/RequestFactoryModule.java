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

package google.registry.tools;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.util.GenericData;
import com.google.auth.oauth2.UserCredentials;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.GoogleCredentialsBundle;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/**
 * Module for providing the HttpRequestFactory.
 *
 * <p>Localhost connections go to the App Engine dev server. The dev server differs from most HTTP
 * connections in that they don't require OAuth2 credentials, but instead require a special cookie.
 */
@Module
class RequestFactoryModule {

  static final int REQUEST_TIMEOUT_MS = 10 * 60 * 1000;

  /**
   * Server to use if we want to manually request an IAP ID token
   *
   * <p>If we need to have an IAP-enabled audience, we can use the existing refresh token and the
   * IAP client ID audience to request an IAP-enabled ID token. This token is read and used by
   * {@link google.registry.request.auth.IapHeaderAuthenticationMechanism}, and it requires that the
   * user have a {@link google.registry.model.console.User} object present in the database.
   */
  private static final GenericUrl TOKEN_SERVER_URL =
      new GenericUrl(URI.create("https://oauth2.googleapis.com/token"));

  @Provides
  static HttpRequestFactory provideHttpRequestFactory(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("iapClientId") Optional<String> iapClientId) {
    if (RegistryConfig.areServersLocal()) {
      return new NetHttpTransport()
          .createRequestFactory(
              request ->
                  request
                      .getHeaders()
                      .setCookie("dev_appserver_login=test@example.com:true:1858047912411"));
    } else {
      return new NetHttpTransport()
          .createRequestFactory(
              request -> {
                // Use the standard credential initializer to set the Authorization header
                credentialsBundle.getHttpRequestInitializer().initialize(request);
                // If using IAP, use the refresh token to acquire an IAP-enabled ID token and use
                // that for authentication.
                if (iapClientId.isPresent()) {
                  String idToken = getIdToken(credentialsBundle, iapClientId.get());
                  // Set the Proxy-Authentication header so that IAP can read from it, see
                  // https://cloud.google.com/iap/docs/authentication-howto#authenticating_from_proxy-authorization_header
                  request.getHeaders().set("Proxy-Authorization", "Bearer " + idToken);
                }
                // GAE request times out after 10 min, so here we set the timeout to 10 min. This is
                // needed to support some nomulus commands like updating premium lists that take
                // a lot of time to complete.
                // See
                // https://developers.google.com/api-client-library/java/google-api-java-client/errors
                request.setConnectTimeout(REQUEST_TIMEOUT_MS);
                request.setReadTimeout(REQUEST_TIMEOUT_MS);
              });
    }
  }

  /**
   * Uses the saved desktop-app refresh token to acquire an IAP ID token.
   *
   * <p>This is lifted mostly from the Google Auth Library's {@link UserCredentials}
   * "doRefreshAccessToken" method (which is private and thus inaccessible) while adding in the
   * audience of the IAP client ID. That addition of the audience is what allows us to satisfy IAP
   * auth. See
   * https://cloud.google.com/iap/docs/authentication-howto#authenticating_from_a_desktop_app for
   * more details.
   */
  private static String getIdToken(GoogleCredentialsBundle credentialsBundle, String iapClientId)
      throws IOException {
    UserCredentials credentials = (UserCredentials) credentialsBundle.getGoogleCredentials();
    GenericData tokenRequest = new GenericData();
    tokenRequest.set("client_id", credentials.getClientId());
    tokenRequest.set("client_secret", credentials.getClientSecret());
    tokenRequest.set("refresh_token", credentials.getRefreshToken());
    tokenRequest.set("audience", iapClientId);
    tokenRequest.set("grant_type", "refresh_token");
    UrlEncodedContent content = new UrlEncodedContent(tokenRequest);

    HttpRequestFactory requestFactory = credentialsBundle.getHttpTransport().createRequestFactory();
    HttpRequest request = requestFactory.buildPostRequest(TOKEN_SERVER_URL, content);
    request.setParser(credentialsBundle.getJsonFactory().createJsonObjectParser());
    HttpResponse response = request.execute();
    return response.parseAs(GenericData.class).get("id_token").toString();
  }
}
