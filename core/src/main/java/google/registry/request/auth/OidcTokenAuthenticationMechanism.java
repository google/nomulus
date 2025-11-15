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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.auth.oauth2.TokenVerifier.VerificationException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.User;
import google.registry.persistence.VKey;
import google.registry.request.auth.AuthModule.IapOidc;
import google.registry.request.auth.AuthModule.RegularOidc;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.util.RegistryEnvironment;
import google.registry.util.StopwatchLogger;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * An authentication mechanism that verifies the OIDC token.
 *
 * <p>Currently, two flavors are supported: one that checks for the OIDC token as a regular bearer
 * token, and another that checks for the OIDC token passed by IAP. In both cases, the {@link
 * AuthResult} with the highest {@link AuthLevel} possible is returned. So, if the email address for
 * which the token is minted exists both as a {@link User} and as a service account, the returned
 * {@link AuthResult} is at {@link AuthLevel#USER}.
 *
 * @see <a href="https://developers.google.com/identity/openid-connect/openid-connect">OpenID
 *     Connect </a>
 */
public abstract class OidcTokenAuthenticationMechanism implements AuthenticationMechanism {

  public static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // A workaround that allows "use" of the OIDC authenticator when running local testing, i.e.,
  // the RegistryTestServer
  private static AuthResult authResultForTesting = null;

  protected final TokenExtractor tokenExtractor;

  protected final TokenVerifier tokenVerifier;

  private final ImmutableSet<String> serviceAccountEmails;

  protected OidcTokenAuthenticationMechanism(
      ImmutableSet<String> serviceAccountEmails,
      TokenExtractor tokenExtractor,
      TokenVerifier tokenVerifier) {
    this.serviceAccountEmails = serviceAccountEmails;
    this.tokenExtractor = tokenExtractor;
    this.tokenVerifier = tokenVerifier;
  }

  @Override
  public AuthResult authenticate(HttpServletRequest request) {
    if (RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)
        && authResultForTesting != null) {
      logger.atWarning().log("Using AuthResult %s for testing.", authResultForTesting);
      return authResultForTesting;
    }
    final StopwatchLogger stopwatch = new StopwatchLogger();
    String rawIdToken = tokenExtractor.extract(request);
    stopwatch.tick("OidcTokenAuthenticationMechanism tokenExtractor extracted");
    if (rawIdToken == null) {
      return AuthResult.NOT_AUTHENTICATED;
    }
    JsonWebSignature token = null;
    try {
      String hostname = request.getServerName();
      String service = Splitter.on('.').split(hostname).iterator().next();
      if (request.getHeader("canary") != null) {
        service += "-canary";
      }
      token = tokenVerifier.verify(service, rawIdToken);
    } catch (Exception e) {
      logger.atInfo().withCause(e).log(
          "Failed OIDC verification attempt:\n%s",
          RegistryEnvironment.get().equals(RegistryEnvironment.PRODUCTION)
              ? "Raw token redacted in prod"
              : rawIdToken);
    }
    stopwatch.tick("OidcTokenAuthenticationMechanism token verified");
    if (token == null) {
      return AuthResult.NOT_AUTHENTICATED;
    }

    String email = (String) token.getPayload().get("email");
    if (email == null) {
      logger.atInfo().log("No email address from the OIDC token:\n%s", token.getPayload());
      return AuthResult.NOT_AUTHENTICATED;
    }

    // Short-circuit the user lookup for known service accounts.
    // This check bypasses the database lookup for high-volume
    // traffic from trusted system accounts to reduce database load.

    if (serviceAccountEmails.contains(email)) {
      return AuthResult.createApp(email);
    }
    logger.atInfo().log("No service account found for email address %s, loading the User", email);
    Optional<User> maybeUser =
        tm().transact(() -> tm().loadByKeyIfPresent(VKey.create(User.class, email)));
    stopwatch.tick("OidcTokenAuthenticationMechanism maybeUser loaded");
    if (maybeUser.isPresent()) {
      return AuthResult.createUser(maybeUser.get());
    }
    logger.atInfo().log("No end user found for email address %s", email);

    logger.atWarning().log(
        "The email address %s is not tied to a principal with access to Nomulus", email);
    return AuthResult.NOT_AUTHENTICATED;
  }

  @VisibleForTesting
  public static void setAuthResultForTesting(@Nullable AuthResult authResult) {
    checkState(
        RegistryEnvironment.get() == RegistryEnvironment.UNITTEST,
        "Explicitly setting auth result is only supported in tests");
    authResultForTesting = authResult;
  }

  @VisibleForTesting
  public static void unsetAuthResultForTesting() {
    checkState(
        RegistryEnvironment.get() == RegistryEnvironment.UNITTEST,
        "Explicitly unsetting auth result is only supported in tests");
    authResultForTesting = null;
  }

  @FunctionalInterface
  protected interface TokenExtractor {
    @Nullable
    String extract(HttpServletRequest request);
  }

  @FunctionalInterface
  protected interface TokenVerifier {
    @Nullable
    JsonWebSignature verify(@Nullable String service, String rawToken) throws VerificationException;
  }

  /**
   * A mechanism to authenticate HTTP requests that have gone through the GCP Identity-Aware Proxy.
   *
   * <p>When the user logs in, IAP provides a JWT in the {@code X-Goog-IAP-JWT-Assertion} header.
   * This header is included on all requests to IAP-enabled services (which should be all of them
   * that receive requests from the front end). The token verification libraries ensure that the
   * signed token has the proper audience and issuer.
   *
   * @see <a href="https://cloud.google.com/iap/docs/signed-headers-howto">the documentation on GCP
   *     IAP's signed headers for more information.</a>
   */
  static class IapOidcAuthenticationMechanism extends OidcTokenAuthenticationMechanism {

    @Inject
    protected IapOidcAuthenticationMechanism(
        @Config("allowedServiceAccountEmails") ImmutableSet<String> serviceAccountEmails,
        @IapOidc TokenExtractor tokenExtractor,
        @IapOidc TokenVerifier tokenVerifier) {
      super(serviceAccountEmails, tokenExtractor, tokenVerifier);
    }
  }

  /**
   * A mechanism to authenticate HTTP requests with an OIDC token as a bearer token.
   *
   * <p>If the endpoint is not behind IAP, we can try to authenticate the OIDC token supplied in the
   * request header directly. Ideally we would like all endpoints to be behind IAP, but being able
   * to authenticate the token directly provides us with some extra flexibility that comes in handy,
   * at least during the migration to GKE.
   *
   * @see <a href=https://datatracker.ietf.org/doc/html/rfc6750>Bearer Token Usage</a>
   */
  static class RegularOidcAuthenticationMechanism extends OidcTokenAuthenticationMechanism {

    @Inject
    protected RegularOidcAuthenticationMechanism(
        @Config("allowedServiceAccountEmails") ImmutableSet<String> serviceAccountEmails,
        @RegularOidc TokenExtractor tokenExtractor,
        @RegularOidc TokenVerifier tokenVerifier) {
      super(serviceAccountEmails, tokenExtractor, tokenVerifier);
    }
  }
}
