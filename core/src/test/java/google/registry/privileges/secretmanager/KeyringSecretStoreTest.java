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

import static com.google.common.truth.Truth.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link KeyringSecretStore}. */
public class KeyringSecretStoreTest {

  private final SecretManagerClient csmClient = new FakeSecretManagerClient();
  private final KeyringSecretStore secretStore = new KeyringSecretStore(csmClient);
  private final byte[] data = {1, 2, 3, 0};

  @Test
  void createSecret() {
    secretStore.createOrUpdateSecret("a", data);
    byte[] persistedData = secretStore.getSecret("a");
    assertThat(persistedData).isEqualTo(data);
  }

  @Test
  void createSecret_underTheHood() {
    secretStore.createOrUpdateSecret("a", data);
    byte[] persistedData =
        csmClient.getSecretData("keyring-a", Optional.empty()).getBytes(StandardCharsets.UTF_8);
    assertThat(persistedData).isEqualTo(data);
  }

  @Test
  void updateSecret() {
    secretStore.createOrUpdateSecret("a", data);
    byte[] newData = {0, 1, 2, 3};
    secretStore.createOrUpdateSecret("a", newData);
    byte[] persistedData = secretStore.getSecret("a");
    assertThat(persistedData).isEqualTo(newData);
  }
}
