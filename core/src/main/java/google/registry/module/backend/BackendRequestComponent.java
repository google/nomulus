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

package google.registry.module.backend;

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
import google.registry.flows.FlowComponent;
import google.registry.flows.custom.CustomLogicModule;
import google.registry.monitoring.whitebox.WhiteboxModule;
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

/** Dagger component with per-request lifetime for "backend" App Engine module. */
@RequestScope
@Subcomponent(
    modules = {
      BatchModule.class,
      BillingModule.class,
      CloudDnsWriterModule.class,
      CronModule.class,
      CustomLogicModule.class,
      DnsCountQueryCoordinatorModule.class,
      DnsModule.class,
      DnsUpdateConfigModule.class,
      DnsUpdateWriterModule.class,
      IcannReportingModule.class,
      RdeModule.class,
      ReportingModule.class,
      RequestModule.class,
      SheetModule.class,
      Spec11Module.class,
      TmchModule.class,
      VoidDnsWriterModule.class,
      WhiteboxModule.class,
    })
public interface BackendRequestComponent {

  BrdaCopyAction brdaCopyAction();

  CannedScriptExecutionAction cannedScriptExecutionAction();

  CopyDetailReportsAction copyDetailReportAction();

  DeleteExpiredDomainsAction deleteExpiredDomainsAction();

  DeleteLoadTestDataAction deleteLoadTestDataAction();

  DeleteProberDataAction deleteProberDataAction();

  ExpandBillingRecurrencesAction expandBillingRecurrencesAction();

  ExportDomainListsAction exportDomainListsAction();

  ExportPremiumTermsAction exportPremiumTermsAction();

  ExportReservedTermsAction exportReservedTermsAction();

  FlowComponent.Builder flowComponentBuilder();

  GenerateInvoicesAction generateInvoicesAction();

  GenerateSpec11ReportAction generateSpec11ReportAction();

  IcannReportingStagingAction icannReportingStagingAction();

  IcannReportingUploadAction icannReportingUploadAction();

  NordnUploadAction nordnUploadAction();

  NordnVerifyAction nordnVerifyAction();

  PublishDnsUpdatesAction publishDnsUpdatesAction();

  PublishInvoicesAction uploadInvoicesAction();

  PublishSpec11ReportAction publishSpec11ReportAction();

  ReadDnsRefreshRequestsAction readDnsRefreshRequestsAction();

  RdeReportAction rdeReportAction();

  RdeStagingAction rdeStagingAction();

  RdeUploadAction rdeUploadAction();

  RdeReporter rdeReporter();

  RefreshDnsAction refreshDnsAction();

  RefreshDnsOnHostRenameAction refreshDnsOnHostRenameAction();

  RelockDomainAction relockDomainAction();

  ResaveAllEppResourcesPipelineAction resaveAllEppResourcesPipelineAction();

  ResaveEntityAction resaveEntityAction();

  SendExpiringCertificateNotificationEmailAction sendExpiringCertificateNotificationEmailAction();

  SyncGroupMembersAction syncGroupMembersAction();

  SyncRegistrarsSheetAction syncRegistrarsSheetAction();

  TldFanoutAction tldFanoutAction();

  TmchCrlAction tmchCrlAction();

  TmchDnlAction tmchDnlAction();

  TmchSmdrlAction tmchSmdrlAction();

  UpdateRegistrarRdapBaseUrlsAction updateRegistrarRdapBaseUrlsAction();

  WipeOutContactHistoryPiiAction wipeOutContactHistoryPiiAction();

  @Subcomponent.Builder
  abstract class Builder implements RequestComponentBuilder<BackendRequestComponent> {

    @Override
    public abstract Builder requestModule(RequestModule requestModule);

    @Override
    public abstract BackendRequestComponent build();
  }

  @Module(subcomponents = BackendRequestComponent.class)
  class BackendRequestComponentModule {}
}
