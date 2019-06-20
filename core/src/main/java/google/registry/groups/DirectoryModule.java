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

package google.registry.groups;

import com.google.api.client.googleapis.util.Utils;
import com.google.api.services.admin.directory.Directory;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.DelegatedCredential;
import google.registry.config.RegistryConfig.Config;

/** Dagger module for the Google {@link Directory} service. */
@Module
public final class DirectoryModule {

  @Provides
  static Directory provideDirectory(
      @DelegatedCredential GoogleCredentials credential, @Config("projectId") String projectId) {
    return new Directory.Builder(
            Utils.getDefaultTransport(),
            Utils.getDefaultJsonFactory(),
            new HttpCredentialsAdapter(credential))
        .setApplicationName(projectId)
        .build();
  }
}
