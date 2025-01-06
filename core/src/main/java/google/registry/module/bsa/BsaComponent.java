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

package google.registry.module.bsa;

import com.google.monitoring.metrics.MetricReporter;
import dagger.Component;
import dagger.Lazy;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.groups.GmailModule;
import google.registry.keyring.KeyringModule;
import google.registry.keyring.secretmanager.SecretManagerKeyringModule;
import google.registry.module.bsa.BsaRequestComponent.BsaRequestComponentModule;
import google.registry.monitoring.whitebox.StackdriverModule;
import google.registry.persistence.PersistenceModule;
import google.registry.privileges.secretmanager.SecretManagerModule;
import google.registry.request.Modules.GsonModule;
import google.registry.request.Modules.UrlConnectionServiceModule;
import google.registry.request.auth.AuthModule;
import google.registry.util.UtilsModule;
import javax.inject.Singleton;

@Singleton
@Component(
    modules = {
      AuthModule.class,
      BsaRequestComponentModule.class,
      ConfigModule.class,
      CredentialModule.class,
      GmailModule.class,
      GsonModule.class,
      PersistenceModule.class,
      KeyringModule.class,
      SecretManagerKeyringModule.class,
      SecretManagerModule.class,
      StackdriverModule.class,
      UrlConnectionServiceModule.class,
      UtilsModule.class
    })
interface BsaComponent {
  BsaRequestHandler requestHandler();

  Lazy<MetricReporter> metricReporter();
}
