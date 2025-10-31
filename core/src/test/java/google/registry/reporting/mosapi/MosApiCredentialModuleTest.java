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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import google.registry.privileges.secretmanager.SecretManagerClient;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class MosApiCredentialModuleTest {
  @Mock private SecretManagerClient mockSecretManagerClient;

  private Function<String, String> usernameProvider;
  private Function<String, String> passwordProvider;

  @BeforeEach
  void setUp() {
    usernameProvider =
        MosApiCredentialModule.provideMosapiUsernameProvider(mockSecretManagerClient);
    passwordProvider = MosApiCredentialModule.provideMosapiPassword(mockSecretManagerClient);
  }

  @Test
  void provideMosapiUsernameProvider_success_returnsUsername() {
    when(mockSecretManagerClient.getSecretData("mosapi_username_test", Optional.of("latest")))
        .thenReturn("test_user");
    String username = usernameProvider.apply("test");
    assertThat(username).isEqualTo("test_user");
  }

  @Test
  void provideMosapiUsernameProvider_secretNotFound_throwsException() {
    when(mockSecretManagerClient.getSecretData(
            "mosapi_username_nonexistent", Optional.of("latest")))
        .thenThrow(new IllegalStateException("Secret not found"));
    assertThrows(IllegalStateException.class, () -> usernameProvider.apply("nonexistent"));
  }

  @Test
  void provideMosapiPasswordProvider_success_returnsPassword() {
    when(mockSecretManagerClient.getSecretData("mosapi_password_test", Optional.of("latest")))
        .thenReturn("test_password");
    String password = passwordProvider.apply("test");
    assertThat(password).isEqualTo("test_password");
  }

  @Test
  void provideMosapiPasswordProvider_secretNotFound_throwsException() {
    when(mockSecretManagerClient.getSecretData(
            "mosapi_password_nonexistent", Optional.of("latest")))
        .thenThrow(new IllegalStateException("Secret not found"));
    assertThrows(IllegalStateException.class, () -> passwordProvider.apply("nonexistent"));
  }
}
