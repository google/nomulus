// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.Job;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link WipeoutDatastoreAction}. */
@ExtendWith(MockitoExtension.class)
class WipeOutDatastoreActionTest {

  @Mock private Dataflow dataflow;
  @Mock private Dataflow.Projects projects;
  @Mock private Dataflow.Projects.Locations locations;
  @Mock private Dataflow.Projects.Locations.FlexTemplates flexTemplates;
  @Mock private Dataflow.Projects.Locations.FlexTemplates.Launch launch;
  private LaunchFlexTemplateResponse launchResponse =
      new LaunchFlexTemplateResponse().setJob(new Job());

  private final FakeClock clock = new FakeClock();
  private final FakeResponse response = new FakeResponse();

  @BeforeEach
  void beforeEach() throws Exception {
    lenient().when(dataflow.projects()).thenReturn(projects);
    lenient().when(projects.locations()).thenReturn(locations);
    lenient().when(locations.flexTemplates()).thenReturn(flexTemplates);
    lenient()
        .when(flexTemplates.launch(anyString(), anyString(), any(LaunchFlexTemplateRequest.class)))
        .thenReturn(launch);
    lenient().when(launch.execute()).thenReturn(launchResponse);
  }

  @Test
  void run_projectNotAllowed() {
    WipeoutDatastoreAction action =
        new WipeoutDatastoreAction(
            "domain-registry", "us-central1", "gs://some-bucket", clock, response, dataflow);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
    verifyNoInteractions(dataflow);
  }

  @Test
  void run_projectAllowed() throws Exception {
    WipeoutDatastoreAction action =
        new WipeoutDatastoreAction(
            "domain-registry-qa", "us-central1", "gs://some-bucket", clock, response, dataflow);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(launch, times(1)).execute();
    verifyNoMoreInteractions(launch);
  }

  @Test
  void run_failure() throws Exception {
    when(launch.execute()).thenThrow(new RuntimeException());
    WipeoutDatastoreAction action =
        new WipeoutDatastoreAction(
            "domain-registry-qa", "us-central1", "gs://some-bucket", clock, response, dataflow);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    verify(launch, times(1)).execute();
    verifyNoMoreInteractions(launch);
  }
}
