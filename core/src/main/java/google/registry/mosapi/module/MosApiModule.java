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

package google.registry.mosapi.module;

import dagger.Module;
import dagger.Provides;
import google.registry.privileges.secretmanager.SecretManagerClient;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import java.util.Optional;

@Module
public final class MosApiModule {
  /**
   * Provides a Provider for the MoSAPI TLS Cert.
   *
   * <p>This method returns a Dagger {@link Provider} that can be used to fetch the TLS Certs for a
   * MosAPI.
   *
   * @param secretManagerClient The injected Secret Manager client.
   * @return A Provider for the MoSAPI TLS Certs.
   */
  @Provides
  @Named("mosapiTlsCert")
  public static String provideMosapiTlsCert(SecretManagerClient secretManagerClient) {
    return secretManagerClient.getSecretData(
        "nomulus-dot-foo_tls-client-dot-crt-dot-pem", Optional.of("latest"));
  }

  /**
   * Provides a Provider for the MoSAPI TLS Key.
   *
   * <p>This method returns a Dagger {@link Provider} that can be used to fetch the TLS Key for a
   * MosAPI.
   *
   * @param secretManagerClient The injected Secret Manager client.
   * @return A Provider for the MoSAPI TLS Key.
   */
  @Provides
  @Named("mosapiTlsKey")
  public static String provideMosapiTlsKey(SecretManagerClient secretManagerClient) {
    return secretManagerClient.getSecretData(
        "nomulus-dot-foo_tls-client-dot-key", Optional.of("latest"));
  }
}
