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

import com.google.cloud.secretmanager.v1.SecretVersionName;
import google.registry.config.RegistryConfig.Config;
import google.registry.privileges.secretmanager.SecretManagerClient.NoSuchSecretResourceException;
import google.registry.privileges.secretmanager.SecretManagerClient.SecretAlreadyExistsException;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Storage of SQL users' login credentials, backed by Cloud Secret Manager.
 *
 * <p>A user's credential is stored with one level of indirection using two secret IDs: Each version
 * of the <em>credential data</em> is stored as follows: its secret ID is determined by {@link
 * #getCredentialDataSecretId(SqlUser, String dbInstance)}, and the value of each version is a
 * {@link SqlCredential}, serialized using {@link SqlCredential#toFormattedString}. The 'live'
 * version of the credential is saved under the 'live pointer' secret explained below.
 *
 * <p>The pointer to the 'live' version of the credential data is stored as follows: its secret ID
 * is determined by {@link #getLiveLabelSecretId(SqlUser, String dbInstance)}; and the value of each
 * version is a {@link SecretVersionName} in String form, pointing to a version of the credential
 * data. Only the 'latest' version of this secret should be used. It is guaranteed to be valid.
 *
 * <p>The indirection in credential storage makes it easy to handle failures in the credential
 * change process.
 */
public class SqlCredentialStore {
  private final SecretManagerClient csmClient;
  private final String dbInstance;

  @Inject
  SqlCredentialStore(
      SecretManagerClient csmClient, @Config("cloudSqlDbInstanceName") String dbInstance) {
    this.csmClient = csmClient;
    this.dbInstance = dbInstance;
  }

  public SqlCredential getCredential(SqlUser user) {
    SecretVersionName credentialName = getLiveCredentialSecretVersion(user);
    return SqlCredential.fromFormattedString(
        csmClient.getSecretData(
            credentialName.getSecret(), Optional.of(credentialName.getSecretVersion())));
  }

  public void createOrUpdateCredential(SqlUser user, String password) {
    SecretVersionName dataName = saveCredentialData(user, password);
    saveLiveLabel(user, dataName);
  }

  public void deleteCredential(SqlUser user) {
    try {
      csmClient.deleteSecret(getCredentialDataSecretId(user, dbInstance));
    } catch (NoSuchSecretResourceException e) {
      // ok
    }
    try {
      csmClient.deleteSecret(getLiveLabelSecretId(user, dbInstance));
    } catch (NoSuchSecretResourceException e) {
      // ok.
    }
  }

  private void createSecretIfAbsent(String secretId) {
    try {
      csmClient.createSecret(secretId);
    } catch (SecretAlreadyExistsException ignore) {
      // Not a problem.
    }
  }

  private SecretVersionName saveCredentialData(SqlUser user, String password) {
    String credentialDataSecretId = getCredentialDataSecretId(user, dbInstance);
    createSecretIfAbsent(credentialDataSecretId);
    String credentialVersion =
        csmClient.addSecretVersion(
            credentialDataSecretId,
            SqlCredential.of(createDatabaseLoginName(user), password).toFormattedString());
    return SecretVersionName.of(csmClient.getProject(), credentialDataSecretId, credentialVersion);
  }

  private void saveLiveLabel(SqlUser user, SecretVersionName dataVersionName) {
    String liveLabelSecretId = getLiveLabelSecretId(user, dbInstance);
    createSecretIfAbsent(liveLabelSecretId);
    csmClient.addSecretVersion(liveLabelSecretId, dataVersionName.toString());
  }

  private SecretVersionName getLiveCredentialSecretVersion(SqlUser user) {
    return SecretVersionName.parse(
        csmClient.getSecretData(getLiveLabelSecretId(user, dbInstance), Optional.empty()));
  }

  private static String getLiveLabelSecretId(SqlUser user, String dbInstance) {
    return String.format("sql-cred-live-label-%s-%s", user.geUserName(), dbInstance);
  }

  private static String getCredentialDataSecretId(SqlUser user, String dbInstance) {
    return String.format("sql-cred-data-%s-%s", user.geUserName(), dbInstance);
  }

  // WIP: when b/170230882 is complete, login will be versioned.
  private static String createDatabaseLoginName(SqlUser user) {
    return user.geUserName();
  }
}
