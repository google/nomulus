// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.config;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.CloudTasksSettings;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.DefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.CloudTasksUtils;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.Retrier;
import java.io.IOException;
import javax.inject.Provider;
import javax.inject.Singleton;

/**
 * A {@link Module} that provides {@link CloudTasksUtils}.
 *
 * <p>The class itself cannot be annotated as {@code Inject} because its requested dependencies use
 * the {@link Config} qualifier which is not available in the {@code util} package.
 */
@Module
public abstract class CloudTasksUtilsModule {

  @Singleton
  @Provides
  public static CloudTasksUtils provideCloudTasksUtils(
      @Config("projectId") String projectId,
      @Config("locationId") String locationId,
      // Use a provider so that we can use try-with-resources with the client, which implements
      // Autocloseable.
      Provider<CloudTasksClient> clientProvider,
      Retrier retrier) {
    return new CloudTasksUtils(retrier, projectId, locationId, clientProvider);
  }

  @Provides
  public static CloudTasksClient provideCloudTasksClient(
      @DefaultCredential GoogleCredentialsBundle credentials) {
    try {
      return CloudTasksClient.create(
          CloudTasksSettings.newBuilder()
              .setCredentialsProvider(
                  FixedCredentialsProvider.create(credentials.getGoogleCredentials()))
              .build());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
