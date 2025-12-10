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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link IncidentSummary}. */
public class IncidentSummaryTest {

  // Use GsonBuilder to respect @Expose annotations if needed, though default new Gson() works too.
  private final Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

  @Test
  void testConstructorAndGetters_allFieldsPopulated() {
    IncidentSummary incident =
        new IncidentSummary("INC-001", 1672531200000L, false, "Open", 1672617600000L);

    assertThat(incident.getIncidentID()).isEqualTo("INC-001");
    assertThat(incident.getStartTime()).isEqualTo(1672531200000L);
    assertThat(incident.isFalsePositive()).isFalse();
    assertThat(incident.getState()).isEqualTo("Open");
    assertThat(incident.getEndTime()).isEqualTo(1672617600000L);
  }

  @Test
  void testConstructorAndGetters_nullEndTime() {
    // Tests that endTime can be null (e.g. for an ongoing incident)
    IncidentSummary incident =
        new IncidentSummary("INC-002", 1672531200000L, true, "Closed", null);

    assertThat(incident.getIncidentID()).isEqualTo("INC-002");
    assertThat(incident.isFalsePositive()).isTrue();
    assertThat(incident.getEndTime()).isNull();
  }

  @Test
  void testJsonSerialization() {
    IncidentSummary incident =
        new IncidentSummary("INC-001", 1234567890000L, false, "Active", 1234569990000L);

    String json = gson.toJson(incident);

    // Verify fields are present and correctly named via @SerializedName
    assertThat(json).contains("\"incidentID\":\"INC-001\"");
    assertThat(json).contains("\"startTime\":1234567890000");
    assertThat(json).contains("\"falsePositive\":false");
    assertThat(json).contains("\"state\":\"Active\"");
    assertThat(json).contains("\"endTime\":1234569990000");
  }

  @Test
  void testJsonDeserialization() {
    String json =
        "{"
            + "\"incidentID\": \"INC-999\","
            + "\"startTime\": 1000000,"
            + "\"falsePositive\": true,"
            + "\"state\": \"Resolved\","
            + "\"endTime\": 2000000"
            + "}";

    IncidentSummary incident = gson.fromJson(json, IncidentSummary.class);

    assertThat(incident.getIncidentID()).isEqualTo("INC-999");
    assertThat(incident.getStartTime()).isEqualTo(1000000L);
    assertThat(incident.isFalsePositive()).isTrue();
    assertThat(incident.getState()).isEqualTo("Resolved");
    assertThat(incident.getEndTime()).isEqualTo(2000000L);
  }
}
