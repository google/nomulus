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

package google.registry.bigquery;

import com.google.api.client.googleapis.util.Utils;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import google.registry.config.CredentialModule.DefaultCredential;
import google.registry.config.RegistryConfig.Config;
import java.util.Map;

/** Dagger module for Google {@link Bigquery} connection objects. */
@Module
public abstract class BigqueryModule {

  // No subclasses.
  private BigqueryModule() {}

  @Provides
  static Bigquery provideBigquery(
      @DefaultCredential GoogleCredentials credential, @Config("projectId") String projectId) {
    return new Bigquery.Builder(
            Utils.getDefaultTransport(),
            Utils.getDefaultJsonFactory(),
            new HttpCredentialsAdapter(credential))
        .setApplicationName(projectId)
        .build();
  }

  /** Provides a map of BigQuery table names to field names. */
  @Multibinds
  abstract Map<String, ImmutableList<TableFieldSchema>> bigquerySchemas();
}
