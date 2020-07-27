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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import google.registry.export.datastore.DatastoreAdmin;
import google.registry.export.datastore.DatastoreAdmin.ListOperations;
import google.registry.export.datastore.Operation.OperationList;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import java.io.IOException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

/** Unit tests for {@link google.registry.tools.ListDatastoreOperationsCommand}. */
class ListDatastoreOperationsCommandTest extends CommandTestCase<ListDatastoreOperationsCommand> {

  @Mock private DatastoreAdmin datastoreAdmin;
  @Mock private ListOperations listOperationsRequest;
  @Captor ArgumentCaptor<String> filterClause;

  private final Clock clock = new FakeClock(new DateTime("2019-01-01T00:00:30Z"));

  @BeforeEach
  void beforeEach() throws IOException {
    command.datastoreAdmin = datastoreAdmin;
    command.clock = clock;
    when(listOperationsRequest.execute()).thenReturn(new OperationList());
  }

  @Test
  void testListAll() throws Exception {
    when(datastoreAdmin.listAll()).thenReturn(listOperationsRequest);
    runCommand();
    verify(datastoreAdmin, times(1)).listAll();
    verifyNoMoreInteractions(datastoreAdmin);
  }

  @Test
  void testListWithFilter() throws Exception {
    when(datastoreAdmin.list(filterClause.capture())).thenReturn(listOperationsRequest);
    runCommand("--start_time_filter=PT30S");
    verify(datastoreAdmin, times(1)).list(filterClause.capture());
    assertThat(filterClause.getValue())
        .isEqualTo("metadata.common.startTime>\"2019-01-01T00:00:00.000Z\"");
    verifyNoMoreInteractions(datastoreAdmin);
  }
}
