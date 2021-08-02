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

package google.registry.module.backend;

import com.google.monitoring.metrics.MetricReporter;
import dagger.Component;
import dagger.Lazy;
import google.registry.bigquery.BigqueryModule;
import google.registry.config.CloudTasksUtilsModule;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.dns.writer.VoidDnsWriterModule;
import google.registry.export.DriveModule;
import google.registry.export.datastore.DatastoreAdminModule;
import google.registry.export.sheet.SheetsServiceModule;
import google.registry.flows.ServerTridProviderModule;
import google.registry.flows.custom.CustomLogicFactoryModule;
import google.registry.groups.DirectoryModule;
import google.registry.groups.GroupsModule;
import google.registry.groups.GroupssettingsModule;
import google.registry.keyring.KeyringModule;
import google.registry.keyring.api.DummyKeyringModule;
import google.registry.keyring.api.KeyModule;
import google.registry.keyring.kms.KmsModule;
import google.registry.module.backend.BackendRequestComponent.BackendRequestComponentModule;
import google.registry.monitoring.whitebox.StackdriverModule;
import google.registry.persistence.PersistenceModule;
import google.registry.privileges.secretmanager.SecretManagerModule;
import google.registry.rde.JSchModule;
import google.registry.request.Modules.DatastoreServiceModule;
import google.registry.request.Modules.Jackson2Module;
import google.registry.request.Modules.NetHttpTransportModule;
import google.registry.request.Modules.URLFetchServiceModule;
import google.registry.request.Modules.UrlFetchTransportModule;
import google.registry.request.Modules.UserServiceModule;
import google.registry.request.auth.AuthModule;
import google.registry.util.UtilsModule;
import javax.inject.Singleton;

/** Dagger component with instance lifetime for "backend" App Engine module. */
@Singleton
@Component(
    modules = {
      AuthModule.class,
      BackendRequestComponentModule.class,
      BigqueryModule.class,
      ConfigModule.class,
      CloudTasksUtilsModule.class,
      CredentialModule.class,
      CustomLogicFactoryModule.class,
      DatastoreAdminModule.class,
      DatastoreServiceModule.class,
      DirectoryModule.class,
      DummyKeyringModule.class,
      DriveModule.class,
      GroupsModule.class,
      GroupssettingsModule.class,
      JSchModule.class,
      Jackson2Module.class,
      KeyModule.class,
      KeyringModule.class,
      KmsModule.class,
      NetHttpTransportModule.class,
      PersistenceModule.class,
      SecretManagerModule.class,
      ServerTridProviderModule.class,
      SheetsServiceModule.class,
      StackdriverModule.class,
      URLFetchServiceModule.class,
      UrlFetchTransportModule.class,
      UserServiceModule.class,
      VoidDnsWriterModule.class,
      UtilsModule.class
    })
interface BackendComponent {
  BackendRequestHandler requestHandler();

  Lazy<MetricReporter> metricReporter();
}
