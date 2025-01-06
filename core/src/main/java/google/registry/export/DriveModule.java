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

package google.registry.export;

import com.google.api.services.drive.Drive;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule;
import google.registry.config.CredentialModule.GoogleWorkspaceCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.storage.drive.DriveConnection;
import google.registry.util.GoogleCredentialsBundle;
import javax.inject.Singleton;

/** Dagger module for Google {@link Drive} service connection objects. */
@Module
public final class DriveModule {

  @Provides
  static Drive provideDrive(
      @GoogleWorkspaceCredential GoogleCredentialsBundle googleCredential,
      @Config("projectId") String projectId) {

    return new Drive.Builder(
            googleCredential.getHttpTransport(),
            googleCredential.getJsonFactory(),
            googleCredential.getHttpRequestInitializer())
        .setApplicationName(projectId)
        .build();
  }

  @Singleton
  @Component(modules = {DriveModule.class, ConfigModule.class, CredentialModule.class})
  interface DriveComponent {
    DriveConnection driveConnection();
  }
}
