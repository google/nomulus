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

package google.registry.module.tools;

import dagger.Module;
import dagger.Subcomponent;
import google.registry.backup.BackupModule;
import google.registry.backup.RestoreCommitLogsAction;
import google.registry.dns.DnsModule;
import google.registry.flows.EppToolAction;
import google.registry.flows.EppToolAction.EppToolModule;
import google.registry.flows.FlowComponent;
import google.registry.loadtest.LoadTestAction;
import google.registry.loadtest.LoadTestModule;
import google.registry.mapreduce.MapreduceModule;
import google.registry.monitoring.whitebox.WhiteboxModule;
import google.registry.request.RequestComponentBuilder;
import google.registry.request.RequestModule;
import google.registry.request.RequestScope;
import google.registry.tools.server.CreateGroupsAction;
import google.registry.tools.server.CreatePremiumListAction;
import google.registry.tools.server.GenerateZoneFilesAction;
import google.registry.tools.server.KillAllCommitLogsAction;
import google.registry.tools.server.KillAllEppResourcesAction;
import google.registry.tools.server.ListDomainsAction;
import google.registry.tools.server.ListHostsAction;
import google.registry.tools.server.ListPremiumListsAction;
import google.registry.tools.server.ListRegistrarsAction;
import google.registry.tools.server.ListReservedListsAction;
import google.registry.tools.server.ListTldsAction;
import google.registry.tools.server.RefreshDnsForAllDomainsAction;
import google.registry.tools.server.ResaveAllHistoryEntriesAction;
import google.registry.tools.server.ToolsServerModule;
import google.registry.tools.server.UpdatePremiumListAction;
import google.registry.tools.server.VerifyOteAction;

/** Dagger component with per-request lifetime for "tools" App Engine module. */
@RequestScope
@Subcomponent(
    modules = {
        BackupModule.class,
        DnsModule.class,
        EppToolModule.class,
        LoadTestModule.class,
        MapreduceModule.class,
        RequestModule.class,
        ToolsServerModule.class,
        WhiteboxModule.class,
    })
interface ToolsRequestComponent {
  CreateGroupsAction createGroupsAction();
  CreatePremiumListAction createPremiumListAction();
  EppToolAction eppToolAction();
  FlowComponent.Builder flowComponentBuilder();
  GenerateZoneFilesAction generateZoneFilesAction();
  KillAllCommitLogsAction killAllCommitLogsAction();
  KillAllEppResourcesAction killAllEppResourcesAction();
  ListDomainsAction listDomainsAction();
  ListHostsAction listHostsAction();
  ListPremiumListsAction listPremiumListsAction();
  ListRegistrarsAction listRegistrarsAction();
  ListReservedListsAction listReservedListsAction();
  ListTldsAction listTldsAction();
  LoadTestAction loadTestAction();
  RefreshDnsForAllDomainsAction refreshDnsForAllDomainsAction();
  ResaveAllHistoryEntriesAction resaveAllHistoryEntriesAction();
  RestoreCommitLogsAction restoreCommitLogsAction();
  UpdatePremiumListAction updatePremiumListAction();
  VerifyOteAction verifyOteAction();

  @Subcomponent.Builder
  abstract class Builder implements RequestComponentBuilder<ToolsRequestComponent> {
    @Override public abstract Builder requestModule(RequestModule requestModule);
    @Override public abstract ToolsRequestComponent build();
  }

  @Module(subcomponents = ToolsRequestComponent.class)
  class ToolsRequestComponentModule {}
}
