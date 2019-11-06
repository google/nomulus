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

package google.registry.module.frontend;

import dagger.Module;
import dagger.Subcomponent;
import google.registry.dns.DnsModule;
import google.registry.flows.EppTlsAction;
import google.registry.flows.FlowComponent;
import google.registry.flows.TlsCredentials.EppTlsModule;
import google.registry.monitoring.whitebox.WhiteboxModule;
import google.registry.request.RequestComponentBuilder;
import google.registry.request.RequestModule;
import google.registry.request.RequestScope;
import google.registry.ui.server.registrar.ConsoleOteSetupAction;
import google.registry.ui.server.registrar.ConsoleRegistrarCreatorAction;
import google.registry.ui.server.registrar.ConsoleUiAction;
import google.registry.ui.server.registrar.OteStatusAction;
import google.registry.ui.server.registrar.RegistrarConsoleModule;
import google.registry.ui.server.registrar.RegistrarSettingsAction;
import google.registry.ui.server.registrar.RegistryLockGetAction;

/** Dagger component with per-request lifetime for "default" App Engine module. */
@RequestScope
@Subcomponent(
    modules = {
      DnsModule.class,
      EppTlsModule.class,
      RegistrarConsoleModule.class,
      RequestModule.class,
      WhiteboxModule.class,
    })
interface FrontendRequestComponent {
  ConsoleOteSetupAction consoleOteSetupAction();
  ConsoleRegistrarCreatorAction consoleRegistrarCreatorAction();
  ConsoleUiAction consoleUiAction();
  EppTlsAction eppTlsAction();
  FlowComponent.Builder flowComponentBuilder();
  OteStatusAction oteStatusAction();
  RegistrarSettingsAction registrarSettingsAction();

  RegistryLockGetAction registryLockGetAction();

  @Subcomponent.Builder
  abstract class Builder implements RequestComponentBuilder<FrontendRequestComponent> {
    @Override public abstract Builder requestModule(RequestModule requestModule);
    @Override public abstract FrontendRequestComponent build();
  }

  @Module(subcomponents = FrontendRequestComponent.class)
  class FrontendRequestComponentModule {}
}
