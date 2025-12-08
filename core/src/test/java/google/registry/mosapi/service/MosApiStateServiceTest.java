// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package google.registry.mosapi.service;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.mosapi.client.ServiceMonitoringClient;
import google.registry.mosapi.dto.servicemonitoring.ActiveIncidentsSummary;
import google.registry.mosapi.dto.servicemonitoring.AllServicesStateResponse;
import google.registry.mosapi.dto.servicemonitoring.IncidentSummary;
import google.registry.mosapi.dto.servicemonitoring.ServiceStateSummary;
import google.registry.mosapi.dto.servicemonitoring.ServiceStatus;
import google.registry.mosapi.dto.servicemonitoring.TldServiceState;
import google.registry.mosapi.exception.MosApiException;
import google.registry.mosapi.services.MosApiStateService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MosApiStateService}. */
@ExtendWith(MockitoExtension.class)
public class MosApiStateServiceTest {
  @Mock private ServiceMonitoringClient serviceMonitoringClient;

  private MosApiStateService stateService;
  private final List<String> tlds = ImmutableList.of("example", "test");

  @BeforeEach
  void setUp() {
    stateService = new MosApiStateService(serviceMonitoringClient, tlds);
  }

  @Test
  void getServiceStateSummary_success_statusUp() throws Exception {
    TldServiceState state = new TldServiceState("example", 100L, "Up", ImmutableMap.of());

    when(serviceMonitoringClient.getServiceState("example")).thenReturn(state);

    ServiceStateSummary summary = stateService.getServiceStateSummary("example");

    assertThat(summary.getTld()).isEqualTo("example");
    assertThat(summary.getOverallStatus()).isEqualTo("Up");
    assertThat(summary.getActiveIncidents()).isNull();
  }

  @Test
  void getServiceStateSummary_success_statusDown_withIncidents() throws Exception {
    IncidentSummary incident = new IncidentSummary("123", 1000L, false, "Active", null);
    ServiceStatus dnsStatus = new ServiceStatus("Down", 50.0, ImmutableList.of(incident));
    Map<String, ServiceStatus> serviceMap = ImmutableMap.of("DNS", dnsStatus);

    TldServiceState state = new TldServiceState("example", 100L, "Down", serviceMap);

    when(serviceMonitoringClient.getServiceState("example")).thenReturn(state);

    ServiceStateSummary summary = stateService.getServiceStateSummary("example");

    assertThat(summary.getTld()).isEqualTo("example");
    assertThat(summary.getOverallStatus()).isEqualTo("Down");
    assertThat(summary.getActiveIncidents()).hasSize(1);

    ActiveIncidentsSummary activeIncident = summary.getActiveIncidents().get(0);
    assertThat(activeIncident.getService()).isEqualTo("DNS");
    assertThat(activeIncident.getEmergencyThreshold()).isEqualTo(50.0);
    assertThat(activeIncident.getIncidents()).containsExactly(incident);
  }

  @Test
  void getAllServiceStateSummaries_aggregatesAndHandlesErrors() throws Exception {
    // TLD 1: Success
    TldServiceState state = new TldServiceState("example", 100L, "Up", ImmutableMap.of());
    when(serviceMonitoringClient.getServiceState("example")).thenReturn(state);

    // TLD 2: Failure (simulated exception)
    when(serviceMonitoringClient.getServiceState("test"))
        .thenThrow(new MosApiException("Network error"));

    AllServicesStateResponse response = stateService.getAllServiceStateSummaries();

    assertThat(response.getServiceStates()).hasSize(2);

    // Verify successful summary
    ServiceStateSummary successSummary = response.getServiceStates().get(0);
    assertThat(successSummary.getTld()).isEqualTo("example");
    assertThat(successSummary.getOverallStatus()).isEqualTo("Up");

    // Verify error summary
    ServiceStateSummary errorSummary = response.getServiceStates().get(1);
    assertThat(errorSummary.getTld()).isEqualTo("test");
    assertThat(errorSummary.getOverallStatus()).isEqualTo("ERROR");
    assertThat(errorSummary.getActiveIncidents()).isNull();
  }
}
