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

/** Unit tests for {@link ActiveIncidentsSummary}. */
public class ActiveIncidentsSummaryTest {
  @Test
  void testActiveIncidentsSummary_properties() {
    String service = "DNS";
    double threshold = 10.5;
    List<IncidentSummary> incidents = Collections.emptyList();

    ActiveIncidentsSummary summary = new ActiveIncidentsSummary(service, threshold, incidents);

    assertThat(summary.getService()).isEqualTo(service);
    assertThat(summary.getEmergencyThreshold()).isEqualTo(threshold);
    assertThat(summary.getIncidents()).isEqualTo(incidents);
  }

  @Test
  void testJsonDeserialization() {
    String json =
        "{"
            + "\"service\": \"RDDS\","
            + "\"emergencyThreshold\": 50.0,"
            + "\"incidents\": []"
            + "}";

    Gson gson = new Gson();
    ActiveIncidentsSummary summary = gson.fromJson(json, ActiveIncidentsSummary.class);

    assertThat(summary.getService()).isEqualTo("RDDS");
    assertThat(summary.getEmergencyThreshold()).isEqualTo(50.0);
    assertThat(summary.getIncidents()).isEmpty();
  }
}
