// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import google.registry.batch.CannedScriptExecutionAction;
import google.registry.batch.DeleteExpiredDomainsAction;
import google.registry.batch.DeleteLoadTestDataAction;
import google.registry.batch.DeleteProberDataAction;
import google.registry.batch.ExpandBillingRecurrencesAction;
import google.registry.batch.RelockDomainAction;
import google.registry.batch.ResaveAllEppResourcesPipelineAction;
import google.registry.batch.ResaveEntityAction;
import google.registry.batch.SendExpiringCertificateNotificationEmailAction;
import google.registry.batch.WipeOutContactHistoryPiiAction;
import google.registry.bsa.BsaDownloadAction;
import google.registry.bsa.BsaRefreshAction;
import google.registry.bsa.BsaValidateAction;
import google.registry.bsa.UploadBsaUnavailableDomainsAction;
import google.registry.cron.CronModule;
import google.registry.cron.TldFanoutAction;
import google.registry.dns.DnsModule;
import google.registry.dns.PublishDnsUpdatesAction;
import google.registry.dns.ReadDnsRefreshRequestsAction;
import google.registry.dns.RefreshDnsAction;
import google.registry.dns.RefreshDnsOnHostRenameAction;
import google.registry.dns.writer.VoidDnsWriterModule;
import google.registry.dns.writer.clouddns.CloudDnsWriterModule;
import google.registry.dns.writer.dnsupdate.DnsUpdateConfigModule;
import google.registry.dns.writer.dnsupdate.DnsUpdateWriterModule;
import google.registry.export.ExportDomainListsAction;
import google.registry.export.ExportPremiumTermsAction;
import google.registry.export.ExportReservedTermsAction;
import google.registry.export.SyncGroupMembersAction;
import google.registry.export.sheet.SheetModule;
import google.registry.export.sheet.SyncRegistrarsSheetAction;
import google.registry.flows.CheckApiAction;
import google.registry.flows.CheckApiAction.CheckApiModule;
import google.registry.flows.EppTlsAction;
import google.registry.flows.EppToolAction;
import google.registry.flows.EppToolAction.EppToolModule;
import google.registry.flows.FlowComponent;
import google.registry.flows.TlsCredentials.EppTlsModule;
import google.registry.flows.custom.CustomLogicModule;
import google.registry.loadtest.LoadTestAction;
import google.registry.loadtest.LoadTestModule;
import google.registry.module.ReadinessProbeAction.ReadinessProbeActionFrontend;
import google.registry.module.ReadinessProbeAction.ReadinessProbeActionPubApi;
import google.registry.module.ReadinessProbeAction.ReadinessProbeConsoleAction;
import google.registry.monitoring.whitebox.WhiteboxModule;
import google.registry.rdap.RdapAutnumAction;
import google.registry.rdap.RdapDomainAction;
import google.registry.rdap.RdapDomainSearchAction;
import google.registry.rdap.RdapEmptyAction;
import google.registry.rdap.RdapEntityAction;
import google.registry.rdap.RdapEntitySearchAction;
import google.registry.rdap.RdapHelpAction;
import google.registry.rdap.RdapIpAction;
import google.registry.rdap.RdapModule;
import google.registry.rdap.RdapNameserverAction;
import google.registry.rdap.RdapNameserverSearchAction;
import google.registry.rdap.UpdateRegistrarRdapBaseUrlsAction;
import google.registry.rde.BrdaCopyAction;
import google.registry.rde.RdeModule;
import google.registry.rde.RdeReportAction;
import google.registry.rde.RdeReporter;
import google.registry.rde.RdeStagingAction;
import google.registry.rde.RdeUploadAction;
import google.registry.reporting.ReportingModule;
import google.registry.reporting.billing.BillingModule;
import google.registry.reporting.billing.CopyDetailReportsAction;
import google.registry.reporting.billing.GenerateInvoicesAction;
import google.registry.reporting.billing.PublishInvoicesAction;
import google.registry.reporting.icann.DnsCountQueryCoordinator.DnsCountQueryCoordinatorModule;
import google.registry.reporting.icann.IcannReportingModule;
import google.registry.reporting.icann.IcannReportingStagingAction;
import google.registry.reporting.icann.IcannReportingUploadAction;
import google.registry.reporting.spec11.GenerateSpec11ReportAction;
import google.registry.reporting.spec11.PublishSpec11ReportAction;
import google.registry.reporting.spec11.Spec11Module;
import google.registry.request.RequestComponentBuilder;
import google.registry.request.RequestModule;
import google.registry.request.RequestScope;
import google.registry.tmch.NordnUploadAction;
import google.registry.tmch.NordnVerifyAction;
import google.registry.tmch.TmchCrlAction;
import google.registry.tmch.TmchDnlAction;
import google.registry.tmch.TmchModule;
import google.registry.tmch.TmchSmdrlAction;
import google.registry.tools.server.CreateGroupsAction;
import google.registry.tools.server.GenerateZoneFilesAction;
import google.registry.tools.server.ListDomainsAction;
import google.registry.tools.server.ListHostsAction;
import google.registry.tools.server.ListPremiumListsAction;
import google.registry.tools.server.ListRegistrarsAction;
import google.registry.tools.server.ListReservedListsAction;
import google.registry.tools.server.ListTldsAction;
import google.registry.tools.server.RefreshDnsForAllDomainsAction;
import google.registry.tools.server.ToolsServerModule;
import google.registry.tools.server.UpdateUserGroupAction;
import google.registry.tools.server.VerifyOteAction;
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
import google.registry.whois.WhoisAction;
import google.registry.whois.WhoisHttpAction;
import google.registry.whois.WhoisModule;

/** Dagger component with per-request lifetime. */
@RequestScope
@Subcomponent(
    modules = {
      BatchModule.class,
      BillingModule.class,
      CheckApiModule.class,
      CloudDnsWriterModule.class,
      ConsoleModule.class,
      CronModule.class,
      CustomLogicModule.class,
      DnsCountQueryCoordinatorModule.class,
      DnsModule.class,
      DnsUpdateConfigModule.class,
      DnsUpdateWriterModule.class,
      EppTlsModule.class,
      EppToolModule.class,
      IcannReportingModule.class,
      LoadTestModule.class,
      RdapModule.class,
      RdeModule.class,
      ReportingModule.class,
      RequestModule.class,
      SheetModule.class,
      Spec11Module.class,
      TmchModule.class,
      ToolsServerModule.class,
      VoidDnsWriterModule.class,
      WhiteboxModule.class,
      WhoisModule.class,
    })
interface RequestComponent {
  FlowComponent.Builder flowComponentBuilder();

  BrdaCopyAction brdaCopyAction();

  BsaDownloadAction bsaDownloadAction();

  BsaRefreshAction bsaRefreshAction();

  BsaValidateAction bsaValidateAction();

  CannedScriptExecutionAction cannedScriptExecutionAction();

  CheckApiAction checkApiAction();

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

  CopyDetailReportsAction copyDetailReportAction();

  CreateGroupsAction createGroupsAction();

  DeleteExpiredDomainsAction deleteExpiredDomainsAction();

  DeleteLoadTestDataAction deleteLoadTestDataAction();

  DeleteProberDataAction deleteProberDataAction();

  EppTlsAction eppTlsAction();

  EppToolAction eppToolAction();

  ExpandBillingRecurrencesAction expandBillingRecurrencesAction();

  ExportDomainListsAction exportDomainListsAction();

  ExportPremiumTermsAction exportPremiumTermsAction();

  ExportReservedTermsAction exportReservedTermsAction();

  GenerateInvoicesAction generateInvoicesAction();

  GenerateSpec11ReportAction generateSpec11ReportAction();

  GenerateZoneFilesAction generateZoneFilesAction();

  IcannReportingStagingAction icannReportingStagingAction();

  IcannReportingUploadAction icannReportingUploadAction();

  ListDomainsAction listDomainsAction();

  ListHostsAction listHostsAction();

  ListPremiumListsAction listPremiumListsAction();

  ListRegistrarsAction listRegistrarsAction();

  ListReservedListsAction listReservedListsAction();

  ListTldsAction listTldsAction();

  LoadTestAction loadTestAction();

  NordnUploadAction nordnUploadAction();

  NordnVerifyAction nordnVerifyAction();

  PublishDnsUpdatesAction publishDnsUpdatesAction();

  PublishInvoicesAction uploadInvoicesAction();

  PublishSpec11ReportAction publishSpec11ReportAction();

  ReadinessProbeConsoleAction readinessProbeConsoleAction();

  ReadinessProbeActionPubApi readinessProbeActionPubApi();

  ReadinessProbeActionFrontend readinessProbeActionFrontend();

  RdapAutnumAction rdapAutnumAction();

  RdapDomainAction rdapDomainAction();

  RdapDomainSearchAction rdapDomainSearchAction();

  RdapEmptyAction rdapEmptyAction();

  RdapEntityAction rdapEntityAction();

  RdapEntitySearchAction rdapEntitySearchAction();

  RdapHelpAction rdapHelpAction();

  RdapIpAction rdapDefaultAction();

  RdapNameserverAction rdapNameserverAction();

  RdapNameserverSearchAction rdapNameserverSearchAction();

  RdeReportAction rdeReportAction();

  RdeReporter rdeReporter();

  RdeStagingAction rdeStagingAction();

  RdeUploadAction rdeUploadAction();

  ReadDnsRefreshRequestsAction readDnsRefreshRequestsAction();

  RefreshDnsAction refreshDnsAction();

  RefreshDnsForAllDomainsAction refreshDnsForAllDomainsAction();

  RefreshDnsOnHostRenameAction refreshDnsOnHostRenameAction();

  RegistrarsAction registrarsAction();

  RelockDomainAction relockDomainAction();

  ResaveAllEppResourcesPipelineAction resaveAllEppResourcesPipelineAction();

  ResaveEntityAction resaveEntityAction();

  SecurityAction securityAction();

  SendExpiringCertificateNotificationEmailAction sendExpiringCertificateNotificationEmailAction();

  SyncGroupMembersAction syncGroupMembersAction();

  SyncRegistrarsSheetAction syncRegistrarsSheetAction();

  TldFanoutAction tldFanoutAction();

  TmchCrlAction tmchCrlAction();

  TmchDnlAction tmchDnlAction();

  TmchSmdrlAction tmchSmdrlAction();

  UpdateRegistrarRdapBaseUrlsAction updateRegistrarRdapBaseUrlsAction();

  UpdateUserGroupAction updateUserGroupAction();

  UploadBsaUnavailableDomainsAction uploadBsaUnavailableDomains();

  VerifyOteAction verifyOteAction();

  WhoisAction whoisAction();

  WhoisHttpAction whoisHttpAction();

  WhoisRegistrarFieldsAction whoisRegistrarFieldsAction();

  WipeOutContactHistoryPiiAction wipeOutContactHistoryPiiAction();

  @Subcomponent.Builder
  abstract class Builder implements RequestComponentBuilder<RequestComponent> {
    @Override
    public abstract Builder requestModule(RequestModule requestModule);

    @Override
    public abstract RequestComponent build();
  }

  @Module(subcomponents = RequestComponent.class)
  static class RequestComponentModule {}
}
