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

package google.registry.config;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.client.util.GenericData;
import com.google.api.client.util.StringUtils;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import google.registry.util.Clock;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.ServiceLoader;
import org.apache.commons.codec.binary.Base64;

/**
 * OAuth2 credentials for accessing Google Workspace APIs with domain-wide delegation. It fetches
 * access tokens using JSON Web Tokens (JWT) signed by a user-provided {@link ServiceAccountSigner}.
 *
 * <p>This class accepts the application-default-credential as {@code ServiceAccountSigner},
 * avoiding the need for exported private keys. In this case, the default credential user itself
 * (project-id@appspot.gserviceaccount.com on AppEngine) must have domain-wide delegation to the
 * Workspace APIs. The default credential user also must have the Token Creator role to itself.
 *
 * <p>If the user provides a credential {@code S} that carries its own private key, such as {@link
 * com.google.auth.oauth2.ServiceAccountCredentials}, this class can use {@code S} to impersonate
 * another service account {@code D} and gain delegated access as {@code D}, as long as S has the
 * Token Creator role to {@code D}. This usage is documented here for future reference.
 *
 * <p>As of October 2022, the functionalities described above are not implemented in the GCP Java
 * Auth library, although they are available in the Python library. We have filed a <a
 * href="https://github.com/googleapis/google-auth-library-java/issues/1064">feature request</a>.
 * This class is a stop-gap implementation.
 *
 * <p>The main body of this class is adapted from {@link
 * com.google.auth.oauth2.ServiceAccountCredentials} with cosmetic changes. The important changes
 * include the removal of all uses of the private key and the signing of the JWT (in {@link
 * #signAssertion}). We choose not to extend {@code ServiceAccountCredentials} because it would add
 * dependency to the non-public details of that class.
 */
public class DelegatedCredentials extends GoogleCredentials {

  private static final long serialVersionUID = 617127523756785546L;

  private static final String DEFAULT_TOKEN_URI = "https://accounts.google.com/o/oauth2/token";
  private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
  private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();

  private static final String VALUE_NOT_FOUND_MESSAGE = "%sExpected value %s not found.";
  private static final String VALUE_WRONG_TYPE_MESSAGE = "%sExpected %s value %s of wrong type.";
  private static final String PARSE_ERROR_PREFIX = "Error parsing token refresh response. ";

  private static final Duration MAX_TOKEN_REFRESH_DELAY = Duration.ofHours(1);

  private final ServiceAccountSigner signer;
  private final String delegatedServiceAccountUser;
  private final ImmutableList<String> scopes;
  private final String delegatingUserEmail;

  private final Clock clock;
  private final Duration tokenRefreshDelay;

  private final HttpTransportFactory transportFactory;

  /**
   * Creates a {@link DelegatedCredentials} instance that is self-signed by the signer, which must
   * have delegated access to the Workspace APIs.
   *
   * @param signer Signs for the generated JWT tokens. This may be the application default
   *     credential
   * @param scopes The scopes to use when generating JWT tokens
   * @param delegatingUserEmail The Workspace user whose permissions are delegated to the signer
   * @param clock Used for setting token expiration times.
   * @param tokenRefreshDelay The lifetime of each token. Should not exceed one hour according to
   *     GCP recommendations.
   */
  static DelegatedCredentials createSelfSignedDelegatedCredential(
      ServiceAccountSigner signer,
      Collection<String> scopes,
      String delegatingUserEmail,
      Clock clock,
      Duration tokenRefreshDelay) {
    return new DelegatedCredentials(
        signer, signer.getAccount(), scopes, delegatingUserEmail, clock, tokenRefreshDelay);
  }

  private DelegatedCredentials(
      ServiceAccountSigner signer,
      String delegatedServiceAccountUser,
      Collection<String> scopes,
      String delegatingUserEmail,
      Clock clock,
      Duration tokenRefreshDelay) {
    checkArgument(
        tokenRefreshDelay.getSeconds() <= MAX_TOKEN_REFRESH_DELAY.getSeconds(),
        "Max refresh delay must not exceed %s.",
        MAX_TOKEN_REFRESH_DELAY);

    this.signer = signer;
    this.delegatedServiceAccountUser = delegatedServiceAccountUser;
    this.scopes = ImmutableList.copyOf(scopes);
    this.delegatingUserEmail = delegatingUserEmail;

    this.clock = clock;
    this.tokenRefreshDelay = tokenRefreshDelay;

    this.transportFactory =
        getFromServiceLoader(
            HttpTransportFactory.class, DelegatedCredentials::provideHttpTransport);
  }

  /**
   * Refreshes the OAuth2 access token by getting a new access token using a JSON Web Token (JWT).
   */
  @Override
  public AccessToken refreshAccessToken() throws IOException {
    JsonFactory jsonFactory = JSON_FACTORY;
    long currentTime = clock.nowUtc().getMillis();
    String assertion = createAssertion(jsonFactory, currentTime);

    GenericData tokenRequest = new GenericData();
    tokenRequest.set("grant_type", GRANT_TYPE);
    tokenRequest.set("assertion", assertion);
    UrlEncodedContent content = new UrlEncodedContent(tokenRequest);

    HttpRequestFactory requestFactory = transportFactory.create().createRequestFactory();
    HttpRequest request =
        requestFactory.buildPostRequest(new GenericUrl(DEFAULT_TOKEN_URI), content);
    request.setParser(new JsonObjectParser(jsonFactory));

    request.setIOExceptionHandler(new HttpBackOffIOExceptionHandler(new ExponentialBackOff()));
    request.setUnsuccessfulResponseHandler(
        new HttpBackOffUnsuccessfulResponseHandler(new ExponentialBackOff())
            .setBackOffRequired(
                response -> {
                  int code = response.getStatusCode();
                  return (
                  // Server error --- includes timeout errors, which use 500 instead of 408
                  code / 100 == 5
                      // Forbidden error --- for historical reasons, used for rate_limit_exceeded
                      // errors instead of 429, but there currently seems no robust automatic way
                      // to
                      // distinguish these cases: see
                      // https://github.com/google/google-api-java-client/issues/662
                      || code == 403);
                }));

    HttpResponse response;
    try {
      response = request.execute();
    } catch (IOException e) {
      throw new IOException(
          String.format("Error getting access token for service account: %s", e.getMessage()), e);
    }

    GenericData responseData = response.parseAs(GenericData.class);
    String accessToken = validateString(responseData, "access_token", PARSE_ERROR_PREFIX);
    int expiresInSeconds = validateInt32(responseData, "expires_in", PARSE_ERROR_PREFIX);
    return new AccessToken(accessToken, clock.nowUtc().plusSeconds(expiresInSeconds).toDate());
  }

  String createAssertion(JsonFactory jsonFactory, long currentTime) throws IOException {
    JsonWebSignature.Header header = new JsonWebSignature.Header();
    header.setAlgorithm("RS256");
    header.setType("JWT");

    JsonWebToken.Payload payload = new JsonWebToken.Payload();
    payload.setIssuer(this.delegatedServiceAccountUser);
    payload.setIssuedAtTimeSeconds(currentTime / 1000);
    payload.setExpirationTimeSeconds(currentTime / 1000 + tokenRefreshDelay.getSeconds());
    payload.setSubject(delegatingUserEmail);
    payload.put("scope", Joiner.on(' ').join(scopes));
    payload.setAudience(DEFAULT_TOKEN_URI);

    return signAssertion(jsonFactory, header, payload);
  }

  String signAssertion(
      JsonFactory jsonFactory, JsonWebSignature.Header header, JsonWebToken.Payload payload)
      throws IOException {
    String content =
        Base64.encodeBase64URLSafeString(jsonFactory.toByteArray(header))
            + "."
            + Base64.encodeBase64URLSafeString(jsonFactory.toByteArray(payload));
    byte[] contentBytes = StringUtils.getBytesUtf8(content);
    byte[] signature = signer.sign(contentBytes); // Changed from ServiceAccountCredentials.
    return content + "." + Base64.encodeBase64URLSafeString(signature);
  }

  static HttpTransport provideHttpTransport() {
    return HTTP_TRANSPORT;
  }

  public static <T> T getFromServiceLoader(Class<? extends T> clazz, T defaultInstance) {
    return Iterables.getFirst(ServiceLoader.load(clazz), defaultInstance);
  }

  /** Return the specified string from JSON or throw a helpful error message. */
  static String validateString(Map<String, Object> map, String key, String errorPrefix)
      throws IOException {
    Object value = map.get(key);
    if (value == null) {
      throw new IOException(String.format(VALUE_NOT_FOUND_MESSAGE, errorPrefix, key));
    }
    if (!(value instanceof String)) {
      throw new IOException(String.format(VALUE_WRONG_TYPE_MESSAGE, errorPrefix, "string", key));
    }
    return (String) value;
  }

  /** Return the specified integer from JSON or throw a helpful error message. */
  static int validateInt32(Map<String, Object> map, String key, String errorPrefix)
      throws IOException {
    Object value = map.get(key);
    if (value == null) {
      throw new IOException(String.format(VALUE_NOT_FOUND_MESSAGE, errorPrefix, key));
    }
    if (value instanceof BigDecimal bigDecimalValue) {
      return bigDecimalValue.intValueExact();
    }
    if (!(value instanceof Integer)) {
      throw new IOException(String.format(VALUE_WRONG_TYPE_MESSAGE, errorPrefix, "integer", key));
    }
    return (Integer) value;
  }
}
