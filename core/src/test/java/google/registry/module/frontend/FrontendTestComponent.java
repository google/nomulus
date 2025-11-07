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

package google.registry.module.frontend;

import dagger.Component;
import google.registry.config.CloudTasksUtilsModule;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig;
import google.registry.flows.ServerTridProviderModule;
import google.registry.flows.custom.CustomLogicFactoryModule;
import google.registry.flows.domain.DomainDeletionTimeCacheModule;
import google.registry.groups.GmailModule;
import google.registry.groups.GroupsModule;
import google.registry.groups.GroupssettingsModule;
import google.registry.keyring.KeyringModule;
import google.registry.keyring.api.KeyModule;
import google.registry.monitoring.whitebox.StackdriverModule;
import google.registry.privileges.secretmanager.SecretManagerModule;
import google.registry.request.Modules;
import google.registry.request.auth.AuthModule;
import google.registry.util.UtilsModule;
import jakarta.inject.Singleton;

@Singleton
@Component(
    modules = {
      AuthModule.class,
      CloudTasksUtilsModule.class,
      RegistryConfig.ConfigModule.class,
      CredentialModule.class,
      CustomLogicFactoryModule.class,
      CloudTasksUtilsModule.class,
      DomainDeletionTimeCacheModule.class,
      FrontendRequestComponent.FrontendRequestComponentModule.class,
      GmailModule.class,
      GroupsModule.class,
      GroupssettingsModule.class,
      MockDirectoryModule.class,
      Modules.GsonModule.class,
      KeyModule.class,
      KeyringModule.class,
      Modules.NetHttpTransportModule.class,
      SecretManagerModule.class,
      ServerTridProviderModule.class,
      StackdriverModule.class,
      UtilsModule.class
    })
interface FrontendTestComponent extends FrontendComponent {}
