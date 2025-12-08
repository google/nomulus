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
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MetricaReportInfo}. */
public class MetricaReportInfoTest {
  /**
   * Verifies that the constructor correctly sets all fields and getters return the expected values.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.3</a>
   */
  @Test
  void testMetricaReportInfo_properties() {
    String domainListDate = "2025-05-20";
    String domainListGenerationDate = "2025-05-21T10:00:00Z";

    MetricaReportInfo info = new MetricaReportInfo(domainListDate, domainListGenerationDate);

    assertThat(info.getDomainListDate()).isEqualTo(domainListDate);
    assertThat(info.getDomainListGenerationDate()).isEqualTo(domainListGenerationDate);
  }

  /**
   * Verifies that the object can be correctly deserialized from JSON, respecting the {@code
   * SerializedName} annotations.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.3</a>
   */
  @Test
  void testJsonDeserialization() {
    // JSON structure based on the array elements defined in Section 9.3
    String json =
        "{"
            + "\"domainListDate\": \"2025-05-20\","
            + "\"domainListGenerationDate\": \"2025-05-21T10:00:00Z\""
            + "}";

    Gson gson = new Gson();
    MetricaReportInfo info = gson.fromJson(json, MetricaReportInfo.class);

    assertThat(info.getDomainListDate()).isEqualTo("2025-05-20");
    assertThat(info.getDomainListGenerationDate()).isEqualTo("2025-05-21T10:00:00Z");
  }
}
