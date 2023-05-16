// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
package google.registry.util;

import static com.google.api.client.googleapis.auth.oauth2.GoogleOAuthConstants.TOKEN_SERVER_URL;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.GenericData;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdToken;
import com.google.auth.oauth2.IdTokenProvider;
import com.google.auth.oauth2.IdTokenProvider.Option;
import com.google.auth.oauth2.UserCredentials;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;

public abstract class OidcTokenUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private OidcTokenUtils() {}

  public static String createOidcToken(GoogleCredentials credentials, String clientId) {
    if (credentials instanceof UserCredentials) {
      try {
        return getIdTokenForUserCredential((UserCredentials) credentials, clientId);
      } catch (Exception e) {
        logger.atSevere().withCause(e).log(
            "Cannot generate OIDC token for credential %s", credentials);
        throw new RuntimeException(e);
      }
    } else {
      IdTokenProvider idTokenProvider = (IdTokenProvider) credentials;
      IdToken idToken = null;
      // Note: we use Option.FORMAT_FULL to make sure the JWT we receive contains the email
      // address (as is required by IAP)
      try {
        idToken =
            idTokenProvider.idTokenWithAudience(clientId, ImmutableList.of(Option.FORMAT_FULL));
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error when attempting to create OIDC token");
      }
      return idToken.getTokenValue();
    }
  }

  /**
   * Uses the saved desktop-app refresh token to acquire a token with the given audience.
   *
   * <p>This is lifted mostly from the Google Auth Library's {@link UserCredentials}
   * "doRefreshAccessToken" method (which is private and thus inaccessible) while adding in the
   * audience of the IAP client ID. The "idTokenWithAudience" method of that class does not support
   * setting custom audience, paradoxically.
   *
   * @see <a
   *     href="https://cloud.google.com/iap/docs/authentication-howto#authenticating_from_a_desktop_app">
   *     Authenticating from a desktop app</a>
   */
  private static String getIdTokenForUserCredential(UserCredentials credentials, String audience)
      throws GeneralSecurityException, IOException {
    GenericData tokenRequest = new GenericData();
    tokenRequest.set("client_id", credentials.getClientId());
    tokenRequest.set("client_secret", credentials.getClientSecret());
    tokenRequest.set("refresh_token", credentials.getRefreshToken());
    tokenRequest.set("audience", audience);
    tokenRequest.set("grant_type", "refresh_token");
    UrlEncodedContent content = new UrlEncodedContent(tokenRequest);

    HttpRequestFactory requestFactory =
        GoogleNetHttpTransport.newTrustedTransport().createRequestFactory();
    HttpRequest request =
        requestFactory.buildPostRequest(new GenericUrl(URI.create(TOKEN_SERVER_URL)), content);
    request.setParser(GsonFactory.getDefaultInstance().createJsonObjectParser());
    HttpResponse response = request.execute();
    return response.parseAs(GenericData.class).get("id_token").toString();
  }
}
