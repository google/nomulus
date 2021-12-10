// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.export.datastore;

import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.util.GoogleCredentialsBundle;
import javax.inject.Singleton;

/** Dagger module that configures provision of {@link DatastoreAdmin}. */
@Module
@DeleteAfterMigration
public abstract class DatastoreAdminModule {

  @Singleton
  @Provides
  static DatastoreAdmin provideDatastoreAdmin(
      @CredentialModule.DefaultCredential GoogleCredentialsBundle credentialsBundle,
      @RegistryConfig.Config("projectId") String projectId) {
    return new DatastoreAdmin.Builder(
            credentialsBundle.getHttpTransport(),
            credentialsBundle.getJsonFactory(),
            credentialsBundle.getHttpRequestInitializer())
        .setApplicationName(projectId)
        .setProjectId(projectId)
        .build();
  }
}
