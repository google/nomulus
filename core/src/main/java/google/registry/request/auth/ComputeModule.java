// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.request.auth;

import static google.registry.util.RegistryEnvironment.UNITTEST;

import com.google.cloud.compute.v1.BackendService;
import com.google.cloud.compute.v1.BackendServicesClient;
import com.google.cloud.compute.v1.BackendServicesSettings;
import com.google.common.collect.ImmutableMap;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.RegistryEnvironment;
import java.io.IOException;
import javax.inject.Named;
import javax.inject.Singleton;

/** Dagger module to provide the backend service IDs. */
@Module
public abstract class ComputeModule {
  // The automatically generated backend service name is in the following format:
  // gkemcg1-default-console[-canary]-80-(some random string)
  private static final Pattern BACKEND_END_PATTERN =
      Pattern.compile(".*-default-((frontend|backend|console|pubapi)(-canary)?)-80-.*");

  @Provides
  @Singleton
  static BackendServicesClient provideBackendServicesClients(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle) {
    try {
      return BackendServicesClient.create(
          BackendServicesSettings.newBuilder()
              .setCredentialsProvider(credentialsBundle::getGoogleCredentials)
              .build());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  @Singleton
  @Named("backendServiceIdMap")
  static ImmutableMap<String, Long> provideBackendServiceList(
      Lazy<BackendServicesClient> client, @Config("projectId") String projectId) {
    if (RegistryEnvironment.isInTestServer() || RegistryEnvironment.get() == UNITTEST) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, Long> builder = ImmutableMap.builder();
    for (BackendService service : client.get().list(projectId).iterateAll()) {
      String name = service.getName();
      Matcher matcher = BACKEND_END_PATTERN.matcher(name);
      if (!matcher.matches()) {
        continue;
      }
      builder.put(matcher.group(1), service.getId());
    }
    return builder.build();
  }
}
