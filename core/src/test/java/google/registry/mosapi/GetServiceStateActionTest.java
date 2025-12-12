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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.mosapi.model.AllServicesStateResponse;
import google.registry.mosapi.model.ServiceStateSummary;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.testing.FakeResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link GetServiceStateAction}. */
@ExtendWith(MockitoExtension.class)
public class GetServiceStateActionTest {
  @Mock private MosApiStateService stateService;
  private final FakeResponse response = new FakeResponse();
  private final Gson gson = new Gson();

  @Test
  void testRun_singleTld_returnsStateForTld() throws Exception {
    GetServiceStateAction action =
        new GetServiceStateAction(stateService, response, gson, Optional.of("example"));

    ServiceStateSummary summary = new ServiceStateSummary("example", "Up", null);
    when(stateService.getServiceStateSummary("example")).thenReturn(summary);

    action.run();

    assertThat(response.getContentType()).isEqualTo(MediaType.JSON_UTF_8);
    assertThat(response.getPayload()).contains("\"overallStatus\":\"Up\"");
    verify(stateService).getServiceStateSummary("example");
  }

  @Test
  void testRun_noTld_returnsStateForAll() {
    GetServiceStateAction action =
        new GetServiceStateAction(stateService, response, gson, Optional.empty());

    AllServicesStateResponse allStates = new AllServicesStateResponse(ImmutableList.of());
    when(stateService.getAllServiceStateSummaries()).thenReturn(allStates);

    action.run();

    assertThat(response.getContentType()).isEqualTo(MediaType.JSON_UTF_8);
    assertThat(response.getPayload()).contains("\"serviceStates\":[]");
    verify(stateService).getAllServiceStateSummaries();
  }

  @Test
  void testRun_serviceThrowsException_throwsServiceUnavailable() throws Exception {
    GetServiceStateAction action =
        new GetServiceStateAction(stateService, response, gson, Optional.of("example"));

    doThrow(new MosApiException("Backend error", null))
        .when(stateService)
        .getServiceStateSummary("example");

    ServiceUnavailableException thrown =
        assertThrows(ServiceUnavailableException.class, action::run);

    assertThat(thrown).hasMessageThat().isEqualTo("Error fetching MoSAPI service state.");
  }
}
