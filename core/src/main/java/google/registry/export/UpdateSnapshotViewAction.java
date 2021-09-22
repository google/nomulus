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

import static google.registry.request.Action.Method.POST;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Table;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.ViewDefinition;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.common.flogger.FluentLogger;
import google.registry.bigquery.CheckedBigquery;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.SqlTemplate;
import java.io.IOException;
import javax.inject.Inject;

/** Update a well-known view to point at a certain Datastore snapshot table in BigQuery. */
@Action(
    service = Action.Service.BACKEND,
    path = UpdateSnapshotViewAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class UpdateSnapshotViewAction implements Runnable {

  /** Headers for passing parameters into the servlet. */
  static final String UPDATE_SNAPSHOT_DATASET_ID_PARAM = "dataset";

  static final String UPDATE_SNAPSHOT_TABLE_ID_PARAM = "table";
  static final String UPDATE_SNAPSHOT_KIND_PARAM = "kind";
  static final String UPDATE_SNAPSHOT_VIEWNAME_PARAM = "viewname";

  /** Servlet-specific details needed for enqueuing tasks against itself. */
  // For now this queue is shared by the backup workflows started by BackupDatastoreAction.
  // TODO(weiminyu): update queue name (snapshot->backup) after ExportSnapshot flow is removed.
  static final String QUEUE = "export-snapshot-update-view"; // See queue.xml.

  static final String PATH = "/_dr/task/updateSnapshotView"; // See web.xml.

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject
  @Parameter(UPDATE_SNAPSHOT_DATASET_ID_PARAM)
  String datasetId;

  @Inject
  @Parameter(UPDATE_SNAPSHOT_TABLE_ID_PARAM)
  String tableId;

  @Inject
  @Parameter(UPDATE_SNAPSHOT_KIND_PARAM)
  String kindName;

  @Inject
  @Parameter(UPDATE_SNAPSHOT_VIEWNAME_PARAM)
  String viewName;

  @Inject
  @Config("projectId")
  String projectId;

  @Inject CheckedBigquery checkedBigquery;

  @Inject
  UpdateSnapshotViewAction() {}

  /** Create a task for updating a snapshot view. */
  static TaskOptions createViewUpdateTask(
      String datasetId, String tableId, String kindName, String viewName) {
    return TaskOptions.Builder.withUrl(PATH)
        .method(Method.POST)
        .param(UPDATE_SNAPSHOT_DATASET_ID_PARAM, datasetId)
        .param(UPDATE_SNAPSHOT_TABLE_ID_PARAM, tableId)
        .param(UPDATE_SNAPSHOT_KIND_PARAM, kindName)
        .param(UPDATE_SNAPSHOT_VIEWNAME_PARAM, viewName);
  }

  @Override
  public void run() {
    try {
      SqlTemplate sqlTemplate =
          SqlTemplate.create(
              "#standardSQL\nSELECT * FROM `%PROJECT%.%SOURCE_DATASET%.%SOURCE_TABLE%`");
      updateSnapshotView(datasetId, tableId, kindName, viewName, sqlTemplate);
    } catch (Throwable e) {
      throw new InternalServerErrorException(
          String.format("Could not update snapshot view %s for table %s", viewName, tableId), e);
    }
  }

  private void updateSnapshotView(
      String sourceDatasetId,
      String sourceTableId,
      String kindName,
      String viewDataset,
      SqlTemplate viewQueryTemplate)
      throws IOException {

    Bigquery bigquery = checkedBigquery.ensureDataSetExists(projectId, viewDataset);
    updateTable(
        bigquery,
        new Table()
            .setTableReference(
                new TableReference()
                    .setProjectId(projectId)
                    .setDatasetId(viewDataset)
                    .setTableId(kindName))
            .setView(
                new ViewDefinition()
                    .setUseLegacySql(false)
                    .setQuery(
                        viewQueryTemplate
                            .put("PROJECT", projectId)
                            .put("SOURCE_DATASET", sourceDatasetId)
                            .put("SOURCE_TABLE", sourceTableId)
                            .build())));

    logger.atInfo().log(
        "Updated view [%s:%s.%s] to point at snapshot table [%s:%s.%s].",
        projectId, viewDataset, kindName, projectId, sourceDatasetId, sourceTableId);
  }

  private static void updateTable(Bigquery bigquery, Table table) throws IOException {
    TableReference ref = table.getTableReference();
    try {
      bigquery
          .tables()
          .update(ref.getProjectId(), ref.getDatasetId(), ref.getTableId(), table)
          .execute();
    } catch (GoogleJsonResponseException e) {
      if (e.getDetails() != null && e.getDetails().getCode() == 404) {
        bigquery.tables().insert(ref.getProjectId(), ref.getDatasetId(), table).execute();
      } else {
        logger.atWarning().withCause(e).log("UpdateSnapshotViewAction errored out.");
      }
    }
  }
}
