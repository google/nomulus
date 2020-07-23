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

package google.registry.bigquery;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.bigquery.BigqueryUtils.FieldType.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Dataset;
import com.google.api.services.bigquery.model.Table;
import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link CheckedBigquery}. */
class CheckedBigqueryTest {

  private final Bigquery bigquery = mock(Bigquery.class);
  private final Bigquery.Datasets bigqueryDatasets = mock(Bigquery.Datasets.class);
  private final Bigquery.Datasets.Insert bigqueryDatasetsInsert =
      mock(Bigquery.Datasets.Insert.class);
  private final Bigquery.Tables bigqueryTables = mock(Bigquery.Tables.class);
  private final Bigquery.Tables.Insert bigqueryTablesInsert = mock(Bigquery.Tables.Insert.class);

  private CheckedBigquery checkedBigquery;

  @BeforeEach
  void beforeEach() throws Exception {
    when(bigquery.datasets()).thenReturn(bigqueryDatasets);
    when(bigqueryDatasets.insert(eq("Project-Id"), any(Dataset.class)))
        .thenReturn(bigqueryDatasetsInsert);
    when(bigquery.tables()).thenReturn(bigqueryTables);
    when(bigqueryTables.insert(eq("Project-Id"), any(String.class), any(Table.class)))
        .thenReturn(bigqueryTablesInsert);
    checkedBigquery = new CheckedBigquery();
    checkedBigquery.bigquery = bigquery;
    checkedBigquery.bigquerySchemas =
        new ImmutableMap.Builder<String, ImmutableList<TableFieldSchema>>()
            .put(
                "Table-Id",
                ImmutableList.of(new TableFieldSchema().setName("column1").setType(STRING.name())))
            .put(
                "Table2",
                ImmutableList.of(new TableFieldSchema().setName("column1").setType(STRING.name())))
            .build();
  }

  @Test
  void testSuccess_datastoreCreation() throws Exception {
    checkedBigquery.ensureDataSetExists("Project-Id", "Dataset-Id");

    ArgumentCaptor<Dataset> datasetArg = ArgumentCaptor.forClass(Dataset.class);
    verify(bigqueryDatasets).insert(eq("Project-Id"), datasetArg.capture());
    assertThat(datasetArg.getValue().getDatasetReference().getProjectId())
        .isEqualTo("Project-Id");
    assertThat(datasetArg.getValue().getDatasetReference().getDatasetId())
        .isEqualTo("Dataset-Id");
    verify(bigqueryDatasetsInsert).execute();
  }

  @Test
  void testSuccess_datastoreAndTableCreation() throws Exception {
    checkedBigquery.ensureDataSetAndTableExist("Project-Id", "Dataset2", "Table2");

    ArgumentCaptor<Dataset> datasetArg = ArgumentCaptor.forClass(Dataset.class);
    verify(bigqueryDatasets).insert(eq("Project-Id"), datasetArg.capture());
    assertThat(datasetArg.getValue().getDatasetReference().getProjectId())
        .isEqualTo("Project-Id");
    assertThat(datasetArg.getValue().getDatasetReference().getDatasetId())
        .isEqualTo("Dataset2");
    verify(bigqueryDatasetsInsert).execute();

    ArgumentCaptor<Table> tableArg = ArgumentCaptor.forClass(Table.class);
    verify(bigqueryTables).insert(eq("Project-Id"), eq("Dataset2"), tableArg.capture());
    TableReference ref = tableArg.getValue().getTableReference();
    assertThat(ref.getProjectId()).isEqualTo("Project-Id");
    assertThat(ref.getDatasetId()).isEqualTo("Dataset2");
    assertThat(ref.getTableId()).isEqualTo("Table2");
    assertThat(tableArg.getValue().getSchema().getFields())
        .containsExactly(new TableFieldSchema().setName("column1").setType(STRING.name()));
    verify(bigqueryTablesInsert).execute();
  }
}
