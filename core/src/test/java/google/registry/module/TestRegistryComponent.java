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

package google.registry.module;

import com.google.monitoring.metrics.MetricReporter;
import dagger.Component;
import dagger.Lazy;
import google.registry.batch.BatchModule;
import google.registry.bigquery.BigqueryModule;
import google.registry.config.CloudTasksUtilsModule;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.export.DriveModule;
import google.registry.export.sheet.SheetsServiceModule;
import google.registry.flows.ServerTridProviderModule;
import google.registry.flows.custom.CustomLogicFactoryModule;
import google.registry.flows.domain.DomainDeletionTimeCacheModule;
import google.registry.groups.GmailModule;
import google.registry.groups.GroupsModule;
import google.registry.groups.GroupssettingsModule;
import google.registry.keyring.KeyringModule;
import google.registry.keyring.api.KeyModule;
import google.registry.module.TestRequestComponent.TestRequestComponentModule;
import google.registry.monitoring.whitebox.StackdriverModule;
import google.registry.mosapi.module.MosApiModule;
import google.registry.persistence.PersistenceModule;
import google.registry.privileges.secretmanager.SecretManagerModule;
import google.registry.rde.JSchModule;
import google.registry.request.Modules.GsonModule;
import google.registry.request.Modules.NetHttpTransportModule;
import google.registry.request.Modules.UrlConnectionServiceModule;
import google.registry.request.auth.AuthModule;
import google.registry.util.UtilsModule;
import jakarta.inject.Singleton;

/** Dagger component with instance lifetime for the test server. */
@Singleton
@Component(
    modules = {
      AuthModule.class,
      BatchModule.class,
      BigqueryModule.class,
      CloudTasksUtilsModule.class,
      ConfigModule.class,
      CredentialModule.class,
      CustomLogicFactoryModule.class,
      DomainDeletionTimeCacheModule.class,
      DriveModule.class,
      GmailModule.class,
      GroupsModule.class,
      GroupssettingsModule.class,
      GsonModule.class,
      MosApiModule.class,
      JSchModule.class,
      KeyModule.class,
      KeyringModule.class,
      MockDirectoryModule.class,
      NetHttpTransportModule.class,
      PersistenceModule.class,
      SecretManagerModule.class,
      ServerTridProviderModule.class,
      SheetsServiceModule.class,
      StackdriverModule.class,
      TestRequestComponentModule.class,
      UrlConnectionServiceModule.class,
      UtilsModule.class
    })
interface TestRegistryComponent {
  TestRequestHandler requestHandler();

  Lazy<MetricReporter> metricReporter();
}
