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

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import google.registry.privileges.secretmanager.SecretManagerClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Caches MoSAPI session cookies in Secret Manager to share state across pods.
 *
 * <p>This assumes that secrets named "mosapi_session_cookie_ENTITYID" are pre-created in Secret
 * Manager and that the service account has permission to read and add new versions to them.
 */
@Singleton
public class MosApiSessionCache {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String SECRET_PREFIX = "mosapi_session_cookie_";

  private final SecretManagerClient secretManagerClient;

  @Inject
  public MosApiSessionCache(SecretManagerClient secretManagerClient) {
    this.secretManagerClient = secretManagerClient;
  }

  private String getSecretName(String entityId) {
    return SECRET_PREFIX + entityId;
  }

  /**
   * Retrieves the session cookie for a given entityId.
   *
   * @return The cookie string (e.g., "id=...") or Optional.empty() if not found or invalid.
   */
  public Optional<String> get(String entityId) {
    String secretName = getSecretName(entityId);
    try {
      String cookie = secretManagerClient.getSecretData(secretName, Optional.of("latest"));
      // An empty string is considered an invalid/cleared cookie
      return Strings.isNullOrEmpty(cookie) ? Optional.empty() : Optional.of(cookie);
    } catch (Exception e) {
      // This is expected if the secret or version doesn't exist
      logger.atInfo().log("No session cookie found in Secret Manager for %s.", entityId);
      return Optional.empty();
    }
  }

  /**
   * Stores a new session cookie value for a given entityId.
   *
   * <p>This will create a new secret version.
   */
  public void store(String entityId, String cookieValue) {
    String secretName = getSecretName(entityId);
    try {
      secretManagerClient.addSecretVersion(secretName, cookieValue);
      logger.atInfo().log("Stored new MoSAPI session cookie for %s.", entityId);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Failed to store MoSAPI session cookie for %s.", entityId);
      throw new RuntimeException("Failed to store session cookie in Secret Manager.", e);
    }
  }

  /** Clears the cached session cookie for a given entityId by storing an empty value. */
  public void clear(String entityId) {
    store(entityId, "");
  }
}
