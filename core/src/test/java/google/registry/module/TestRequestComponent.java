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

import dagger.Module;
import dagger.Subcomponent;
import google.registry.batch.BatchModule;
import google.registry.cron.CronModule;
import google.registry.dns.DnsModule;
import google.registry.dns.writer.DnsWritersModule;
import google.registry.dns.writer.dnsupdate.DnsUpdateConfigModule;
import google.registry.export.sheet.SheetModule;
import google.registry.flows.CheckApiAction.CheckApiModule;
import google.registry.flows.EppToolAction.EppToolModule;
import google.registry.flows.TlsCredentials.EppTlsModule;
import google.registry.flows.custom.CustomLogicModule;
import google.registry.loadtest.LoadTestModule;
import google.registry.monitoring.whitebox.WhiteboxModule;
import google.registry.mosapi.module.MosApiRequestModule;
import google.registry.rdap.RdapModule;
import google.registry.rde.RdeModule;
import google.registry.reporting.ReportingModule;
import google.registry.reporting.billing.BillingModule;
import google.registry.reporting.icann.DnsCountQueryCoordinator.DnsCountQueryCoordinatorModule;
import google.registry.reporting.icann.IcannReportingModule;
import google.registry.reporting.spec11.Spec11Module;
import google.registry.request.RequestComponentBuilder;
import google.registry.request.RequestModule;
import google.registry.request.RequestScope;
import google.registry.tmch.TmchModule;
import google.registry.tools.server.ToolsServerModule;
import google.registry.ui.server.console.ConsoleModule;

/** Dagger component with per-request lifetime for the test server. */
@RequestScope
@Subcomponent(
    modules = {
      BatchModule.class,
      BillingModule.class,
      CheckApiModule.class,
      ConsoleModule.class,
      CronModule.class,
      CustomLogicModule.class,
      DnsCountQueryCoordinatorModule.class,
      DnsModule.class,
      DnsUpdateConfigModule.class,
      DnsWritersModule.class,
      EppTlsModule.class,
      EppToolModule.class,
      IcannReportingModule.class,
      LoadTestModule.class,
      MosApiRequestModule.class,
      RdapModule.class,
      RdeModule.class,
      ReportingModule.class,
      RequestModule.class,
      SheetModule.class,
      Spec11Module.class,
      TmchModule.class,
      ToolsServerModule.class,
      WhiteboxModule.class
    })
interface TestRequestComponent extends RequestComponent {

  @Subcomponent.Builder
  abstract class Builder implements RequestComponentBuilder<TestRequestComponent> {
    @Override
    public abstract TestRequestComponent.Builder requestModule(RequestModule requestModule);

    @Override
    public abstract TestRequestComponent build();
  }

  @Module(subcomponents = TestRequestComponent.class)
  static class TestRequestComponentModule {}
}
