// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.keyring.kms;

import com.google.api.services.cloudkms.v1.CloudKMS;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;
import google.registry.config.CredentialModule.DefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.Keyring;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.Retrier;

/** Dagger module for Cloud KMS. */
@Module
public abstract class KmsModule {

  public static final String NAME = "KMS";

  @Provides
  @Config("defaultKms")
  static CloudKMS provideKms(
      @DefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("cloudKmsProjectId") String projectId) {
    return createKms(credentialsBundle, projectId);
  }

  @Provides
  @Config("beamKms")
  static CloudKMS provideBeamKms(
      @DefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("beamCloudKmsProjectId") String projectId) {
    return createKms(credentialsBundle, projectId);
  }

  private static CloudKMS createKms(GoogleCredentialsBundle credentialsBundle, String projectId) {
    return new CloudKMS.Builder(
            credentialsBundle.getHttpTransport(),
            credentialsBundle.getJsonFactory(),
            credentialsBundle.getHttpRequestInitializer())
        .setApplicationName(projectId)
        .build();
  }

  @Provides
  @Config("defaultKmsConnection")
  static KmsConnection provideKmsConnection(
      @Config("cloudKmsProjectId") String projectId,
      @Config("cloudKmsKeyRing") String keyringName,
      Retrier retrier,
      @Config("defaultKms") CloudKMS defaultKms) {
    return new KmsConnectionImpl(projectId, keyringName, retrier, defaultKms);
  }

  @Provides
  @Config("beamKmsConnection")
  static KmsConnection provideBeamKmsConnection(
      @Config("beamCloudKmsProjectId") String projectId,
      @Config("beamCloudKmsKeyRing") String keyringName,
      Retrier retrier,
      @Config("beamKms") CloudKMS defaultKms) {
    return new KmsConnectionImpl(projectId, keyringName, retrier, defaultKms);
  }

  @Binds
  @IntoMap
  @StringKey(NAME)
  abstract Keyring provideKeyring(KmsKeyring keyring);
}
