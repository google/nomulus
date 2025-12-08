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

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TldServicesDowntime}. */
public class TldServicesDowntimeTest {

  @Test
  void testTldServicesDowntime_properties() {
    String tld = "example";
    Map<String, ServiceDowntime> downtimeMap = ImmutableMap.of();
    TldServicesDowntime tldDowntime = new TldServicesDowntime(tld, downtimeMap);
    assertThat(tldDowntime.getTld()).isEqualTo(tld);
    assertThat(tldDowntime.getServiceDowntime()).isEqualTo(downtimeMap);
  }

  @Test
  void testJsonDeserialization() {
    // JSON structure simulating the aggregation of service downtimes for a TLD
    String json =
        "{"
            + "\"tld\": \"example\","
            + "\"serviceDowntime\": {"
            + "  \"DNS\": {"
            + "    \"version\": 2,"
            + "    \"downtime\": 10"
            + "  }"
            + "}"
            + "}";

    Gson gson = new Gson();
    TldServicesDowntime tldDowntime = gson.fromJson(json, TldServicesDowntime.class);

    assertThat(tldDowntime.getTld()).isEqualTo("example");
    assertThat(tldDowntime.getServiceDowntime()).containsKey("DNS");
    assertThat(tldDowntime.getServiceDowntime().get("DNS").getDowntime()).isEqualTo(10);
  }
}
