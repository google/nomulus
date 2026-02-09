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

import google.registry.config.RegistryConfig.Config;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Storage of SQL users' login credentials, backed by Cloud Secret Manager.
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
    var secretId = getSecretIdForUserPassword(user);
    var secretData = csmClient.getSecretData(secretId, Optional.empty());
    return SqlCredential.fromFormattedString(secretData);
  }

  public void createOrUpdateCredential(SqlUser user, String password) {
    var secretId = getSecretIdForUserPassword(user);
    csmClient.createSecretIfAbsent(secretId);
    csmClient.addSecretVersion(
        secretId, SqlCredential.create(user.geUserName(), password).toFormattedString());
  }

  private String getSecretIdForUserPassword(SqlUser user) {
    return String.format("sql-password-for-%s-on-%s", user.geUserName(), this.dbInstance);
  }
}
