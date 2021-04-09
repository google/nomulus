// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Storage for 'keyring' secrets, backed by the Secret Manager.
 *
 * <p>This store is for secrets and credentials that must be set up manually and/or do not require
 * non-disruptive password changes, e.g., passwords to regulatory reporting websites, which are used
 * by cron jobs.
 *
 * <p>In contrast, the {@link SqlCredentialStore} is designed to support non-disruptive credential
 * changes with Cloud SQL.
 */
public class KeyringSecretStore {
  static final String SECRET_NAME_PREFIX = "keyring-";

  private final SecretManagerClient csmClient;

  @Inject
  public KeyringSecretStore(SecretManagerClient csmClient) {
    this.csmClient = csmClient;
  }

  public void createOrUpdateSecret(String label, byte[] data) {
    String secretId = decorateLabel(label);
    csmClient.createSecretIfAbsent(secretId);
    csmClient.addSecretVersion(secretId, new String(data, StandardCharsets.UTF_8));
  }

  public byte[] getSecret(String label) {
    return csmClient
        .getSecretData(decorateLabel(label), Optional.empty())
        .getBytes(StandardCharsets.UTF_8);
  }

  static String decorateLabel(String label) {
    checkArgument(!isNullOrEmpty(label), "null or empty label");
    return SECRET_NAME_PREFIX + label;
  }
}
