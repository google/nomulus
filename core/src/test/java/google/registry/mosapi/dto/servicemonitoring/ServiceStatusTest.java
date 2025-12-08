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

/** Unit tests for {@link ServiceStatus}. */
public class ServiceStatusTest {

  @Test
  void testServiceStatus_properties() {
    String status = "Down";
    double threshold = 10.0;
    List<IncidentSummary> incidents = Collections.emptyList();

    ServiceStatus serviceStatus = new ServiceStatus(status, threshold, incidents);

    assertThat(serviceStatus.getStatus()).isEqualTo(status);
    assertThat(serviceStatus.getEmergencyThreshold()).isEqualTo(threshold);
    assertThat(serviceStatus.getIncidents()).isEqualTo(incidents);
  }

  @Test
  void testJsonDeserialization() {
    // JSON structure based on the example in Specification Section 5.1
    String json =
        "{" + "\"status\": \"Down\"," + "\"emergencyThreshold\": 10.0," + "\"incidents\": []" + "}";

    Gson gson = new Gson();
    ServiceStatus serviceStatus = gson.fromJson(json, ServiceStatus.class);

    assertThat(serviceStatus.getStatus()).isEqualTo("Down");
    assertThat(serviceStatus.getEmergencyThreshold()).isEqualTo(10.0);
    assertThat(serviceStatus.getIncidents()).isEmpty();
  }
}
