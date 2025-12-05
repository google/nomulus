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

/** Unit tests for {@link ServiceDowntime}. */
public class ServiceDowntimeTest {

  @Test
  void testServiceDowntime_properties() {
    int version = 2;
    long lastUpdate = 1422492450L;
    int downtime = 935;
    boolean disabled = false;

    ServiceDowntime serviceDowntime = new ServiceDowntime(version, lastUpdate, downtime, disabled);

    assertThat(serviceDowntime.getVersion()).isEqualTo(version);
    assertThat(serviceDowntime.getLastUpdateApiDatabase()).isEqualTo(lastUpdate);
    assertThat(serviceDowntime.getDowntime()).isEqualTo(downtime);
    assertThat(serviceDowntime.getDisabledMonitoring()).isFalse();
  }

  @Test
  void testJsonDeserialization() {
    // JSON structure based on the example in Specification Section 5.3
    String json =
        "{"
            + "\"version\": 2,"
            + "\"lastUpdateApiDatabase\": 1422492450,"
            + "\"downtime\": 935"
            + "}";

    Gson gson = new Gson();
    ServiceDowntime serviceDowntime = gson.fromJson(json, ServiceDowntime.class);

    assertThat(serviceDowntime.getVersion()).isEqualTo(2);
    assertThat(serviceDowntime.getLastUpdateApiDatabase()).isEqualTo(1422492450L);
    assertThat(serviceDowntime.getDowntime()).isEqualTo(935);
    // disabledMonitoring is not in the standard spec response, so it should be null/default
    assertThat(serviceDowntime.getDisabledMonitoring()).isNull();
  }

  @Test
  void testJsonDeserialization_withDisabledMonitoring() {
    String json =
        "{"
            + "\"version\": 2,"
            + "\"lastUpdateApiDatabase\": 1422492450,"
            + "\"downtime\": 0,"
            + "\"disabledMonitoring\": true"
            + "}";

    Gson gson = new Gson();
    ServiceDowntime serviceDowntime = gson.fromJson(json, ServiceDowntime.class);

    assertThat(serviceDowntime.getDisabledMonitoring()).isTrue();
  }
}
