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


import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.DefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.Retrier;
import java.io.IOException;
import javax.inject.Singleton;

/** Provides bindings for {@link SecretManagerClient}. */
@Module
public abstract class SecretManagerModule {

  @Provides
  @Singleton
  static SecretManagerServiceSettings provideSecretManagerSetting(
      @DefaultCredential GoogleCredentialsBundle credentialsBundle) {
    try {
      return SecretManagerServiceSettings.newBuilder()
          .setCredentialsProvider(() -> credentialsBundle.getGoogleCredentials())
          .build();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  @Singleton
  static SecretManagerClient provideSecretManagerClient(
      SecretManagerServiceSettings serviceSettings,
      @Config("projectId") String project,
      Retrier retrier) {
    try {
      SecretManagerServiceClient stub = SecretManagerServiceClient.create(serviceSettings);
      Runtime.getRuntime().addShutdownHook(new Thread(stub::close));
      return new SecretManagerClientImpl(project, stub, retrier);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
