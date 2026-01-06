// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.net.MediaType;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.testing.FakeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link TriggerServiceStateActionTest}. */
@ExtendWith(MockitoExtension.class)
public class TriggerServiceStateActionTest {
  
  private final MosApiStateService stateService = mock(MosApiStateService.class);
  private final FakeResponse response = new FakeResponse();
  private TriggerServiceStateAction action;

  @BeforeEach
  void beforeEach() {
    action = new TriggerServiceStateAction(stateService, response);
  }

  @Test
  void testRun_success() {
    action.run();

    verify(stateService).triggerMetricsForAllServiceStateSummaries();

    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).
        isEqualTo("MoSAPI metrics triggered successfully for all TLDs.");
  }

  @Test
  void testRun_failure_throwsInternalServerError() {
    doThrow(new RuntimeException("Database error"))
        .when(stateService)
        .triggerMetricsForAllServiceStateSummaries();

    InternalServerErrorException thrown =
        assertThrows(InternalServerErrorException.class, () -> action.run());

    assertThat(thrown.getMessage()).contains("Failed to process MoSAPI metrics.");

    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
  }

}
