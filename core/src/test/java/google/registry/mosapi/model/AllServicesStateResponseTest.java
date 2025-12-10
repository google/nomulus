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

package google.registry.mosapi.model;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AllServicesStateResponse}. */
public class AllServicesStateResponseTest {
  private final Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

  @Test
  void testConstructorAndGetter() {
    ServiceStateSummary summary = new ServiceStateSummary("tld", "Up", Collections.emptyList());
    ImmutableList<ServiceStateSummary> summaries = ImmutableList.of(summary);

    AllServicesStateResponse response = new AllServicesStateResponse(summaries);

    assertThat(response.getServiceStates()).containsExactly(summary);
  }

  @Test
  void testJsonSerialization_setsCorrectFieldName() {
    ServiceStateSummary summary = new ServiceStateSummary("test.tld", "Down", null);
    AllServicesStateResponse response = new AllServicesStateResponse(ImmutableList.of(summary));

    String json = gson.toJson(response);

    // Verify the JSON structure contains the specific key
    assertThat(json).contains("\"serviceStates\":");
    assertThat(json).contains("\"tld\":\"test.tld\"");
    assertThat(json).contains("\"overallStatus\":\"Down\"");
  }

  @Test
  void testJsonDeserialization_readsCorrectFieldName() {
    String json =
        "{\"serviceStates\": [{\"tld\": \"example.tld\", "
            + "\"overallStatus\": \"Up\", \"activeIncidents\": []}]}";

    AllServicesStateResponse response =
        gson.fromJson(json, AllServicesStateResponse.class);

    assertThat(response.getServiceStates()).hasSize(1);
    assertThat(response.getServiceStates().get(0).getTld()).isEqualTo("example.tld");
    assertThat(response.getServiceStates().get(0).getOverallStatus()).isEqualTo("Up");
  }

  @Test
  void testJsonSerialization_handlesEmptyList() {
    AllServicesStateResponse response = new AllServicesStateResponse(ImmutableList.of());

    String json = gson.toJson(response);

    assertThat(json).isEqualTo("{\"serviceStates\":[]}");
  }
}
