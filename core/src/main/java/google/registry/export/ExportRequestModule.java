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

package google.registry.export;

import static google.registry.export.BigqueryPollJobAction.CHAINED_TASK_QUEUE_HEADER;
import static google.registry.export.BigqueryPollJobAction.JOB_ID_HEADER;
import static google.registry.export.BigqueryPollJobAction.PROJECT_ID_HEADER;
import static google.registry.export.CheckBackupAction.CHECK_BACKUP_KINDS_TO_LOAD_PARAM;
import static google.registry.export.CheckBackupAction.CHECK_BACKUP_NAME_PARAM;
import static google.registry.export.UpdateSnapshotViewAction.UPDATE_SNAPSHOT_DATASET_ID_PARAM;
import static google.registry.export.UpdateSnapshotViewAction.UPDATE_SNAPSHOT_KIND_PARAM;
import static google.registry.export.UpdateSnapshotViewAction.UPDATE_SNAPSHOT_TABLE_ID_PARAM;
import static google.registry.export.UpdateSnapshotViewAction.UPDATE_SNAPSHOT_VIEWNAME_PARAM;
import static google.registry.export.UploadDatastoreBackupAction.UPLOAD_BACKUP_FOLDER_PARAM;
import static google.registry.export.UploadDatastoreBackupAction.UPLOAD_BACKUP_ID_PARAM;
import static google.registry.export.UploadDatastoreBackupAction.UPLOAD_BACKUP_KINDS_PARAM;
import static google.registry.request.RequestParameters.extractRequiredHeader;
import static google.registry.request.RequestParameters.extractRequiredParameter;

import dagger.Module;
import dagger.Provides;
import google.registry.request.Header;
import google.registry.request.Parameter;
import javax.servlet.http.HttpServletRequest;

/** Dagger module for data export tasks. */
@Module
public final class ExportRequestModule {

  @Provides
  @Parameter(UPDATE_SNAPSHOT_DATASET_ID_PARAM)
  static String provideUpdateSnapshotDatasetId(HttpServletRequest req) {
    return extractRequiredParameter(req, UPDATE_SNAPSHOT_DATASET_ID_PARAM);
  }

  @Provides
  @Parameter(UPDATE_SNAPSHOT_TABLE_ID_PARAM)
  static String provideUpdateSnapshotTableId(HttpServletRequest req) {
    return extractRequiredParameter(req, UPDATE_SNAPSHOT_TABLE_ID_PARAM);
  }

  @Provides
  @Parameter(UPDATE_SNAPSHOT_KIND_PARAM)
  static String provideUpdateSnapshotKind(HttpServletRequest req) {
    return extractRequiredParameter(req, UPDATE_SNAPSHOT_KIND_PARAM);
  }

  @Provides
  @Parameter(UPDATE_SNAPSHOT_VIEWNAME_PARAM)
  static String provideUpdateSnapshotViewName(HttpServletRequest req) {
    return extractRequiredParameter(req, UPDATE_SNAPSHOT_VIEWNAME_PARAM);
  }

  @Provides
  @Parameter(UPLOAD_BACKUP_FOLDER_PARAM)
  static String provideSnapshotUrlPrefix(HttpServletRequest req) {
    return extractRequiredParameter(req, UPLOAD_BACKUP_FOLDER_PARAM);
  }

  @Provides
  @Parameter(UPLOAD_BACKUP_ID_PARAM)
  static String provideLoadSnapshotId(HttpServletRequest req) {
    return extractRequiredParameter(req, UPLOAD_BACKUP_ID_PARAM);
  }

  @Provides
  @Parameter(UPLOAD_BACKUP_KINDS_PARAM)
  static String provideLoadSnapshotKinds(HttpServletRequest req) {
    return extractRequiredParameter(req, UPLOAD_BACKUP_KINDS_PARAM);
  }

  @Provides
  @Parameter(CHECK_BACKUP_NAME_PARAM)
  static String provideCheckSnapshotName(HttpServletRequest req) {
    return extractRequiredParameter(req, CHECK_BACKUP_NAME_PARAM);
  }

  @Provides
  @Parameter(CHECK_BACKUP_KINDS_TO_LOAD_PARAM)
  static String provideCheckSnapshotKindsToLoad(HttpServletRequest req) {
    return extractRequiredParameter(req, CHECK_BACKUP_KINDS_TO_LOAD_PARAM);
  }

  @Provides
  @Header(CHAINED_TASK_QUEUE_HEADER)
  static String provideChainedTaskQueue(HttpServletRequest req) {
    return extractRequiredHeader(req, CHAINED_TASK_QUEUE_HEADER);
  }

  @Provides
  @Header(JOB_ID_HEADER)
  static String provideJobId(HttpServletRequest req) {
    return extractRequiredHeader(req, JOB_ID_HEADER);
  }

  @Provides
  @Header(PROJECT_ID_HEADER)
  static String provideProjectId(HttpServletRequest req) {
    return extractRequiredHeader(req, PROJECT_ID_HEADER);
  }
}
