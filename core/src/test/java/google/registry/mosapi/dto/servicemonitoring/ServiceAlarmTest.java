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

/** Unit tests for {@link ServiceAlarm}. */
public class ServiceAlarmTest {
  @Test
  void testServiceAlarm_properties() {
    int version = 2;
    long lastUpdate = 1422492450L;
    String alarmed = "Yes";

    ServiceAlarm serviceAlarm = new ServiceAlarm(version, lastUpdate, alarmed);

    assertThat(serviceAlarm.getVersion()).isEqualTo(version);
    assertThat(serviceAlarm.getLastUpdateApiDatabase()).isEqualTo(lastUpdate);
    assertThat(serviceAlarm.getAlarmed()).isEqualTo(alarmed);
  }

  @Test
  void testJsonDeserialization() {
    String json =
        "{"
            + "\"version\": 2,"
            + "\"lastUpdateApiDatabase\": 1422492450,"
            + "\"alarmed\": \"No\""
            + "}";

    Gson gson = new Gson();
    ServiceAlarm serviceAlarm = gson.fromJson(json, ServiceAlarm.class);

    assertThat(serviceAlarm.getVersion()).isEqualTo(2);
    assertThat(serviceAlarm.getLastUpdateApiDatabase()).isEqualTo(1422492450L);
    assertThat(serviceAlarm.getAlarmed()).isEqualTo("No");
  }
}
