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
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link MetricaReport}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MetricaReportTest {

  @Test
  void testMetricaReport_properties() {
    int version = 2;
    String tld = "example";
    String date = "2025-05-20";
    int abuseCount = 42;
    List<MetricaThreatData> threats = Collections.emptyList();

    MetricaReport report = new MetricaReport(version, tld, date, abuseCount, threats);

    assertThat(report.getVersion()).isEqualTo(version);
    assertThat(report.getTld()).isEqualTo(tld);
    assertThat(report.getDomainListDate()).isEqualTo(date);
    assertThat(report.getUniqueAbuseDomains()).isEqualTo(abuseCount);
    assertThat(report.getThreats()).isEqualTo(threats);
  }

  @Test
  void testJsonDeserialization() {
    String json =
        "{"
            + "\"version\": 2,"
            + "\"tld\": \"test_tld\","
            + "\"domainListDate\": \"2025-01-01\","
            + "\"uniqueAbuseDomains\": 10,"
            + "\"domainListData\": []"
            + "}";

    Gson gson = new Gson();
    MetricaReport report = gson.fromJson(json, MetricaReport.class);

    assertThat(report.getVersion()).isEqualTo(2);
    assertThat(report.getTld()).isEqualTo("test_tld");
    assertThat(report.getDomainListDate()).isEqualTo("2025-01-01");
    assertThat(report.getUniqueAbuseDomains()).isEqualTo(10);
    assertThat(report.getThreats()).isEmpty();
  }
}
