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

package google.registry.privileges.secretmanager;

import com.google.auto.value.AutoValue;
import com.google.cloud.secretmanager.v1.SecretVersion;
import com.google.common.collect.Streams;
import java.util.Optional;

/** A Cloud Secret Manager client for Nomulus, bound to a specific GCP project. */
public interface SecretManagerClient {

  /** Returns the project name with which this client is associated. */
  String getProject();

  /**
   * Creates a new secret in the Cloud Secret Manager with no data.
   *
   * <p>Use addVersion to add data to this secret.
   *
   * @param secretId The ID of the secret, must be unique in a project
   * @throws SecretAlreadyExistsException A secret with this secretId already exists
   */
  void createSecret(String secretId);

  /** Checks if a secret with the given {@code secretId} already exists. */
  boolean secretExists(String secretId);

  /** Returns all secret IDs in the Cloud Secret Manager. */
  Iterable<String> listSecrets();

  /** Returns the {@link SecretVersionState} of all secrets with {@code secretId}. */
  Iterable<SecretVersionState> listSecretVersions(String secretId);

  /** Creates a secret if it does not already exists. */
  default void createSecretIfAbsent(String secretId) {
    try {
      createSecret(secretId);
    } catch (SecretAlreadyExistsException ignore) {
      // Not a problem.
    }
  }

  /**
   * Returns the version strings of all secrets in the given {@code state} with {@code secretId}.
   */
  default Iterable<String> listSecretVersions(String secretId, SecretVersion.State state) {
    return () ->
        Streams.stream(listSecretVersions(secretId))
            .filter(secretVersionState -> secretVersionState.state().equals(state))
            .map(SecretVersionState::version)
            .iterator();
  }

  /**
   * Adds a new version of data to a secret.
   *
   * @param secretId The ID of the secret
   * @param data The secret data to be stored in Cloud Secret Manager, encoded in utf-8 charset
   * @return The version string of the newly added secret data
   */
  String addSecretVersion(String secretId, String data);

  /**
   * Returns the data of a secret at the given version.
   *
   * @param secretId The ID of the secret
   * @param version The version of the secret to fetch. If not provided, the {@code latest} version
   *     will be returned
   */
  String getSecretData(String secretId, Optional<String> version);

  /**
   * Enables a secret version.
   *
   * @param secretId The ID of the secret
   * @param version The version of the secret to fetch. If not provided, the {@code latest} version
   *     will be returned
   */
  void enableSecretVersion(String secretId, String version);

  /**
   * Disables a secret version.
   *
   * @param secretId The ID of the secret
   * @param version The version of the secret to fetch. If not provided, the {@code latest} version
   *     will be returned
   */
  void disableSecretVersion(String secretId, String version);

  /**
   * Destroys a secret version.
   *
   * @param secretId The ID of the secret
   * @param version The version of the secret to destroy
   */
  void destroySecretVersion(String secretId, String version);

  /**
   * Deletes a secret from the Secret Manager. All versions of this secret will be destroyed.
   *
   * @param secretId The ID of the secret to be deleted
   */
  void deleteSecret(String secretId);

  /** Contains the {@link SecretVersion.State State} of an secret version. */
  @AutoValue
  abstract class SecretVersionState {

    public abstract String secretId();

    public abstract String version();

    public abstract SecretVersion.State state();

    public static SecretVersionState of(
        String secretId, String version, SecretVersion.State state) {
      return new AutoValue_SecretManagerClient_SecretVersionState(secretId, version, state);
    }
  }

  /** Catch-all class for all SecretManager exceptions. */
  class SecretManagerException extends RuntimeException {
    SecretManagerException(Throwable cause) {
      super(cause);
    }
  }

  /** The secret to be created already exists. */
  class SecretAlreadyExistsException extends SecretManagerException {
    SecretAlreadyExistsException(Throwable cause) {
      super(cause);
    }
  }

  /**
   * The resource being requested in the Secret Manager does not exist.
   *
   * <p>The missing resource may be a secret version or the secret itself. They are grouped together
   * because it is not always possible to identify the type of the missing resource.
   */
  class NoSuchSecretResourceException extends SecretManagerException {
    NoSuchSecretResourceException(Throwable cause) {
      super(cause);
    }
  }
}
