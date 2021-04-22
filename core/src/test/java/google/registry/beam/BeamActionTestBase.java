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

package google.registry.beam;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.Dataflow.Projects;
import com.google.api.services.dataflow.Dataflow.Projects.Locations;
import com.google.api.services.dataflow.Dataflow.Projects.Locations.FlexTemplates;
import com.google.api.services.dataflow.Dataflow.Projects.Locations.FlexTemplates.Launch;
import com.google.api.services.dataflow.model.Job;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import google.registry.testing.FakeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Base class for all actions that launches a Dataflow Flex template. */
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
public abstract class BeamActionTestBase {

  protected FakeResponse response = new FakeResponse();
  protected Dataflow dataflow = mock(Dataflow.class);
  private Projects projects = mock(Projects.class);
  private Locations locations = mock(Locations.class);
  private FlexTemplates templates = mock(FlexTemplates.class);
  protected Launch launch = mock(Launch.class);
  private LaunchFlexTemplateResponse launchResponse =
      new LaunchFlexTemplateResponse().setJob(new Job().setId("jobid"));

  @BeforeEach
  void beforeEach() throws Exception {
    when(dataflow.projects()).thenReturn(projects);
    when(projects.locations()).thenReturn(locations);
    when(locations.flexTemplates()).thenReturn(templates);
    when(templates.launch(anyString(), anyString(), any(LaunchFlexTemplateRequest.class)))
        .thenReturn(launch);
    when(launch.execute()).thenReturn(launchResponse);
  }
}
