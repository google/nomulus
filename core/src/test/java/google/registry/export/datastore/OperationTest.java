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

package google.registry.export.datastore;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;

import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import google.registry.export.datastore.Operation.CommonMetadata;
import google.registry.export.datastore.Operation.Metadata;
import google.registry.export.datastore.Operation.Progress;
import google.registry.testing.FakeClock;
import google.registry.testing.TestDataHelper;
import java.io.IOException;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

/** Unit tests for unmarshalling {@link Operation} and its member types. */
class OperationTest {

  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

  @Test
  void testCommonMetadata_unmarshall() throws IOException {
    CommonMetadata commonMetadata = loadJson("common_metadata.json", CommonMetadata.class);
    assertThat(commonMetadata.getState()).isEqualTo("SUCCESSFUL");
    assertThat(commonMetadata.getOperationType()).isEqualTo("EXPORT_ENTITIES");
    assertThat(commonMetadata.getStartTime())
        .isEqualTo(DateTime.parse("2018-10-29T16:01:04.645299Z"));
    assertThat(commonMetadata.getEndTime()).isEmpty();
  }

  @Test
  void testProgress_unmarshall() throws IOException {
    Progress progress = loadJson("progress.json", Progress.class);
    assertThat(progress.getWorkCompleted()).isEqualTo(51797);
    assertThat(progress.getWorkEstimated()).isEqualTo(54513);
  }

  @Test
  void testMetadata_unmarshall() throws IOException {
    Metadata metadata = loadJson("metadata.json", Metadata.class);
    assertThat(metadata.getCommonMetadata().getOperationType()).isEqualTo("EXPORT_ENTITIES");
    assertThat(metadata.getCommonMetadata().getState()).isEqualTo("SUCCESSFUL");
    assertThat(metadata.getCommonMetadata().getStartTime())
        .isEqualTo(DateTime.parse("2018-10-29T16:01:04.645299Z"));
    assertThat(metadata.getCommonMetadata().getEndTime())
        .hasValue(DateTime.parse("2018-10-29T16:02:19.009859Z"));
    assertThat(metadata.getOutputUrlPrefix())
        .isEqualTo("gs://domain-registry-alpha-datastore-export-test/2018-10-29T16:01:04_99364");
  }

  @Test
  void testOperation_unmarshall() throws IOException {
    Operation operation = loadJson("operation.json", Operation.class);
    assertThat(operation.getName())
        .startsWith("projects/domain-registry-alpha/operations/ASAzNjMwOTEyNjUJ");
    assertThat(operation.isProcessing()).isTrue();
    assertThat(operation.isSuccessful()).isFalse();
    assertThat(operation.isDone()).isFalse();
    assertThat(operation.getStartTime()).isEqualTo(DateTime.parse("2018-10-29T16:01:04.645299Z"));
    assertThat(operation.getExportFolderUrl())
        .isEqualTo("gs://domain-registry-alpha-datastore-export-test/2018-10-29T16:01:04_99364");
    assertThat(operation.getExportId()).isEqualTo("2018-10-29T16:01:04_99364");
    assertThat(operation.getKinds()).containsExactly("Registry", "Registrar", "DomainBase");
    assertThat(operation.toPrettyString())
        .isEqualTo(
            TestDataHelper.loadFile(OperationTest.class, "prettyprinted_operation.json").trim());
    assertThat(operation.getProgress()).isEqualTo("Progress: N/A");
  }

  @Test
  void testOperationList_unmarshall() throws IOException {
    Operation.OperationList operationList =
        loadJson("operation_list.json", Operation.OperationList.class);
    assertThat(operationList.toList()).hasSize(2);
    FakeClock clock = new FakeClock(DateTime.parse("2018-10-29T16:01:04.645299Z"));
    clock.advanceOneMilli();
    assertThat(operationList.toList().get(0).getRunningTime(clock)).isEqualTo(Duration.millis(1));
    assertThat(operationList.toList().get(0).getProgress())
        .isEqualTo("Progress: [51797/54513 entities]");
    assertThat(operationList.toList().get(1).getRunningTime(clock))
        .isEqualTo(Duration.standardMinutes(1));
    // Work completed may exceed work estimated
    assertThat(operationList.toList().get(1).getProgress())
        .isEqualTo("Progress: [96908367/73773755 bytes] [51797/54513 entities]");
  }

  private static <T> T loadJson(String fileName, Class<T> type) throws IOException {
    return JSON_FACTORY.fromString(TestDataHelper.loadFile(OperationTest.class, fileName), type);
  }
}
