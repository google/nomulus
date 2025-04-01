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
import google.registry.batch.BatchModule;
import google.registry.dns.DnsModule;
import google.registry.flows.EppTlsAction;
import google.registry.flows.FlowComponent;
import google.registry.flows.TlsCredentials.EppTlsModule;
import google.registry.module.ReadinessProbeAction.ReadinessProbeActionFrontend;
import google.registry.module.ReadinessProbeAction.ReadinessProbeConsoleAction;
import google.registry.monitoring.whitebox.WhiteboxModule;
import google.registry.request.RequestComponentBuilder;
import google.registry.request.RequestModule;
import google.registry.request.RequestScope;
import google.registry.ui.server.console.ConsoleDomainGetAction;
import google.registry.ui.server.console.ConsoleDomainListAction;
import google.registry.ui.server.console.ConsoleDumDownloadAction;
import google.registry.ui.server.console.ConsoleEppPasswordAction;
import google.registry.ui.server.console.ConsoleModule;
import google.registry.ui.server.console.ConsoleOteAction;
import google.registry.ui.server.console.ConsoleRegistryLockAction;
import google.registry.ui.server.console.ConsoleRegistryLockVerifyAction;
import google.registry.ui.server.console.ConsoleUpdateRegistrarAction;
import google.registry.ui.server.console.ConsoleUserDataAction;
import google.registry.ui.server.console.ConsoleUsersAction;
import google.registry.ui.server.console.RegistrarsAction;
import google.registry.ui.server.console.domains.ConsoleBulkDomainAction;
import google.registry.ui.server.console.settings.ContactAction;
import google.registry.ui.server.console.settings.SecurityAction;
import google.registry.ui.server.console.settings.WhoisRegistrarFieldsAction;

/** Dagger component with per-request lifetime for "default" App Engine module. */
@RequestScope
@Subcomponent(
    modules = {
      BatchModule.class,
      DnsModule.class,
      EppTlsModule.class,
      ConsoleModule.class,
      RequestModule.class,
      WhiteboxModule.class,
    })
public interface FrontendRequestComponent {
  ConsoleBulkDomainAction consoleBulkDomainAction();

  ConsoleDomainGetAction consoleDomainGetAction();

  ConsoleDomainListAction consoleDomainListAction();

  ConsoleEppPasswordAction consoleEppPasswordAction();

  ConsoleOteAction consoleOteAction();

  ConsoleRegistryLockAction consoleRegistryLockAction();

  ConsoleRegistryLockVerifyAction consoleRegistryLockVerifyAction();

  ConsoleUpdateRegistrarAction consoleUpdateRegistrarAction();

  ConsoleUserDataAction consoleUserDataAction();

  ConsoleUsersAction consoleUsersAction();

  ConsoleDumDownloadAction consoleDumDownloadAction();

  ContactAction contactAction();

  EppTlsAction eppTlsAction();

  FlowComponent.Builder flowComponentBuilder();

  ReadinessProbeActionFrontend readinessProbeActionFrontend();

  ReadinessProbeConsoleAction readinessProbeConsoleAction();

  RegistrarsAction registrarsAction();

  SecurityAction securityAction();

  WhoisRegistrarFieldsAction whoisRegistrarFieldsAction();

  @Subcomponent.Builder
  abstract class Builder implements RequestComponentBuilder<FrontendRequestComponent> {
    @Override public abstract Builder requestModule(RequestModule requestModule);
    @Override public abstract FrontendRequestComponent build();
  }

  @Module(subcomponents = FrontendRequestComponent.class)
  class FrontendRequestComponentModule {}
}
