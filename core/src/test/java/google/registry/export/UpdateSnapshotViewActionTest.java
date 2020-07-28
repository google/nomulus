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

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.export.UpdateSnapshotViewAction.QUEUE;
import static google.registry.export.UpdateSnapshotViewAction.UPDATE_SNAPSHOT_DATASET_ID_PARAM;
import static google.registry.export.UpdateSnapshotViewAction.UPDATE_SNAPSHOT_KIND_PARAM;
import static google.registry.export.UpdateSnapshotViewAction.UPDATE_SNAPSHOT_TABLE_ID_PARAM;
import static google.registry.export.UpdateSnapshotViewAction.UPDATE_SNAPSHOT_VIEWNAME_PARAM;
import static google.registry.export.UpdateSnapshotViewAction.createViewUpdateTask;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Dataset;
import com.google.api.services.bigquery.model.Table;
import com.google.common.collect.Iterables;
import google.registry.bigquery.CheckedBigquery;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Unit tests for {@link UpdateSnapshotViewAction}. */
public class UpdateSnapshotViewActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine = AppEngineExtension.builder().withTaskQueue().build();

  private final CheckedBigquery checkedBigquery = mock(CheckedBigquery.class);
  private final Bigquery bigquery = mock(Bigquery.class);
  private final Bigquery.Datasets bigqueryDatasets = mock(Bigquery.Datasets.class);
  private final Bigquery.Datasets.Insert bigqueryDatasetsInsert =
      mock(Bigquery.Datasets.Insert.class);
  private final Bigquery.Tables bigqueryTables = mock(Bigquery.Tables.class);
  private final Bigquery.Tables.Update bigqueryTablesUpdate = mock(Bigquery.Tables.Update.class);

  private UpdateSnapshotViewAction action;

  @BeforeEach
  void beforeEach() throws Exception {
    when(checkedBigquery.ensureDataSetExists(anyString(), anyString())).thenReturn(bigquery);
    when(bigquery.datasets()).thenReturn(bigqueryDatasets);
    when(bigqueryDatasets.insert(anyString(), any(Dataset.class)))
        .thenReturn(bigqueryDatasetsInsert);
    when(bigquery.tables()).thenReturn(bigqueryTables);
    when(bigqueryTables.update(anyString(), anyString(), anyString(), any(Table.class)))
        .thenReturn(bigqueryTablesUpdate);

    action = new UpdateSnapshotViewAction();
    action.checkedBigquery = checkedBigquery;
    action.datasetId = "some_dataset";
    action.kindName = "fookind";
    action.viewName = "latest_datastore_export";
    action.projectId = "myproject";
    action.tableId = "12345_fookind";
  }

  @Test
  void testSuccess_createViewUpdateTask() {
    getQueue(QUEUE)
        .add(
            createViewUpdateTask(
                "some_dataset", "12345_fookind", "fookind", "latest_datastore_export"));
    assertTasksEnqueued(
        QUEUE,
        new TaskMatcher()
            .url(UpdateSnapshotViewAction.PATH)
            .method("POST")
            .param(UPDATE_SNAPSHOT_DATASET_ID_PARAM, "some_dataset")
            .param(UPDATE_SNAPSHOT_TABLE_ID_PARAM, "12345_fookind")
            .param(UPDATE_SNAPSHOT_KIND_PARAM, "fookind")
            .param(UPDATE_SNAPSHOT_VIEWNAME_PARAM, "latest_datastore_export"));
  }

  @Test
  void testSuccess_doPost() throws Exception {
    action.run();

    InOrder factoryOrder = inOrder(checkedBigquery);
    // Check that the BigQuery factory was called in such a way that the dataset would be created
    // if it didn't already exist.
    factoryOrder
        .verify(checkedBigquery)
        .ensureDataSetExists("myproject", "latest_datastore_export");

    // Check that we updated both views
    InOrder tableOrder = inOrder(bigqueryTables);
    ArgumentCaptor<Table> tableArg = ArgumentCaptor.forClass(Table.class);
    tableOrder
        .verify(bigqueryTables)
        .update(eq("myproject"), eq("latest_datastore_export"), eq("fookind"), tableArg.capture());
    Iterable<String> actualQueries =
        Iterables.transform(tableArg.getAllValues(), table -> table.getView().getQuery());
    assertThat(actualQueries)
        .containsExactly("#standardSQL\nSELECT * FROM `myproject.some_dataset.12345_fookind`");
  }

  @Test
  void testFailure_bigqueryConnectionThrowsError() throws Exception {
    when(bigqueryTables.update(anyString(), anyString(), anyString(), any(Table.class)))
        .thenThrow(new IOException("I'm sorry Dave, I can't let you do that"));
    InternalServerErrorException thrown =
        assertThrows(InternalServerErrorException.class, action::run);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Could not update snapshot view latest_datastore_export for table 12345_fookind");
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("I'm sorry Dave");
  }
}
