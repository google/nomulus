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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import google.registry.beam.BeamActionTestBase;
import google.registry.testing.FakeClock;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link WipeOutContactHistoryPiiAction}. */
class WipeOutContactHistoryPiiActionTest extends BeamActionTestBase {

  private final DateTime now = DateTime.parse("2019-01-19T01:02:03Z");
  private final FakeClock clock = new FakeClock(now);
  private final Map<String, String> expectedParameters = new HashMap<>();
  private final ArgumentCaptor<LaunchFlexTemplateRequest> launchRequest =
      ArgumentCaptor.forClass(LaunchFlexTemplateRequest.class);
  private WipeOutContactHistoryPiiAction action =
      new WipeOutContactHistoryPiiAction(
          clock,
          false,
          Optional.empty(),
          8,
          "tucketBucket",
          "testProject",
          "testRegion",
          dataflow,
          response);

  @BeforeEach
  void before() {
    expectedParameters.put("registryEnvironment", "UNITTEST");
    expectedParameters.put("isDryRun", "false");
    expectedParameters.put("cutoffTime", "2018-05-19T01:02:03.000Z");
  }

  @Test
  void testSuccess() throws Exception {
    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload())
        .isEqualTo("Launched contact history PII wipeout pipeline: jobid");
    verify(templates, times(1))
        .launch(eq("testProject"), eq("testRegion"), launchRequest.capture());
    assertThat(launchRequest.getValue().getLaunchParameter().getParameters())
        .containsExactlyEntriesIn(expectedParameters);
  }

  @Test
  void testSuccess_providedCutoffTime() throws Exception {
    action =
        new WipeOutContactHistoryPiiAction(
            clock,
            false,
            Optional.of(now.minusYears(1)),
            8,
            "tucketBucket",
            "testProject",
            "testRegion",
            dataflow,
            response);
    action.run();
    expectedParameters.put("cutoffTime", "2018-01-19T01:02:03.000Z");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload())
        .isEqualTo("Launched contact history PII wipeout pipeline: jobid");
    verify(templates, times(1))
        .launch(eq("testProject"), eq("testRegion"), launchRequest.capture());
    assertThat(launchRequest.getValue().getLaunchParameter().getParameters())
        .containsExactlyEntriesIn(expectedParameters);
  }

  @Test
  void testSuccess_dryRun() throws Exception {
    action =
        new WipeOutContactHistoryPiiAction(
            clock,
            true,
            Optional.empty(),
            8,
            "tucketBucket",
            "testProject",
            "testRegion",
            dataflow,
            response);
    action.run();
    expectedParameters.put("isDryRun", "true");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload())
        .isEqualTo("Launched contact history PII wipeout pipeline: jobid");
    verify(templates, times(1))
        .launch(eq("testProject"), eq("testRegion"), launchRequest.capture());
    assertThat(launchRequest.getValue().getLaunchParameter().getParameters())
        .containsExactlyEntriesIn(expectedParameters);
  }

  @Test
  void testFailure_launchError() throws Exception {
    when(launch.execute()).thenThrow(new IOException("cannot launch"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getPayload()).isEqualTo("Pipeline launch failed: cannot launch");
    verify(templates, times(1))
        .launch(eq("testProject"), eq("testRegion"), launchRequest.capture());
    assertThat(launchRequest.getValue().getLaunchParameter().getParameters())
        .containsExactlyEntriesIn(expectedParameters);
  }
}
