// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import dagger.Subcomponent;
import google.registry.export.PublishDetailReportAction;
import google.registry.flows.EppToolAction;
import google.registry.flows.EppToolAction.EppToolModule;
import google.registry.flows.FlowComponent;
import google.registry.loadtest.LoadTestAction;
import google.registry.loadtest.LoadTestModule;
import google.registry.mapreduce.MapreduceModule;
import google.registry.request.RequestModule;
import google.registry.request.RequestScope;
import google.registry.tools.server.CreateGroupsAction;
import google.registry.tools.server.CreatePremiumListAction;
import google.registry.tools.server.DeleteEntityAction;
import google.registry.tools.server.DeleteProberDataAction;
import google.registry.tools.server.GenerateZoneFilesAction;
import google.registry.tools.server.KillAllCommitLogsAction;
import google.registry.tools.server.KillAllEppResourcesAction;
import google.registry.tools.server.ListDomainsAction;
import google.registry.tools.server.ListHostsAction;
import google.registry.tools.server.ListPremiumListsAction;
import google.registry.tools.server.ListRegistrarsAction;
import google.registry.tools.server.ListReservedListsAction;
import google.registry.tools.server.ListTldsAction;
import google.registry.tools.server.ResaveAllEppResourcesAction;
import google.registry.tools.server.ToolsServerModule;
import google.registry.tools.server.UpdatePremiumListAction;
import google.registry.tools.server.VerifyOteAction;
import google.registry.tools.server.javascrap.BackfillAutorenewBillingFlagAction;
import google.registry.tools.server.javascrap.CountRecurringBillingEventsAction;
import google.registry.tools.server.javascrap.RefreshAllDomainsAction;

/** Dagger component with per-request lifetime for "tools" App Engine module. */
@RequestScope
@Subcomponent(
    modules = {
        EppToolModule.class,
        LoadTestModule.class,
        MapreduceModule.class,
        RequestModule.class,
        ToolsServerModule.class,
    })
interface ToolsRequestComponent {
  BackfillAutorenewBillingFlagAction backfillAutorenewBillingFlagAction();
  CountRecurringBillingEventsAction countRecurringBillingEventsAction();
  CreateGroupsAction createGroupsAction();
  CreatePremiumListAction createPremiumListAction();
  DeleteEntityAction deleteEntityAction();
  DeleteProberDataAction deleteProberDataAction();
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
  PublishDetailReportAction publishDetailReportAction();
  RefreshAllDomainsAction refreshAllDomainsAction();
  ResaveAllEppResourcesAction resaveAllEppResourcesAction();
  UpdatePremiumListAction updatePremiumListAction();
  VerifyOteAction verifyOteAction();
}
