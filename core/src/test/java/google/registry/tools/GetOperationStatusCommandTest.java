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
import static org.mockito.Mockito.when;

import google.registry.export.datastore.DatastoreAdmin;
import google.registry.export.datastore.DatastoreAdmin.Get;
import google.registry.export.datastore.Operation;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link GetOperationStatusCommand}. */
class GetOperationStatusCommandTest extends CommandTestCase<GetOperationStatusCommand> {

  @Mock private DatastoreAdmin datastoreAdmin;
  @Mock private Get getRequest;
  @Captor ArgumentCaptor<String> operationName;

  @BeforeEach
  void beforeEach() throws IOException {
    command.datastoreAdmin = datastoreAdmin;

    when(datastoreAdmin.get(operationName.capture())).thenReturn(getRequest);
    when(getRequest.execute()).thenReturn(new Operation());
  }

  @Test
  void test_success() throws Exception {
    runCommand("projects/project-id/operations/HASH");
    assertThat(operationName.getValue()).isEqualTo("projects/project-id/operations/HASH");
  }

  @Test
  @MockitoSettings(strictness = Strictness.LENIENT)
  void test_failure_tooManyNames() {
    assertThrows(IllegalArgumentException.class, () -> runCommand("a", "b"));
  }
}
