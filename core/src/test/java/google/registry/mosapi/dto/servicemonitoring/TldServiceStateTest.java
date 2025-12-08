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

/** Unit tests for {@link TldServiceState}. */
public class TldServiceStateTest {

  @Test
  void testServiceState_properties() {
    String tld = "example";
    long lastUpdate = 1496923082L;
    String status = "Down";
    Map<String, ServiceStatus> services = ImmutableMap.of();

    TldServiceState state = new TldServiceState(tld, lastUpdate, status, services);

    assertThat(state.getTld()).isEqualTo(tld);
    assertThat(state.getLastUpdateApiDatabase()).isEqualTo(lastUpdate);
    assertThat(state.getStatus()).isEqualTo(status);
    assertThat(state.getServiceStatuses()).isEqualTo(services);
  }

  /**
   * Verifies that the object can be correctly deserialized from JSON.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  @Test
  void testJsonDeserialization() {
    String json =
        "{"
            + "\"tld\": \"example\","
            + "\"lastUpdateApiDatabase\": 1496923082,"
            + "\"status\": \"Down\","
            + "\"testedServices\": {"
            + "  \"DNS\": {},"
            + "  \"RDDS\": {}"
            + "}"
            + "}";

    Gson gson = new Gson();
    TldServiceState state = gson.fromJson(json, TldServiceState.class);

    assertThat(state.getTld()).isEqualTo("example");
    assertThat(state.getLastUpdateApiDatabase()).isEqualTo(1496923082L);
    assertThat(state.getStatus()).isEqualTo("Down");
    assertThat(state.getServiceStatuses()).containsKey("DNS");
    assertThat(state.getServiceStatuses()).containsKey("RDDS");
  }
}
