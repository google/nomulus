// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.transaction;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.function.Predicate.not;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.sql.CredentialFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Supplier class to provide credentials for the Cloud SQL JDBC Socket Factory.
 *
 * <p>When {@code CLOUDSDK_CORE_REQUEST_REASON} is set in the environment, outgoing Cloud SQL Admin
 * API requests also include it in the {@code X-Goog-Request-Reason} HTTP header.
 */
public final class CloudSqlCredentialSupplier implements CredentialFactory {

  private static final String REQUEST_REASON_ENV_VAR = "CLOUDSDK_CORE_REQUEST_REASON";
  private static final String REQUEST_REASON_HEADER = "X-Goog-Request-Reason";
  private static final CharMatcher PRINTABLE_ASCII = CharMatcher.inRange(' ', '~');

  @Nullable private static volatile SupplierConfig config;

  /** Required public no-arg constructor for {@link CredentialFactory} reflective instantiation. */
  public CloudSqlCredentialSupplier() {}

  /** Initializes the supplier with the given credential and environment request reason, if any. */
  public static void setupCredentialSupplier(GoogleCredentials credential) {
    setupCredentialSupplier(credential, System.getenv(REQUEST_REASON_ENV_VAR));
  }

  @VisibleForTesting
  static void setupCredentialSupplier(
      GoogleCredentials credential, @Nullable String requestReason) {
    GoogleCredentials validatedCredential = checkNotNull(credential, "credential");
    Optional<String> normalizedReason = normalizeRequestReason(requestReason);
    config = new SupplierConfig(validatedCredential, normalizedReason);
    System.setProperty(
        CredentialFactory.CREDENTIAL_FACTORY_PROPERTY, CloudSqlCredentialSupplier.class.getName());
  }

  @VisibleForTesting
  static void resetForTesting() {
    config = null;
  }

  @Override
  public HttpRequestInitializer create() {
    SupplierConfig currentConfig = getConfig();
    Optional<String> requestReason = currentConfig.requestReason();
    if (requestReason.isEmpty()) {
      return new HttpCredentialsAdapter(currentConfig.credential());
    }
    return new RequestReasonCredentialsAdapter(currentConfig.credential(), requestReason.get());
  }

  @Override
  public GoogleCredentials getCredentials() {
    return getConfig().credential();
  }

  private static SupplierConfig getConfig() {
    SupplierConfig currentConfig = config;
    checkState(currentConfig != null, "CloudSqlCredentialSupplier has not been initialized");
    return currentConfig;
  }

  private static Optional<String> normalizeRequestReason(@Nullable String requestReason) {
    // Use Guava's CharMatcher.whitespace() rather than String.strip() so Unicode non-breaking
    // spaces (such as U+00A0) are also trimmed.
    Optional<String> normalized =
        Optional.ofNullable(requestReason)
            .map(CharMatcher.whitespace()::trimFrom)
            .filter(not(String::isEmpty));
    normalized.ifPresent(
        reason ->
            checkArgument(
                PRINTABLE_ASCII.matchesAllOf(reason),
                "%s must contain only printable ASCII characters",
                REQUEST_REASON_ENV_VAR));
    return normalized;
  }

  private record SupplierConfig(GoogleCredentials credential, Optional<String> requestReason) {}

  private static final class RequestReasonCredentialsAdapter extends HttpCredentialsAdapter {

    private final String requestReason;

    private RequestReasonCredentialsAdapter(GoogleCredentials credential, String requestReason) {
      super(credential);
      this.requestReason = requestReason;
    }

    @Override
    public void initialize(HttpRequest request) throws IOException {
      super.initialize(request);
      request.getHeaders().set(REQUEST_REASON_HEADER, requestReason);
    }
  }
}
