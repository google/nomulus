// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import google.registry.beam.BeamActionTestBase;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import java.io.IOException;
import java.util.HashMap;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ExpandBillingRecurrencesAction}. */
public class ExpandBillingRecurrencesActionTest extends BeamActionTestBase {

  private final DateTime cursorTime = DateTime.parse("2020-02-01T00:00:00Z");
  private final DateTime now = DateTime.parse("2020-02-02T00:00:00Z");

  private final FakeClock clock = new FakeClock(now);
  private final ExpandBillingRecurrencesAction action = new ExpandBillingRecurrencesAction();
  private final HashMap<String, String> expectedParameters = new HashMap<>();

  private final ArgumentCaptor<LaunchFlexTemplateRequest> launchRequest =
      ArgumentCaptor.forClass(LaunchFlexTemplateRequest.class);

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void before() {
    action.clock = clock;
    action.isDryRun = false;
    action.advanceCursor = true;
    action.startTimeParam = Optional.empty();
    action.endTimeParam = Optional.empty();
    action.projectId = "projectId";
    action.jobRegion = "jobRegion";
    action.stagingBucketUrl = "test-bucket";
    action.dataflow = dataflow;
    action.response = response;
    expectedParameters.put("registryEnvironment", "UNITTEST");
    expectedParameters.put("startTime", "2020-02-01T00:00:00.000Z");
    expectedParameters.put("endTime", "2020-02-02T00:00:00.000Z");
    expectedParameters.put("isDryRun", "false");
    expectedParameters.put("advanceCursor", "true");
    tm().transact(() -> tm().put(Cursor.createGlobal(CursorType.RECURRING_BILLING, cursorTime)));
  }

  @Test
  void testSuccess() throws Exception {
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload())
        .isEqualTo("Launched billing recurrence expansion pipeline: jobid");
    verify(templates, times(1)).launch(eq("projectId"), eq("jobRegion"), launchRequest.capture());
    assertThat(launchRequest.getValue().getLaunchParameter().getParameters())
        .containsExactlyEntriesIn(expectedParameters);
  }

  @Test
  void testSuccess_provideEndTime() throws Exception {
    action.endTimeParam = Optional.of(DateTime.parse("2020-02-01T12:00:00.001Z"));
    expectedParameters.put("endTime", "2020-02-01T12:00:00.001Z");
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload())
        .isEqualTo("Launched billing recurrence expansion pipeline: jobid");
    verify(templates, times(1)).launch(eq("projectId"), eq("jobRegion"), launchRequest.capture());
    assertThat(launchRequest.getValue().getLaunchParameter().getParameters())
        .containsExactlyEntriesIn(expectedParameters);
  }

  @Test
  void testSuccess_provideStartTime() throws Exception {
    action.startTimeParam = Optional.of(DateTime.parse("2020-01-01T12:00:00.001Z"));
    expectedParameters.put("startTime", "2020-01-01T12:00:00.001Z");
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload())
        .isEqualTo("Launched billing recurrence expansion pipeline: jobid");
    verify(templates, times(1)).launch(eq("projectId"), eq("jobRegion"), launchRequest.capture());
    assertThat(launchRequest.getValue().getLaunchParameter().getParameters())
        .containsExactlyEntriesIn(expectedParameters);
  }

  @Test
  void testSuccess_doesNotAdvanceCursor() throws Exception {
    action.advanceCursor = false;
    expectedParameters.put("advanceCursor", "false");
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload())
        .isEqualTo("Launched billing recurrence expansion pipeline: jobid");
    verify(templates, times(1)).launch(eq("projectId"), eq("jobRegion"), launchRequest.capture());
    assertThat(launchRequest.getValue().getLaunchParameter().getParameters())
        .containsExactlyEntriesIn(expectedParameters);
  }

  @Test
  void testSuccess_dryRun() throws Exception {
    action.isDryRun = true;
    action.advanceCursor = false;
    expectedParameters.put("isDryRun", "true");
    expectedParameters.put("advanceCursor", "false");
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload())
        .isEqualTo("Launched billing recurrence expansion pipeline: jobid");
    verify(templates, times(1)).launch(eq("projectId"), eq("jobRegion"), launchRequest.capture());
    assertThat(launchRequest.getValue().getLaunchParameter().getParameters())
        .containsExactlyEntriesIn(expectedParameters);
  }

  @Test
  void testFailure_endTimeAfterNow() throws Exception {
    action.endTimeParam = Optional.of(DateTime.parse("2020-02-03T00:00:00Z"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> action.run());
    assertThat(thrown.getMessage()).contains("must be at or before now");
    verifyNoInteractions(templates);
  }

  @Test
  void testFailure_startTimeAfterEndTime() throws Exception {
    action.startTimeParam = Optional.of(DateTime.parse("2020-02-03T00:00:00Z"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> action.run());
    assertThat(thrown.getMessage()).contains("must be before end time");
    verifyNoInteractions(templates);
  }

  @Test
  void testFailure_AdvanceCursorInDryRun() throws Exception {
    action.isDryRun = true;
    action.advanceCursor = true;
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> action.run());
    assertThat(thrown.getMessage()).contains("Cannot advance the cursor in a dry run");
    verifyNoInteractions(templates);
  }

  @Test
  void testFailure_launchError() throws Exception {
    when(launch.execute()).thenThrow(new IOException("cannot launch"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getPayload()).isEqualTo("Pipeline launch failed: cannot launch");
    verify(templates, times(1)).launch(eq("projectId"), eq("jobRegion"), launchRequest.capture());
    assertThat(launchRequest.getValue().getLaunchParameter().getParameters())
        .containsExactlyEntriesIn(expectedParameters);
  }
}
