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

import dagger.Module;
import dagger.Subcomponent;
import google.registry.backup.BackupModule;
import google.registry.backup.CommitLogCheckpointAction;
import google.registry.backup.DeleteOldCommitLogsAction;
import google.registry.backup.ExportCommitLogDiffAction;
import google.registry.batch.BatchModule;
import google.registry.batch.DeleteContactsAndHostsAction;
import google.registry.batch.DeleteExpiredDomainsAction;
import google.registry.batch.DeleteLoadTestDataAction;
import google.registry.batch.DeleteProberDataAction;
import google.registry.batch.ExpandRecurringBillingEventsAction;
import google.registry.batch.RefreshDnsOnHostRenameAction;
import google.registry.batch.RelockDomainAction;
import google.registry.batch.ResaveAllEppResourcesAction;
import google.registry.batch.ResaveEntityAction;
import google.registry.batch.WipeOutCloudSqlAction;
import google.registry.cron.CommitLogFanoutAction;
import google.registry.cron.CronModule;
import google.registry.cron.TldFanoutAction;
import google.registry.dns.DnsModule;
import google.registry.dns.PublishDnsUpdatesAction;
import google.registry.dns.ReadDnsQueueAction;
import google.registry.dns.RefreshDnsAction;
import google.registry.dns.writer.VoidDnsWriterModule;
import google.registry.dns.writer.clouddns.CloudDnsWriterModule;
import google.registry.dns.writer.dnsupdate.DnsUpdateConfigModule;
import google.registry.dns.writer.dnsupdate.DnsUpdateWriterModule;
import google.registry.export.BackupDatastoreAction;
import google.registry.export.BigqueryPollJobAction;
import google.registry.export.CheckBackupAction;
import google.registry.export.ExportDomainListsAction;
import google.registry.export.ExportPremiumTermsAction;
import google.registry.export.ExportRequestModule;
import google.registry.export.ExportReservedTermsAction;
import google.registry.export.SyncGroupMembersAction;
import google.registry.export.UpdateSnapshotViewAction;
import google.registry.export.UploadDatastoreBackupAction;
import google.registry.export.sheet.SheetModule;
import google.registry.export.sheet.SyncRegistrarsSheetAction;
import google.registry.flows.FlowComponent;
import google.registry.mapreduce.MapreduceModule;
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
import google.registry.reporting.icann.DnsCountQueryCoordinatorModule;
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
      BackendModule.class,
      BackupModule.class,
      BatchModule.class,
      BillingModule.class,
      CloudDnsWriterModule.class,
      CronModule.class,
      DnsCountQueryCoordinatorModule.class,
      DnsModule.class,
      DnsUpdateConfigModule.class,
      DnsUpdateWriterModule.class,
      ExportRequestModule.class,
      IcannReportingModule.class,
      MapreduceModule.class,
      RdeModule.class,
      ReportingModule.class,
      RequestModule.class,
      SheetModule.class,
      Spec11Module.class,
      TmchModule.class,
      VoidDnsWriterModule.class,
      WhiteboxModule.class,
    })
interface BackendRequestComponent {

  BackupDatastoreAction backupDatastoreAction();

  BigqueryPollJobAction bigqueryPollJobAction();

  BrdaCopyAction brdaCopyAction();

  CheckBackupAction checkBackupAction();

  CommitLogCheckpointAction commitLogCheckpointAction();

  CommitLogFanoutAction commitLogFanoutAction();

  CopyDetailReportsAction copyDetailReportAction();

  DeleteContactsAndHostsAction deleteContactsAndHostsAction();

  DeleteExpiredDomainsAction deleteExpiredDomainsAction();

  DeleteLoadTestDataAction deleteLoadTestDataAction();

  DeleteOldCommitLogsAction deleteOldCommitLogsAction();

  DeleteProberDataAction deleteProberDataAction();

  ExpandRecurringBillingEventsAction expandRecurringBillingEventsAction();

  ExportCommitLogDiffAction exportCommitLogDiffAction();

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

  PublishSpec11ReportAction publishSpec11ReportAction();

  ReadDnsQueueAction readDnsQueueAction();

  RdeReportAction rdeReportAction();

  RdeStagingAction rdeStagingAction();

  RdeUploadAction rdeUploadAction();

  RdeReporter rdeReporter();

  RefreshDnsAction refreshDnsAction();

  RefreshDnsOnHostRenameAction refreshDnsOnHostRenameAction();

  RelockDomainAction relockDomainAction();

  ResaveAllEppResourcesAction resaveAllEppResourcesAction();

  ResaveEntityAction resaveEntityAction();

  SyncGroupMembersAction syncGroupMembersAction();

  SyncRegistrarsSheetAction syncRegistrarsSheetAction();

  TldFanoutAction tldFanoutAction();

  TmchCrlAction tmchCrlAction();

  TmchDnlAction tmchDnlAction();

  TmchSmdrlAction tmchSmdrlAction();

  UploadDatastoreBackupAction uploadDatastoreBackupAction();

  UpdateRegistrarRdapBaseUrlsAction updateRegistrarRdapBaseUrlsAction();

  UpdateSnapshotViewAction updateSnapshotViewAction();

  PublishInvoicesAction uploadInvoicesAction();

  WipeOutCloudSqlAction wipeOutCloudSqlAction();

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
