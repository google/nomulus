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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import google.registry.mosapi.model.AllServicesStateResponse;
import google.registry.mosapi.model.IncidentSummary;
import google.registry.mosapi.model.ServiceStateSummary;
import google.registry.mosapi.model.ServiceStatus;
import google.registry.mosapi.model.TldServiceState;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MosApiStateService}. */
@ExtendWith(MockitoExtension.class)
class MosApiStateServiceTest {

  @Mock private ServiceMonitoringClient client;

  private final ExecutorService executor = MoreExecutors.newDirectExecutorService();

  private MosApiStateService service;

  @BeforeEach
  void setUp() {
    service = new MosApiStateService(client, ImmutableSet.of("tld1", "tld2"), executor);
  }

  @Test
  void testGetServiceStateSummary_upStatus_returnsEmptyIncidents() throws Exception {
    TldServiceState rawState = new TldServiceState("tld1", 12345L, "Up", ImmutableMap.of());
    when(client.getTldServiceState("tld1")).thenReturn(rawState);

    ServiceStateSummary result = service.getServiceStateSummary("tld1");

    assertThat(result.getTld()).isEqualTo("tld1");
    assertThat(result.getOverallStatus()).isEqualTo("Up");
    assertThat(result.getActiveIncidents()).isNull();
  }

  @Test
  void testGetServiceStateSummary_downStatus_filtersActiveIncidents() throws Exception {
    IncidentSummary dnsIncident = new IncidentSummary("inc-1", 100L, false, "Open", null);
    ServiceStatus dnsService = new ServiceStatus("Down", 50.0, ImmutableList.of(dnsIncident));

    ServiceStatus rdapService = new ServiceStatus("Up", 0.0, ImmutableList.of());

    TldServiceState rawState =
        new TldServiceState(
            "tld1", 12345L, "Down", ImmutableMap.of("DNS", dnsService, "RDAP", rdapService));

    when(client.getTldServiceState("tld1")).thenReturn(rawState);

    ServiceStateSummary result = service.getServiceStateSummary("tld1");

    assertThat(result.getOverallStatus()).isEqualTo("Down");
    assertThat(result.getActiveIncidents()).hasSize(1);

    ServiceStatus incidentSummary = result.getActiveIncidents().get(0);
    assertThat(incidentSummary.getStatus()).isEqualTo("DNS");
    assertThat(incidentSummary.getIncidents()).containsExactly(dnsIncident);
  }

  @Test
  void testGetServiceStateSummary_throwsException_whenClientFails() throws Exception {
    when(client.getTldServiceState("tld1")).thenThrow(new MosApiException("Network error", null));

    assertThrows(MosApiException.class, () -> service.getServiceStateSummary("tld1"));
  }

  @Test
  void testGetAllServiceStateSummaries_success() throws Exception {
    TldServiceState state1 = new TldServiceState("tld1", 1L, "Up", ImmutableMap.of());
    TldServiceState state2 = new TldServiceState("tld2", 2L, "Up", ImmutableMap.of());

    when(client.getTldServiceState("tld1")).thenReturn(state1);
    when(client.getTldServiceState("tld2")).thenReturn(state2);

    AllServicesStateResponse response = service.getAllServiceStateSummaries();

    assertThat(response.getServiceStates()).hasSize(2);
    assertThat(response.getServiceStates().stream().map(ServiceStateSummary::getTld))
        .containsExactly("tld1", "tld2");
  }

  @Test
  void testGetAllServiceStateSummaries_partialFailure_returnsErrorState() throws Exception {
    TldServiceState state1 = new TldServiceState("tld1", 1L, "Up", ImmutableMap.of());
    when(client.getTldServiceState("tld1")).thenReturn(state1);

    when(client.getTldServiceState("tld2")).thenThrow(new MosApiException("Failure", null));

    AllServicesStateResponse response = service.getAllServiceStateSummaries();

    assertThat(response.getServiceStates()).hasSize(2);

    ServiceStateSummary summary1 =
        response.getServiceStates().stream()
            .filter(s -> s.getTld().equals("tld1"))
            .findFirst()
            .get();
    assertThat(summary1.getOverallStatus()).isEqualTo("Up");

    ServiceStateSummary summary2 =
        response.getServiceStates().stream()
            .filter(s -> s.getTld().equals("tld2"))
            .findFirst()
            .get();

    assertThat(summary2.getOverallStatus()).isEqualTo("ERROR");
    assertThat(summary2.getActiveIncidents()).isNull();
  }
}
