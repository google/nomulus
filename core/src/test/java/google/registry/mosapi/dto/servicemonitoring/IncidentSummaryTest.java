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
import org.junit.jupiter.api.Test;

/** Unit tests for {@link IncidentSummary}. */
public class IncidentSummaryTest {
  @Test
  void testIncidentSummary_properties() {
    String incidentId = "1495811850.1700";
    long startTime = 1495811850L;
    boolean falsePositive = false;
    String state = "Active";
    Long endTime = null;

    IncidentSummary summary =
        new IncidentSummary(incidentId, startTime, falsePositive, state, endTime);

    assertThat(summary.getIncidentID()).isEqualTo(incidentId);
    assertThat(summary.getStartTime()).isEqualTo(startTime);
    assertThat(summary.isFalsePositive()).isFalse();
    assertThat(summary.getState()).isEqualTo(state);
    assertThat(summary.getEndTime()).isNull();
  }

  @Test
  void testJsonDeserialization_resolvedIncident() {
    String json =
        "{"
            + "\"incidentID\": \"1495811850.1700\","
            + "\"startTime\": 1495811850,"
            + "\"falsePositive\": true,"
            + "\"state\": \"Resolved\","
            + "\"endTime\": 1495812000"
            + "}";

    Gson gson = new Gson();
    IncidentSummary summary = gson.fromJson(json, IncidentSummary.class);

    assertThat(summary.getIncidentID()).isEqualTo("1495811850.1700");
    assertThat(summary.getStartTime()).isEqualTo(1495811850L);
    assertThat(summary.isFalsePositive()).isTrue();
    assertThat(summary.getState()).isEqualTo("Resolved");
    assertThat(summary.getEndTime()).isEqualTo(1495812000L);
  }

  @Test
  void testJsonDeserialization_activeIncident() {
    String json =
        "{"
            + "\"incidentID\": \"1495811850.1700\","
            + "\"startTime\": 1495811850,"
            + "\"falsePositive\": false,"
            + "\"state\": \"Active\","
            + "\"endTime\": null"
            + "}";

    Gson gson = new Gson();
    IncidentSummary summary = gson.fromJson(json, IncidentSummary.class);

    assertThat(summary.getState()).isEqualTo("Active");
    assertThat(summary.getEndTime()).isNull();
  }
}
