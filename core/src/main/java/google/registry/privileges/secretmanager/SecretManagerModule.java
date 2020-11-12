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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.util.Retrier;
import google.registry.util.UtilsModule;
import java.io.IOException;
import javax.inject.Singleton;

/** Provides bindings for {@link SecretManagerClient}. */
@Module
public class SecretManagerModule {

  private final String project;

  public SecretManagerModule(String project) {
    this.project = checkNotNull(project, "project");
  }

  @Provides
  @Singleton
  SecretManagerClient provideSecretManagerClient(Retrier retrier) {
    try {
      return new SecretManagerClientImpl(project, SecretManagerServiceClient.create(), retrier);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Singleton
  @Component(modules = {ConfigModule.class, SecretManagerModule.class, UtilsModule.class})
  public interface SecretManagerComponent {
    SecretManagerClient secretManagerClient();
  }
}
