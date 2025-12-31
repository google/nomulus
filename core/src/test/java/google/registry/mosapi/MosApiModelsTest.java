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

package google.registry.mosapi;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import google.registry.mosapi.MosApiModels.AllServicesStateResponse;
import google.registry.mosapi.MosApiModels.IncidentSummary;
import google.registry.mosapi.MosApiModels.ServiceStateSummary;
import google.registry.mosapi.MosApiModels.ServiceStatus;
import google.registry.mosapi.MosApiModels.TldServiceState;
import org.junit.Test;

/** Tests for {@link MosApiModels}. */
public final class MosApiModelsTest {

  private static final Gson gson =
      new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

  @Test
  public void testAllServicesStateResponse_nullCollection_initializedToEmpty() {
    AllServicesStateResponse response = new AllServicesStateResponse(null);
    assertThat(response.serviceStates()).isEmpty();
    assertThat(response.serviceStates()).isNotNull();
  }

  @Test
  public void testServiceStateSummary_nullCollection_initializedToEmpty() {
    ServiceStateSummary summary = new ServiceStateSummary("example", "Up", null);
    assertThat(summary.activeIncidents()).isEmpty();
    assertThat(summary.activeIncidents()).isNotNull();
  }

  @Test
  public void testServiceStatus_nullCollection_initializedToEmpty() {
    ServiceStatus status = new ServiceStatus("Up", 0.0, null);
    assertThat(status.incidents()).isEmpty();
    assertThat(status.incidents()).isNotNull();
  }

  @Test
  public void testTldServiceState_nullCollection_initializedToEmpty() {
    TldServiceState state = new TldServiceState("example", 123456L, "Up", null);
    assertThat(state.serviceStatuses()).isEmpty();
    assertThat(state.serviceStatuses()).isNotNull();
  }

  @Test
  public void testIncidentSummary_jsonSerialization() {
    IncidentSummary incident = new IncidentSummary("inc-123", 1000L, false, "Active", 2000L);
    String json = gson.toJson(incident);
    // Using Text Blocks to avoid escaping quotes
    assertThat(json)
        .contains(
            """
            "incidentID":"inc-123"
            """
                .trim());
    assertThat(json)
        .contains(
            """
            "startTime":1000
            """
                .trim());
    assertThat(json)
        .contains(
            """
            "falsePositive":false
            """
                .trim());
    assertThat(json)
        .contains(
            """
            "state":"Active"
            """
                .trim());
    assertThat(json)
        .contains(
            """
            "endTime":2000
            """
                .trim());
  }

  @Test
  public void testServiceStatus_jsonSerialization() {
    IncidentSummary incident = new IncidentSummary("inc-1", 1000L, false, "Resolved", null);
    ServiceStatus status = new ServiceStatus("Down", 75.5, ImmutableList.of(incident));
    String json = gson.toJson(status);
    assertThat(json)
        .contains(
            """
            "status":"Down"
            """
                .trim());
    assertThat(json)
        .contains(
            """
            "emergencyThreshold":75.5
            """
                .trim());
    assertThat(json)
        .contains(
            """
            "incidents":[
            """
                .trim());
  }

  @Test
  public void testTldServiceState_jsonSerialization() {
    ServiceStatus dnsStatus = new ServiceStatus("Up", 0.0, ImmutableList.of());
    TldServiceState state =
        new TldServiceState("app", 1700000000L, "Up", ImmutableMap.of("DNS", dnsStatus));

    String json = gson.toJson(state);
    assertThat(json)
        .contains(
            """
            "tld":"app"
            """
                .trim());
    assertThat(json)
        .contains(
            """
            "status":"Up"
            """
                .trim());
    assertThat(json)
        .contains(
            """
            "testedServices":{"DNS":{
            """
                .trim());
  }

  @Test
  public void testAllServicesStateResponse_jsonSerialization() {
    ServiceStateSummary summary = new ServiceStateSummary("dev", "Up", ImmutableList.of());
    AllServicesStateResponse response = new AllServicesStateResponse(ImmutableList.of(summary));

    String json = gson.toJson(response);
    assertThat(json)
        .contains(
            """
            "serviceStates":[
            """
                .trim());
    assertThat(json)
        .contains(
            """
            "tld":"dev"
            """
                .trim());
  }
}
