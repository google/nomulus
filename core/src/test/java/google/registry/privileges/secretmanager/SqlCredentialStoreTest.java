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

import static com.google.common.truth.Truth.assertThat;

import com.google.cloud.secretmanager.v1.SecretVersionName;
import google.registry.privileges.secretmanager.SqlUser.RobotId;
import google.registry.privileges.secretmanager.SqlUser.RobotUser;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SqlCredentialStore}. */
public class SqlCredentialStoreTest {

  private final SecretManagerClient client = new FakeSecretManagerClient();
  private final SqlCredentialStore credentialStore = new SqlCredentialStore(client, "db");
  private SqlUser user = new RobotUser(RobotId.NOMULUS);

  @Test
  void createSecret() {
    credentialStore.createOrUpdateCredential(user, "password");
    assertThat(client.secretExists("sql-cred-live-label-nomulus-db")).isTrue();
    assertThat(
            SecretVersionName.parse(
                    client.getSecretData("sql-cred-live-label-nomulus-db", Optional.empty()))
                .getSecret())
        .isEqualTo("sql-cred-data-nomulus-db");
    assertThat(client.secretExists("sql-cred-data-nomulus-db")).isTrue();
    assertThat(client.getSecretData("sql-cred-data-nomulus-db", Optional.empty()))
        .isEqualTo("nomulus password");
  }

  @Test
  void getCredential() {
    credentialStore.createOrUpdateCredential(user, "password");
    SqlCredential credential = credentialStore.getCredential(user);
    assertThat(credential.login()).isEqualTo("nomulus");
    assertThat(credential.password()).isEqualTo("password");
  }

  @Test
  void deleteCredential() {
    credentialStore.createOrUpdateCredential(user, "password");
    assertThat(client.secretExists("sql-cred-live-label-nomulus-db")).isTrue();
    assertThat(client.secretExists("sql-cred-data-nomulus-db")).isTrue();
    credentialStore.deleteCredential(user);
    assertThat(client.secretExists("sql-cred-live-label-nomulus-db")).isFalse();
    assertThat(client.secretExists("sql-cred-data-nomulus-db")).isFalse();
  }
}
