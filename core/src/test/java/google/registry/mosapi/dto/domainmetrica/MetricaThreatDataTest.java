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

package google.registry.mosapi.dto.domainmetrica;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MetricaThreatData}. */
public class MetricaThreatDataTest {
  @Test
  void testMetricaThreatData_properties() {
    String threatType = "phishing";
    int count = 5;
    List<String> domains = Arrays.asList("example.com", "test.net");

    MetricaThreatData data = new MetricaThreatData(threatType, count, domains);

    assertThat(data.getThreatType()).isEqualTo(threatType);
    assertThat(data.getCount()).isEqualTo(count);
    assertThat(data.getDomains()).containsExactlyElementsIn(domains).inOrder();
  }

  @Test
  void testJsonDeserialization() {
    // JSON structure based on the example in Specification Section 9.1
    String json =
        "{"
            + "\"threatType\": \"spam\","
            + "\"count\": 123,"
            + "\"domains\": [\"test1.example\", \"test2.example\"]"
            + "}";

    Gson gson = new Gson();
    MetricaThreatData data = gson.fromJson(json, MetricaThreatData.class);

    assertThat(data.getThreatType()).isEqualTo("spam");
    assertThat(data.getCount()).isEqualTo(123);
    assertThat(data.getDomains()).containsExactly("test1.example", "test2.example").inOrder();
  }

  @Test
  void testJsonDeserialization_disabledThreat() {
    String json = "{" + "\"threatType\": \"malware\"," + "\"count\": -1," + "\"domains\": []" + "}";

    Gson gson = new Gson();
    MetricaThreatData data = gson.fromJson(json, MetricaThreatData.class);

    assertThat(data.getThreatType()).isEqualTo("malware");
    assertThat(data.getCount()).isEqualTo(-1);
    assertThat(data.getDomains()).isEmpty();
  }
}
