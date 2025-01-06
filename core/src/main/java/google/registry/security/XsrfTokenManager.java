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

package google.registry.security;

import static com.google.common.io.BaseEncoding.base64Url;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hashing;
import google.registry.model.server.ServerSecret;
import google.registry.util.Clock;
import java.util.List;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Helper class for generating and validate XSRF tokens. */
public final class XsrfTokenManager {

  /** HTTP header or cookie name used for transmitting XSRF tokens. */
  public static final String X_CSRF_TOKEN = "X-CSRF-Token";

  /** POST parameter used for transmitting XSRF tokens. */
  public static final String P_CSRF_TOKEN = "xsrfToken";

  /** Maximum age of an acceptable XSRF token. */
  private static final Duration XSRF_VALIDITY = Duration.standardDays(1);

  /** Token version identifier for version 1. */
  private static final String VERSION_1 = "1";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Clock clock;

  @Inject
  public XsrfTokenManager(Clock clock) {
    this.clock = clock;
  }

  /** Generates an XSRF token for a given user based on email address. */
  public String generateToken(String email) {
    checkArgumentNotNull(email);
    long timestampMillis = clock.nowUtc().getMillis();
    return encodeToken(ServerSecret.get().asBytes(), email, timestampMillis);
  }

  /**
   * Returns an XSRF token for the given server secret, user email, and timestamp.
   *
   * <p>The token format consists of three colon-delimited fields: the version number (currently 1),
   * the timestamp in milliseconds since the epoch, and the Base64url-encoded SHA-256 HMAC (using
   * the given secret key) of the user email and the timestamp millis separated by a tab character.
   *
   * <p>We use HMAC instead of a plain SHA-256 hash to avoid length-extension vulnerabilities.
   */
  private static String encodeToken(byte[] secret, String email, long timestampMillis) {
    String payload = Joiner.on('\t').skipNulls().join(email, timestampMillis);
    String hmac =
        base64Url().encode(Hashing.hmacSha256(secret).hashString(payload, UTF_8).asBytes());
    return Joiner.on(':').join(VERSION_1, timestampMillis, hmac);
  }

  /** Validates an XSRF token against the current logged-in user. */
  public boolean validateToken(String email, String token) {
    checkArgumentNotNull(token);
    List<String> tokenParts = Splitter.on(':').splitToList(token);
    if (tokenParts.size() != 3) {
      logger.atWarning().log("Malformed XSRF token: %s", token);
      return false;
    }
    if (!tokenParts.get(0).equals(VERSION_1)) {
      logger.atWarning().log("Unrecognized version in XSRF token: %s", token);
      return false;
    }
    String timePart = tokenParts.get(1);
    long timestampMillis;
    try {
      timestampMillis = Long.parseLong(timePart);
    } catch (NumberFormatException e) {
      logger.atWarning().log("Bad timestamp in XSRF token: %s", token);
      return false;
    }
    if (new DateTime(timestampMillis, UTC).plus(XSRF_VALIDITY).isBefore(clock.nowUtc())) {
      logger.atInfo().log("Expired timestamp in XSRF token: %s", token);
      return false;
    }
    // Reconstruct the token to verify validity.
    String reconstructedToken = encodeToken(ServerSecret.get().asBytes(), email, timestampMillis);
    if (!token.equals(reconstructedToken)) {
      logger.atWarning().log(
          "Reconstructed XSRF mismatch (got != expected): %s != %s", token, reconstructedToken);
      return false;
    }
    return true;
  }
}
