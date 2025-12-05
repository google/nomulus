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
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.mosapi.client.ServiceMonitoringClient;
import google.registry.mosapi.dto.servicemonitoring.AllTldsDowntime;
import google.registry.mosapi.dto.servicemonitoring.ServiceDowntime;
import google.registry.mosapi.dto.servicemonitoring.TldServicesDowntime;
import google.registry.mosapi.exception.MosApiException;
import google.registry.mosapi.services.MosApiDowntimeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MosApiDowntimeService}. */
@ExtendWith(MockitoExtension.class)
public class MosApiDowntimeServiceTest {
  @Mock private ServiceMonitoringClient serviceMonitoringClient;

  private MosApiDowntimeService downtimeService;
  private final List<String> tlds = ImmutableList.of("example", "test");
  private final List<String> services = ImmutableList.of("dns", "rdds");

  @BeforeEach
  void setUp() {
    downtimeService = new MosApiDowntimeService(serviceMonitoringClient, tlds, services);
  }

  @Test
  void getDowntimeForTld_success() throws Exception {
    ServiceDowntime dnsDowntime = new ServiceDowntime(2, 100L, 50, false);
    ServiceDowntime rddsDowntime = new ServiceDowntime(2, 100L, 0, false);

    when(serviceMonitoringClient.getDowntime("example", "dns")).thenReturn(dnsDowntime);
    when(serviceMonitoringClient.getDowntime("example", "rdds")).thenReturn(rddsDowntime);

    TldServicesDowntime result = downtimeService.getDowntimeForTld("example");

    assertThat(result.getTld()).isEqualTo("example");
    assertThat(result.getServiceDowntime()).containsEntry("dns", dnsDowntime);
    assertThat(result.getServiceDowntime()).containsEntry("rdds", rddsDowntime);
  }

  @Test
  void getDowntimeForTld_handlesException() throws Exception {
    ServiceDowntime dnsDowntime = new ServiceDowntime(2, 100L, 50, false);

    when(serviceMonitoringClient.getDowntime("example", "dns")).thenReturn(dnsDowntime);
    when(serviceMonitoringClient.getDowntime("example", "rdds"))
        .thenThrow(new MosApiException("Fetch failed"));

    TldServicesDowntime result = downtimeService.getDowntimeForTld("example");

    assertThat(result.getServiceDowntime()).containsEntry("dns", dnsDowntime);
    assertThat(result.getServiceDowntime()).doesNotContainKey("rdds");
  }

  @Test
  void getDowntimeForAllTlds_success() throws Exception {
    ServiceDowntime dummy = new ServiceDowntime(2, 100L, 10, false);
    when(serviceMonitoringClient.getDowntime(anyString(), anyString())).thenReturn(dummy);

    AllTldsDowntime result = downtimeService.getDowntimeForAllTlds();

    assertThat(result.getAllDowntimes()).hasSize(2); // 2 TLDs
    assertThat(result.getAllDowntimes().get(0).getTld()).isEqualTo("example");
    assertThat(result.getAllDowntimes().get(1).getTld()).isEqualTo("test");
  }
}
