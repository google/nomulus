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

import dagger.Module;
import dagger.Provides;
import google.registry.privileges.secretmanager.SecretManagerClient;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import java.util.Optional;
import java.util.function.Function;

/** Dagger module for providing MoSAPI credentials from Secret Manager. */
@Module
public class MosApiCredentialModule {

  /**
   * Provides a Provider for the MoSAPI username.
   *
   * <p>This method returns a Dagger {@link Provider} that can be used to fetch the username for a
   * specific TLD. The secret name is constructed dynamically using the TLD.
   *
   * @param secretManagerClient The injected Secret Manager client.
   * @return A Provider for the MoSAPI username.
   */
  @Provides
  @Named("mosapiUsernameProvider")
  static Function<String, String> provideMosapiUsernameProvider(
      SecretManagerClient secretManagerClient) {
    // This lambda is the implementation of the Function
    return (tld) -> {
      String secretName = String.format("mosapi_username_%s", tld);
      // This call likely throws a checked exception, so we must handle it.
      return secretManagerClient.getSecretData(secretName, Optional.of("latest"));
    };
  }

  /**
   * Provides the shared MoSAPI password.
   *
   * @param secretManagerClient The injected Secret Manager client.
   * @return The MoSAPI password.
   */
  @Provides
  @Named("mosapiPasswordProvider")
  static Function<String, String> provideMosapiPassword(SecretManagerClient secretManagerClient) {
    return (tld) -> {
      String secretName = String.format("mosapi_password_%s", tld);
      return secretManagerClient.getSecretData(secretName, Optional.of("latest"));
    };
  }
}
