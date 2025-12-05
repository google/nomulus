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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.mosapi.client.ServiceMonitoringClient;
import google.registry.mosapi.dto.servicemonitoring.AlarmResponse;
import google.registry.mosapi.dto.servicemonitoring.AlarmStatus;
import google.registry.mosapi.dto.servicemonitoring.ServiceAlarm;
import google.registry.mosapi.exception.MosApiException;
import google.registry.mosapi.services.MosApiAlarmService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MosApiAlarmService}. */
@ExtendWith(MockitoExtension.class)
public class MosApiAlarmServiceTest {
  @Mock private ServiceMonitoringClient serviceMonitoringClient;

  private MosApiAlarmService alarmService;
  private final List<String> tlds = ImmutableList.of("example", "test");
  private final List<String> services = ImmutableList.of("dns", "rdds");

  @BeforeEach
  void setUp() {
    alarmService = new MosApiAlarmService(serviceMonitoringClient, tlds, services);
  }

  @Test
  void checkAllAlarms_success() throws Exception {
    // Mock responses: 'example' has alarms on both, 'test' has no alarms
    ServiceAlarm alarmed = new ServiceAlarm(2, 100L, "Yes");
    ServiceAlarm notAlarmed = new ServiceAlarm(2, 100L, "No");

    when(serviceMonitoringClient.serviceAlarmed(eq("example"), anyString())).thenReturn(alarmed);
    when(serviceMonitoringClient.serviceAlarmed(eq("test"), anyString())).thenReturn(notAlarmed);

    AlarmResponse response = alarmService.checkAllAlarms();

    assertThat(response.getAlarmStatuses()).hasSize(4); // 2 TLDs * 2 Services

    AlarmStatus exampleDns = response.getAlarmStatuses().get(0);
    assertThat(exampleDns.getTld()).isEqualTo("example");
    assertThat(exampleDns.getService()).isEqualTo("dns");
    assertThat(exampleDns.getStatus()).isEqualTo("Yes");
    assertThat(exampleDns.getErrorMessage()).isNull();

    // Verify 'test' statuses
    AlarmStatus testRdds = response.getAlarmStatuses().get(3);
    assertThat(testRdds.getTld()).isEqualTo("test");
    assertThat(testRdds.getStatus()).isEqualTo("No");
  }

  @Test
  void checkAllAlarms_handlesException() throws Exception {
    when(serviceMonitoringClient.serviceAlarmed(anyString(), anyString()))
        .thenReturn(new ServiceAlarm(2, 100L, "No"));
    when(serviceMonitoringClient.serviceAlarmed("example", "dns"))
        .thenThrow(new MosApiException("Connection refused"));

    AlarmResponse response = alarmService.checkAllAlarms();

    assertThat(response.getAlarmStatuses()).hasSize(4);

    // Find the failed status
    AlarmStatus failedStatus =
        response.getAlarmStatuses().stream()
            .filter(s -> s.getTld().equals("example") && s.getService().equals("dns"))
            .findFirst()
            .orElseThrow();

    assertThat(failedStatus.getStatus()).isEqualTo("ERROR");
    assertThat(failedStatus.getErrorMessage()).isEqualTo("Connection refused");
  }
}
