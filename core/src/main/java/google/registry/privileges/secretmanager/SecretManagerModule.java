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
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.util.Retrier;
import google.registry.util.UtilsModule;
import java.io.IOException;
import javax.inject.Singleton;

/** Provides bindings for {@link SecretManagerClient}. */
@Module
public abstract class SecretManagerModule {

  @Provides
  @Singleton
  static SecretManagerClient provideSecretManagerClient(
      @Config("projectId") String project, Retrier retrier) {
    try {
      SecretManagerServiceClient stub = SecretManagerServiceClient.create();
      Runtime.getRuntime().addShutdownHook(new Thread(stub::close));
      return new SecretManagerClientImpl(project, stub, retrier);
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
