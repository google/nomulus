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
package google.registry.mosapi.dto.servicemonitoring;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AllServicesStateResponse}. */
public class AllServicesStateResponseTest {

  @Test
  void testAllServicesStateResponse_properties() {
    List<ServiceStateSummary> states = Collections.emptyList();
    AllServicesStateResponse response = new AllServicesStateResponse(states);

    assertThat(response.getServiceStates()).isEqualTo(states);
  }

  @Test
  void testJsonDeserialization() {
    // Simulating a JSON object wrapping the list of service states.
    String json = "{ \"serviceStates\": [] }";

    Gson gson = new Gson();
    AllServicesStateResponse response = gson.fromJson(json, AllServicesStateResponse.class);

    assertThat(response).isNotNull();
    assertThat(response.getServiceStates()).isEmpty();
  }
}
