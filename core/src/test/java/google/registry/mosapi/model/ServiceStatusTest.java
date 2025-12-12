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

/** Unit tests for {@link ServiceStatus}. */
public class ServiceStatusTest {

  private final Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

  @Test
  void testConstructorAndGetters_emptyIncidents() {
    ServiceStatus serviceStatus = new ServiceStatus("Up", 0.0, Collections.emptyList());

    assertThat(serviceStatus.getStatus()).isEqualTo("Up");
    assertThat(serviceStatus.getEmergencyThreshold()).isEqualTo(0.0);
    assertThat(serviceStatus.getIncidents()).isEmpty();
  }

  @Test
  void testConstructorAndGetters_withIncidents() {
    IncidentSummary incident = new IncidentSummary("I1", 100L, false, "Open", null);
    ServiceStatus serviceStatus = new ServiceStatus("Down", 50.5, ImmutableList.of(incident));

    assertThat(serviceStatus.getStatus()).isEqualTo("Down");
    assertThat(serviceStatus.getEmergencyThreshold()).isEqualTo(50.5);
    assertThat(serviceStatus.getIncidents()).containsExactly(incident);
  }

  @Test
  void testJsonSerialization() {
    IncidentSummary incident = new IncidentSummary("I1", 100L, false, "Open", null);
    ServiceStatus serviceStatus = new ServiceStatus("Down", 99.9, ImmutableList.of(incident));

    String json = gson.toJson(serviceStatus);

    assertThat(json).contains("\"status\":\"Down\"");
    assertThat(json).contains("\"emergencyThreshold\":99.9");
    assertThat(json).contains("\"incidents\":");
    assertThat(json).contains("\"incidentID\":\"I1\"");
  }

  @Test
  void testJsonDeserialization() {
    String json =
        "{"
            + "\"status\": \"Disabled\","
            + "\"emergencyThreshold\": 10.0,"
            + "\"incidents\": [{"
            + "  \"incidentID\": \"I2\","
            + "  \"startTime\": 200,"
            + "  \"falsePositive\": true,"
            + "  \"state\": \"Closed\""
            + "}]"
            + "}";

    ServiceStatus serviceStatus = gson.fromJson(json, ServiceStatus.class);

    assertThat(serviceStatus.getStatus()).isEqualTo("Disabled");
    assertThat(serviceStatus.getEmergencyThreshold()).isEqualTo(10.0);
    assertThat(serviceStatus.getIncidents()).hasSize(1);
    assertThat(serviceStatus.getIncidents().get(0).getIncidentID()).isEqualTo("I2");
  }
}
