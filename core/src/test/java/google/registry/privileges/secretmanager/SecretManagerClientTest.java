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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.cloud.secretmanager.v1.SecretVersion.State;
import google.registry.privileges.secretmanager.SecretManagerClient.SecretAlreadyExistsException;
import google.registry.privileges.secretmanager.SecretManagerClient.SecretManagerException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SecretManagerClient}.
 *
 * <p>If the 'test.gcp_integration.env' system property is not set, this class serves as unit tests
 * for {@link FakeSecretManagerClient}.
 *
 * <p>If the 'test.gcp_integration.env' environment variable is set, this class serves as
 * integration tests with a GCP project whose name is specified by the variable.
 *
 * <p>See <a href="../../../../../../../../java_common.gradle">java_common.gradle</a> for more
 * information.
 */
public class SecretManagerClientTest {
  // Common prefix for all secret ids generated in this test.
  private static final String SECRET_ID_PREFIX = "TEST_" + UUID.randomUUID() + "_";
  // Used for unique secret id generation.
  private static int seqno = 0;

  private static SecretManagerClient secretManagerClient;
  private static boolean isUnitTest = true;

  private static String nextSecretId() {
    return SECRET_ID_PREFIX + seqno++;
  }

  @BeforeAll
  static void beforeAll() {
    String environmentName = System.getProperty("test.gcp_integration.env");
    if (environmentName != null) {
      secretManagerClient =
          DaggerSecretManagerModule_SecretManagerComponent.builder()
              .secretManagerModule(
                  new SecretManagerModule(String.format("domain-registry-%s", environmentName)))
              .build()
              .secretManagerClient();
      isUnitTest = false;
    } else {
      secretManagerClient = new FakeSecretManagerClient();
    }
  }

  @AfterAll
  static void afterAll() {
    if (isUnitTest) {
      return;
    }
    for (String secretId : secretManagerClient.listSecrets()) {
      if (secretId.startsWith(SECRET_ID_PREFIX)) {
        secretManagerClient.deleteSecret(secretId);
      }
    }
  }

  @Test
  void createSecret_success() {
    String secretId = nextSecretId();
    secretManagerClient.createSecret(secretId);
    assertThat(secretManagerClient.listSecrets()).contains(secretId);
  }

  @Test
  void createSecret_duplicate() {
    String secretId = nextSecretId();
    secretManagerClient.createSecret(secretId);
    assertThrows(
        SecretAlreadyExistsException.class, () -> secretManagerClient.createSecret(secretId));
  }

  @Test
  void addSecretVersion() {
    String secretId = nextSecretId();
    secretManagerClient.createSecret(secretId);
    String version = secretManagerClient.addSecretVersion(secretId, "mydata");
    assertThat(secretManagerClient.listSecretVersions(secretId, State.ENABLED))
        .containsExactly(version);
  }

  @Test
  void getSecretData_byVersion() {
    String secretId = nextSecretId();
    secretManagerClient.createSecret(secretId);
    String version = secretManagerClient.addSecretVersion(secretId, "mydata");
    assertThat(secretManagerClient.getSecretData(secretId, Optional.of(version)))
        .isEqualTo("mydata");
  }

  @Test
  void getSecretData_latestVersion() {
    String secretId = nextSecretId();
    secretManagerClient.createSecret(secretId);
    secretManagerClient.addSecretVersion(secretId, "mydata");
    assertThat(secretManagerClient.getSecretData(secretId, Optional.empty())).isEqualTo("mydata");
  }

  @Test
  void destroySecretVersion() {
    String secretId = nextSecretId();
    secretManagerClient.createSecret(secretId);
    String version = secretManagerClient.addSecretVersion(secretId, "mydata");
    secretManagerClient.destroySecretVersion(secretId, version);
    assertThat(secretManagerClient.listSecretVersions(secretId, State.DESTROYED)).contains(version);
    assertThrows(
        SecretManagerException.class,
        () -> secretManagerClient.getSecretData(secretId, Optional.of(version)));
  }

  @Test
  void deleteSecret() {
    String secretId = nextSecretId();
    secretManagerClient.createSecret(secretId);
    assertThat(secretManagerClient.listSecrets()).contains(secretId);
    secretManagerClient.deleteSecret(secretId);
    assertThat(secretManagerClient.listSecrets()).doesNotContain(secretId);
  }
}
