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

import static com.google.common.collect.Iterables.transform;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.export.UploadDatastoreBackupAction.BACKUP_DATASET;
import static google.registry.export.UploadDatastoreBackupAction.LATEST_BACKUP_VIEW_NAME;
import static google.registry.export.UploadDatastoreBackupAction.PATH;
import static google.registry.export.UploadDatastoreBackupAction.QUEUE;
import static google.registry.export.UploadDatastoreBackupAction.UPLOAD_BACKUP_FOLDER_PARAM;
import static google.registry.export.UploadDatastoreBackupAction.UPLOAD_BACKUP_ID_PARAM;
import static google.registry.export.UploadDatastoreBackupAction.UPLOAD_BACKUP_KINDS_PARAM;
import static google.registry.export.UploadDatastoreBackupAction.enqueueUploadBackupTask;
import static google.registry.export.UploadDatastoreBackupAction.getBackupInfoFileForKind;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Dataset;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.JobReference;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import google.registry.bigquery.CheckedBigquery;
import google.registry.export.BigqueryPollJobAction.BigqueryPollJobEnqueuer;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link UploadDatastoreBackupAction}. */
public class UploadDatastoreBackupActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine = AppEngineExtension.builder().withTaskQueue().build();

  private final CheckedBigquery checkedBigquery = mock(CheckedBigquery.class);
  private final Bigquery bigquery = mock(Bigquery.class);
  private final Bigquery.Jobs bigqueryJobs = mock(Bigquery.Jobs.class);
  private final Bigquery.Jobs.Insert bigqueryJobsInsert = mock(Bigquery.Jobs.Insert.class);
  private final Bigquery.Datasets bigqueryDatasets = mock(Bigquery.Datasets.class);
  private final Bigquery.Datasets.Insert bigqueryDatasetsInsert =
      mock(Bigquery.Datasets.Insert.class);
  private final BigqueryPollJobEnqueuer bigqueryPollEnqueuer = mock(BigqueryPollJobEnqueuer.class);
  private UploadDatastoreBackupAction action;

  @BeforeEach
  void beforeEach() throws Exception {
    when(checkedBigquery.ensureDataSetExists("Project-Id", BACKUP_DATASET)).thenReturn(bigquery);
    when(bigquery.jobs()).thenReturn(bigqueryJobs);
    when(bigqueryJobs.insert(eq("Project-Id"), any(Job.class))).thenReturn(bigqueryJobsInsert);
    when(bigquery.datasets()).thenReturn(bigqueryDatasets);
    when(bigqueryDatasets.insert(eq("Project-Id"), any(Dataset.class)))
        .thenReturn(bigqueryDatasetsInsert);
    action = new UploadDatastoreBackupAction();
    action.checkedBigquery = checkedBigquery;
    action.bigqueryPollEnqueuer = bigqueryPollEnqueuer;
    action.projectId = "Project-Id";
    action.backupFolderUrl = "gs://bucket/path";
    action.backupId = "2018-12-05T17:46:39_92612";
    action.backupKinds = "one,two,three";
  }

  @Test
  void testSuccess_enqueueLoadTask() {
    enqueueUploadBackupTask("id12345", "gs://bucket/path", ImmutableSet.of("one", "two", "three"));
    assertTasksEnqueued(
        QUEUE,
        new TaskMatcher()
            .url(PATH)
            .method("POST")
            .param(UPLOAD_BACKUP_ID_PARAM, "id12345")
            .param(UPLOAD_BACKUP_FOLDER_PARAM, "gs://bucket/path")
            .param(UPLOAD_BACKUP_KINDS_PARAM, "one,two,three"));
  }

  @Test
  void testSuccess_doPost() throws Exception {
    action.run();

    // Verify that checkedBigquery was called in a way that would create the dataset if it didn't
    // already exist.
    verify(checkedBigquery).ensureDataSetExists("Project-Id", BACKUP_DATASET);

    // Capture the load jobs we inserted to do additional checking on them.
    ArgumentCaptor<Job> jobArgument = ArgumentCaptor.forClass(Job.class);
    verify(bigqueryJobs, times(3)).insert(eq("Project-Id"), jobArgument.capture());
    List<Job> jobs = jobArgument.getAllValues();
    assertThat(jobs).hasSize(3);

    // Check properties that should be common to all load jobs.
    for (Job job : jobs) {
      assertThat(job.getJobReference().getProjectId()).isEqualTo("Project-Id");
      JobConfigurationLoad config = job.getConfiguration().getLoad();
      assertThat(config.getSourceFormat()).isEqualTo("DATASTORE_BACKUP");
      assertThat(config.getDestinationTable().getProjectId()).isEqualTo("Project-Id");
      assertThat(config.getDestinationTable().getDatasetId()).isEqualTo(BACKUP_DATASET);
    }

    // Check the job IDs for each load job.
    assertThat(transform(jobs, job -> job.getJobReference().getJobId()))
        .containsExactly(
            "load-backup-2018_12_05T17_46_39_92612-one",
            "load-backup-2018_12_05T17_46_39_92612-two",
            "load-backup-2018_12_05T17_46_39_92612-three");

    // Check the source URI for each load job.
    assertThat(
            transform(
                jobs,
                job -> Iterables.getOnlyElement(job.getConfiguration().getLoad().getSourceUris())))
        .containsExactly(
            "gs://bucket/path/all_namespaces/kind_one/all_namespaces_kind_one.export_metadata",
            "gs://bucket/path/all_namespaces/kind_two/all_namespaces_kind_two.export_metadata",
            "gs://bucket/path/all_namespaces/kind_three/all_namespaces_kind_three.export_metadata");

    // Check the destination table ID for each load job.
    assertThat(
            transform(
                jobs, job -> job.getConfiguration().getLoad().getDestinationTable().getTableId()))
        .containsExactly(
            "2018_12_05T17_46_39_92612_one",
            "2018_12_05T17_46_39_92612_two",
            "2018_12_05T17_46_39_92612_three");

    // Check that we executed the inserted jobs.
    verify(bigqueryJobsInsert, times(3)).execute();

    // Check that the poll tasks for each load job were enqueued.
    verify(bigqueryPollEnqueuer)
        .enqueuePollTask(
            new JobReference()
                .setProjectId("Project-Id")
                .setJobId("load-backup-2018_12_05T17_46_39_92612-one"),
            UpdateSnapshotViewAction.createViewUpdateTask(
                BACKUP_DATASET, "2018_12_05T17_46_39_92612_one", "one", LATEST_BACKUP_VIEW_NAME),
            QueueFactory.getQueue(UpdateSnapshotViewAction.QUEUE));
    verify(bigqueryPollEnqueuer)
        .enqueuePollTask(
            new JobReference()
                .setProjectId("Project-Id")
                .setJobId("load-backup-2018_12_05T17_46_39_92612-two"),
            UpdateSnapshotViewAction.createViewUpdateTask(
                BACKUP_DATASET, "2018_12_05T17_46_39_92612_two", "two", LATEST_BACKUP_VIEW_NAME),
            QueueFactory.getQueue(UpdateSnapshotViewAction.QUEUE));
    verify(bigqueryPollEnqueuer)
        .enqueuePollTask(
            new JobReference()
                .setProjectId("Project-Id")
                .setJobId("load-backup-2018_12_05T17_46_39_92612-three"),
            UpdateSnapshotViewAction.createViewUpdateTask(
                BACKUP_DATASET,
                "2018_12_05T17_46_39_92612_three",
                "three",
                LATEST_BACKUP_VIEW_NAME),
            QueueFactory.getQueue(UpdateSnapshotViewAction.QUEUE));
  }

  @Test
  void testFailure_doPost_bigqueryThrowsException() throws Exception {
    when(bigqueryJobsInsert.execute()).thenThrow(new IOException("The Internet has gone missing"));
    InternalServerErrorException thrown =
        assertThrows(InternalServerErrorException.class, action::run);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Error loading backup: The Internet has gone missing");
  }

  @Test
  void testgetBackupInfoFileForKind() {
    assertThat(
            getBackupInfoFileForKind(
                "gs://BucketName/2018-11-11T00:00:00_12345", "AllocationToken"))
        .isEqualTo(
            "gs://BucketName/2018-11-11T00:00:00_12345/"
                + "all_namespaces/kind_AllocationToken/"
                + "all_namespaces_kind_AllocationToken.export_metadata");
  }
}
