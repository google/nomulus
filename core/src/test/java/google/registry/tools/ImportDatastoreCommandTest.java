// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.export.datastore.DatastoreAdmin;
import google.registry.export.datastore.DatastoreAdmin.Get;
import google.registry.export.datastore.DatastoreAdmin.Import;
import google.registry.export.datastore.Operation;
import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link ImportDatastoreCommand}. */
@MockitoSettings(strictness = Strictness.LENIENT)
class ImportDatastoreCommandTest extends CommandTestCase<ImportDatastoreCommand> {

  @Captor ArgumentCaptor<String> backupUrl;
  @Captor ArgumentCaptor<Collection<String>> kinds;
  @Captor ArgumentCaptor<String> operationName;

  @Mock private DatastoreAdmin datastoreAdmin;
  @Mock private Import importRequest;
  @Mock private Get getRequest;
  @Mock private Operation importOperation;
  @Mock private Operation getOperation;

  @BeforeEach
  void beforeEach() throws Exception {
    command.datastoreAdmin = datastoreAdmin;

    when(datastoreAdmin.importBackup(backupUrl.capture(), kinds.capture()))
        .thenReturn(importRequest);
    when(importRequest.execute()).thenReturn(importOperation);
    when(importOperation.getName()).thenReturn("opName");

    when(datastoreAdmin.get(operationName.capture())).thenReturn(getRequest);
    when(getRequest.execute()).thenReturn(getOperation);
  }

  @Test
  void test_importAllKinds_immediateSuccess() throws Exception {
    runCommandForced(
        "--poll_interval", "PT0.001S",
        "--backup_url", "gs://bucket/export-id/export-id.overall_export_metadata");
    assertThat(backupUrl.getValue())
        .isEqualTo("gs://bucket/export-id/export-id.overall_export_metadata");
    assertThat(kinds.getValue()).isEmpty();
    verify(datastoreAdmin, never()).get(anyString());
  }

  @Test
  void test_importSomeKinds_immediateSuccess() throws Exception {
    runCommandForced(
        "--poll_interval",
        "PT0.001S",
        "--backup_url",
        "gs://bucket/export-id/export-id.overall_export_metadata",
        "--kinds",
        "Registrar",
        "--kinds",
        "Registry");
    assertThat(backupUrl.getValue())
        .isEqualTo("gs://bucket/export-id/export-id.overall_export_metadata");
    assertThat(kinds.getValue()).containsExactly("Registrar", "Registry");
    verify(datastoreAdmin, never()).get(anyString());
  }

  @Test
  void test_delayedSuccess_sync() throws Exception {
    when(importOperation.isProcessing()).thenReturn(true);
    when(getOperation.isProcessing()).thenReturn(true).thenReturn(false);
    when(getOperation.isSuccessful()).thenReturn(true);
    runCommandForced(
        "--poll_interval", "PT0.001S",
        "--backup_url", "gs://bucket/export-id/export-id.overall_export_metadata");

    verify(datastoreAdmin, times(1)).get("opName");
  }

  @Test
  void test_delayedSuccess_async() throws Exception {
    when(importOperation.isProcessing()).thenReturn(false);
    when(getOperation.isProcessing()).thenReturn(true).thenReturn(false);
    when(getOperation.isSuccessful()).thenReturn(true);
    runCommandForced(
        "--poll_interval",
        "PT0.001S",
        "--backup_url",
        "gs://bucket/export-id/export-id.overall_export_metadata",
        "--async");

    verify(datastoreAdmin, never()).get("opName");
  }

  @Test
  void test_failure_notAllowedInProduction() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandInEnvironment(
                RegistryToolEnvironment.PRODUCTION,
                "--force",
                "--poll_interval",
                "PT0.001S",
                "--backup_url",
                "gs://bucket/export-id/export-id.overall_export_metadata"));
  }

  @Test
  void test_success_runInProduction() throws Exception {
    runCommandInEnvironment(
        RegistryToolEnvironment.PRODUCTION,
        "--force",
        "--confirm_production_import",
        "PRODUCTION",
        "--poll_interval",
        "PT0.001S",
        "--backup_url",
        "gs://bucket/export-id/export-id.overall_export_metadata");
  }
}
