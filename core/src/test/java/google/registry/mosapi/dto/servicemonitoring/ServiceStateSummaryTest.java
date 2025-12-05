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

/** Unit tests for {@link ServiceStateSummary}. */
public class ServiceStateSummaryTest {

  @Test
  void testServiceStateSummary_properties() {
    String tld = "example";
    String status = "Down";
    List<ActiveIncidentsSummary> incidents = Collections.emptyList();

    ServiceStateSummary summary = new ServiceStateSummary(tld, status, incidents);

    assertThat(summary.getTld()).isEqualTo(tld);
    assertThat(summary.getOverallStatus()).isEqualTo(status);
    assertThat(summary.getActiveIncidents()).isEqualTo(incidents);
  }

  @Test
  void testJsonDeserialization() {
    String json =
        "{"
            + "\"tld\": \"example\","
            + "\"overallStatus\": \"Up\","
            + "\"activeIncidents\": []"
            + "}";

    Gson gson = new Gson();
    ServiceStateSummary summary = gson.fromJson(json, ServiceStateSummary.class);

    assertThat(summary.getTld()).isEqualTo("example");
    assertThat(summary.getOverallStatus()).isEqualTo("Up");
    assertThat(summary.getActiveIncidents()).isEmpty();
  }

  /**
   * Verifies deserialization when the optional {@code activeIncidents} field is missing (null).
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  @Test
  void testJsonDeserialization_nullIncidents() {
    String json = "{" + "\"tld\": \"example\"," + "\"overallStatus\": \"Up\"" + "}";

    Gson gson = new Gson();
    ServiceStateSummary summary = gson.fromJson(json, ServiceStateSummary.class);

    assertThat(summary.getTld()).isEqualTo("example");
    assertThat(summary.getOverallStatus()).isEqualTo("Up");
    assertThat(summary.getActiveIncidents()).isNull();
  }
}
