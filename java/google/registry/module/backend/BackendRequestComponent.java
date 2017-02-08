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
import google.registry.backup.RestoreCommitLogsAction;
import google.registry.batch.BatchModule;
import google.registry.batch.DeleteContactsAndHostsAction;
import google.registry.batch.DeleteProberDataAction;
import google.registry.batch.ExpandRecurringBillingEventsAction;
import google.registry.batch.RefreshDnsOnHostRenameAction;
import google.registry.batch.VerifyEntityIntegrityAction;
import google.registry.cron.CommitLogFanoutAction;
import google.registry.cron.CronModule;
import google.registry.cron.TldFanoutAction;
import google.registry.dns.DnsModule;
import google.registry.dns.PublishDnsUpdatesAction;
import google.registry.dns.ReadDnsQueueAction;
import google.registry.dns.RefreshDnsAction;
import google.registry.dns.writer.clouddns.CloudDnsWriterModule;
import google.registry.dns.writer.dnsupdate.DnsUpdateConfigModule;
import google.registry.dns.writer.dnsupdate.DnsUpdateWriterModule;
import google.registry.export.BigqueryPollJobAction;
import google.registry.export.CheckSnapshotAction;
import google.registry.export.ExportDomainListsAction;
import google.registry.export.ExportRequestModule;
import google.registry.export.ExportReservedTermsAction;
import google.registry.export.ExportSnapshotAction;
import google.registry.export.LoadSnapshotAction;
import google.registry.export.SyncGroupMembersAction;
import google.registry.export.UpdateSnapshotViewAction;
import google.registry.export.sheet.SheetModule;
import google.registry.export.sheet.SyncRegistrarsSheetAction;
import google.registry.flows.async.AsyncFlowsModule;
import google.registry.mapreduce.MapreduceModule;
import google.registry.monitoring.whitebox.MetricsExportAction;
import google.registry.monitoring.whitebox.WhiteboxModule;
import google.registry.rde.BrdaCopyAction;
import google.registry.rde.RdeModule;
import google.registry.rde.RdeReportAction;
import google.registry.rde.RdeReporter;
import google.registry.rde.RdeStagingAction;
import google.registry.rde.RdeUploadAction;
import google.registry.rde.imports.RdeContactImportAction;
import google.registry.rde.imports.RdeDomainImportAction;
import google.registry.rde.imports.RdeHostImportAction;
import google.registry.rde.imports.RdeHostLinkAction;
import google.registry.rde.imports.RdeImportsModule;
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
        AsyncFlowsModule.class,
        BackendModule.class,
        BackupModule.class,
        BatchModule.class,
        CloudDnsWriterModule.class,
        CronModule.class,
        DnsModule.class,
        DnsUpdateConfigModule.class,
        DnsUpdateWriterModule.class,
        ExportRequestModule.class,
        MapreduceModule.class,
        RdeModule.class,
        RdeImportsModule.class,
        RequestModule.class,
        SheetModule.class,
        TmchModule.class,
        WhiteboxModule.class,
    })
interface BackendRequestComponent {
  BigqueryPollJobAction bigqueryPollJobAction();
  BrdaCopyAction brdaCopyAction();
  CheckSnapshotAction checkSnapshotAction();
  CommitLogCheckpointAction commitLogCheckpointAction();
  CommitLogFanoutAction commitLogFanoutAction();
  DeleteContactsAndHostsAction deleteContactsAndHostsAction();
  DeleteOldCommitLogsAction deleteOldCommitLogsAction();
  DeleteProberDataAction deleteProberDataAction();
  ExpandRecurringBillingEventsAction expandRecurringBillingEventsAction();
  ExportCommitLogDiffAction exportCommitLogDiffAction();
  ExportDomainListsAction exportDomainListsAction();
  ExportReservedTermsAction exportReservedTermsAction();
  ExportSnapshotAction exportSnapshotAction();
  LoadSnapshotAction loadSnapshotAction();
  MetricsExportAction metricsExportAction();
  NordnUploadAction nordnUploadAction();
  NordnVerifyAction nordnVerifyAction();
  PublishDnsUpdatesAction publishDnsUpdatesAction();
  ReadDnsQueueAction readDnsQueueAction();
  RdeContactImportAction rdeContactImportAction();
  RdeDomainImportAction rdeDomainImportAction();
  RdeHostImportAction rdeHostImportAction();
  RdeHostLinkAction rdeHostLinkAction();
  RdeReportAction rdeReportAction();
  RdeStagingAction rdeStagingAction();
  RdeUploadAction rdeUploadAction();
  RdeReporter rdeReporter();
  RefreshDnsAction refreshDnsAction();
  RefreshDnsOnHostRenameAction refreshDnsOnHostRenameAction();
  RestoreCommitLogsAction restoreCommitLogsAction();
  SyncGroupMembersAction syncGroupMembersAction();
  SyncRegistrarsSheetAction syncRegistrarsSheetAction();
  TldFanoutAction tldFanoutAction();
  TmchCrlAction tmchCrlAction();
  TmchDnlAction tmchDnlAction();
  TmchSmdrlAction tmchSmdrlAction();
  UpdateSnapshotViewAction updateSnapshotViewAction();
  VerifyEntityIntegrityAction verifyEntityIntegrityAction();

  @Subcomponent.Builder
  abstract class Builder implements RequestComponentBuilder<BackendRequestComponent, Builder> {
    @Override public abstract Builder requestModule(RequestModule requestModule);
    @Override public abstract BackendRequestComponent build();
  }

  @Module(subcomponents = BackendRequestComponent.class)
  class BackendRequestComponentModule {}
}
