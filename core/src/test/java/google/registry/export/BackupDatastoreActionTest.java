// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.export.CheckBackupAction.CHECK_BACKUP_KINDS_TO_LOAD_PARAM;
import static google.registry.export.CheckBackupAction.CHECK_BACKUP_NAME_PARAM;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.mockito.Mockito.when;

import com.google.common.base.Joiner;
import google.registry.export.datastore.DatastoreAdmin;
import google.registry.export.datastore.DatastoreAdmin.Export;
import google.registry.export.datastore.Operation;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeResponse;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link BackupDatastoreAction}. */
@ExtendWith(MockitoExtension.class)
public class BackupDatastoreActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine = AppEngineExtension.builder().withTaskQueue().build();

  @Mock private DatastoreAdmin datastoreAdmin;
  @Mock private Export exportRequest;
  @Mock private Operation backupOperation;

  private final FakeResponse response = new FakeResponse();
  private final BackupDatastoreAction action = new BackupDatastoreAction();

  @BeforeEach
  void beforeEach() throws Exception {
    action.datastoreAdmin = datastoreAdmin;
    action.response = response;

    when(datastoreAdmin.export(
            "gs://registry-project-id-datastore-backups", AnnotatedEntities.getBackupKinds()))
        .thenReturn(exportRequest);
    when(exportRequest.execute()).thenReturn(backupOperation);
    when(backupOperation.getName())
        .thenReturn("projects/registry-project-id/operations/ASA1ODYwNjc");
    when(backupOperation.getExportFolderUrl())
        .thenReturn("gs://registry-project-id-datastore-backups/some-id");
  }

  @Test
  void testBackup_enqueuesPollTask() {
    action.run();
    assertTasksEnqueued(
        CheckBackupAction.QUEUE,
        new TaskMatcher()
            .url(CheckBackupAction.PATH)
            .param(CHECK_BACKUP_NAME_PARAM, "projects/registry-project-id/operations/ASA1ODYwNjc")
            .param(
                CHECK_BACKUP_KINDS_TO_LOAD_PARAM,
                Joiner.on(",").join(AnnotatedEntities.getReportingKinds()))
            .method("POST"));
    assertThat(response.getPayload())
        .isEqualTo(
            "Datastore backup started with name: "
                + "projects/registry-project-id/operations/ASA1ODYwNjc\n"
                + "Saving to gs://registry-project-id-datastore-backups/some-id");
  }
}
