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

package google.registry.export.sheet;

import com.google.api.services.sheets.v4.Sheets;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.GoogleWorkspaceCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.GoogleCredentialsBundle;

/** Dagger module for {@link Sheets}. */
@Module
public final class SheetsServiceModule {

  @Provides
  static Sheets provideSheets(
      @GoogleWorkspaceCredential GoogleCredentialsBundle credentialsBundle,
      @Config("projectId") String projectId) {
    return new Sheets.Builder(
            credentialsBundle.getHttpTransport(),
            credentialsBundle.getJsonFactory(),
            credentialsBundle.getHttpRequestInitializer())
        .setApplicationName(projectId)
        .build();
  }
}
