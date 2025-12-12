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

package google.registry.mosapi.model;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ServiceStateSummary}. */
public class ServiceStateSummaryTest {

  private final Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

  @Test
  void testConstructorAndGetters_withIncidents() {
    ServiceStatus status = new ServiceStatus("Down", 100.0, Collections.emptyList());
    ServiceStateSummary summary =
        new ServiceStateSummary("example.tld", "Down", ImmutableList.of(status));

    assertThat(summary.getTld()).isEqualTo("example.tld");
    assertThat(summary.getOverallStatus()).isEqualTo("Down");
    assertThat(summary.getActiveIncidents()).containsExactly(status);
  }

  @Test
  void testConstructorAndGetters_nullIncidents() {
    ServiceStateSummary summary = new ServiceStateSummary("example.tld", "Up", null);

    assertThat(summary.getTld()).isEqualTo("example.tld");
    assertThat(summary.getOverallStatus()).isEqualTo("Up");
    assertThat(summary.getActiveIncidents()).isNull();
  }

  @Test
  void testJsonSerialization_includesAllFields() {
    ServiceStatus status = new ServiceStatus("Down", 50.0, null);
    ServiceStateSummary summary =
        new ServiceStateSummary("test.tld", "Down", ImmutableList.of(status));

    String json = gson.toJson(summary);

    assertThat(json).contains("\"tld\":\"test.tld\"");
    assertThat(json).contains("\"overallStatus\":\"Down\"");
    assertThat(json).contains("\"activeIncidents\":");
  }

  @Test
  void testJsonSerialization_excludesNullIncidents_ifNotConfiguredToSerializeNulls() {

    ServiceStateSummary summary = new ServiceStateSummary("test.tld", "Up", null);

    String json = gson.toJson(summary);

    assertThat(json).contains("\"tld\":\"test.tld\"");
    assertThat(json).contains("\"overallStatus\":\"Up\"");
    assertThat(json).doesNotContain("\"activeIncidents\"");
  }

  @Test
  void testJsonDeserialization() {
    String json =
        "{"
            + "\"tld\": \"example.tld\","
            + "\"overallStatus\": \"Down\","
            + "\"activeIncidents\": ["
            + "  {\"status\": \"Down\", \"emergencyThreshold\": 100.0, \"incidents\": []}"
            + "]"
            + "}";

    ServiceStateSummary summary = gson.fromJson(json, ServiceStateSummary.class);

    assertThat(summary.getTld()).isEqualTo("example.tld");
    assertThat(summary.getOverallStatus()).isEqualTo("Down");
    assertThat(summary.getActiveIncidents()).hasSize(1);
    assertThat(summary.getActiveIncidents().get(0).getStatus()).isEqualTo("Down");
  }
}
