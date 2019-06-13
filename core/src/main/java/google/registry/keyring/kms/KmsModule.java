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

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.services.cloudkms.v1.CloudKMS;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;
import google.registry.config.CredentialModule.DefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.Keyring;

/** Dagger module for Cloud KMS. */
@Module
public abstract class KmsModule {

  public static final String NAME = "KMS";

  @Binds
  @IntoMap
  @StringKey(NAME)
  abstract Keyring provideKeyring(KmsKeyring keyring);

  @Provides
  static CloudKMS provideKms(
      @DefaultCredential GoogleCredential credential,
      @Config("cloudKmsProjectId") String projectId) {
    return new CloudKMS.Builder(credential.getTransport(), credential.getJsonFactory(), credential)
        .setApplicationName(projectId)
        .build();
  }

  @Binds
  abstract KmsConnection provideKmsConnection(KmsConnectionImpl kmsConnectionImpl);
}
