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

package google.registry.beam.common;

import dagger.Component;
import dagger.Lazy;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.persistence.PersistenceModule;
import google.registry.persistence.PersistenceModule.BeamJpaTm;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.privileges.secretmanager.SecretManagerModule;
import google.registry.util.UtilsModule;
import javax.inject.Singleton;

/** Component that provides everything needed on a Pipeline worker. */
@Singleton
@Component(
    modules = {
      ConfigModule.class,
      CredentialModule.class,
      PersistenceModule.class,
      SecretManagerModule.class,
      UtilsModule.class
    })
public interface RegistryPipelineComponent {

  /** Returns the GCP project ID. */
  @Config("projectId")
  String getProjectId();

  @BeamJpaTm
  Lazy<JpaTransactionManager> getJpaTransactionManager();
}
