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

package google.registry.mosapi.action;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.mosapi.dto.servicemonitoring.AllTldsDowntime;
import google.registry.mosapi.dto.servicemonitoring.TldServicesDowntime;
import google.registry.mosapi.services.MosApiDowntimeService;
import google.registry.testing.FakeResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link GetServiceDowntimeAction}. */
@ExtendWith(MockitoExtension.class)
public class GetServiceDowntimeActionTest {
  @Mock private MosApiDowntimeService downtimeService;
  private final FakeResponse response = new FakeResponse();
  private final Gson gson = new Gson();

  @Test
  void testRun_singleTld_returnsDowntimeForTld() {
    GetServiceDowntimeAction action =
        new GetServiceDowntimeAction(downtimeService, response, gson, Optional.of("example"));

    TldServicesDowntime mockDowntime = new TldServicesDowntime("example", ImmutableMap.of());
    when(downtimeService.getDowntimeForTld("example")).thenReturn(mockDowntime);

    action.run();

    assertThat(response.getContentType()).isEqualTo(MediaType.JSON_UTF_8);
    assertThat(response.getPayload()).contains("\"tld\":\"example\"");
    verify(downtimeService).getDowntimeForTld("example");
  }

  @Test
  void testRun_noTld_returnsDowntimeForAll() {
    GetServiceDowntimeAction action =
        new GetServiceDowntimeAction(downtimeService, response, gson, Optional.empty());

    AllTldsDowntime mockAllDowntime = new AllTldsDowntime(ImmutableList.of());
    when(downtimeService.getDowntimeForAllTlds()).thenReturn(mockAllDowntime);

    action.run();

    assertThat(response.getContentType()).isEqualTo(MediaType.JSON_UTF_8);
    assertThat(response.getPayload()).contains("\"allDowntimes\":[]");
    verify(downtimeService).getDowntimeForAllTlds();
  }
}
